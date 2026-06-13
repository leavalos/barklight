package com.huesync.tv.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.huesync.tv.MainActivity
import com.huesync.tv.R
import com.huesync.tv.analyzer.ColorAnalyzer
import com.huesync.tv.hue.HueBridge
import com.huesync.tv.model.Config
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * CaptureService v2 — sin MediaProjection, sin VirtualDisplay.
 *
 * Conecta al BarkLight Server JAR que corre en el TV via ADB shell.
 * El servidor usa SurfaceControl (accesible desde shell uid) para leer
 * el framebuffer directamente sin crear un VirtualDisplay.
 *
 * Resultado: el decoder de video NO tiene competencia en la GPU.
 *
 * Setup requerido (una sola vez desde PC/Pi):
 *   adb push barklight-server.jar /data/local/tmp/
 *   adb shell CLASSPATH=/data/local/tmp/barklight-server.jar \
 *             app_process / com.barklight.server.BarkLightServer &
 *
 * El servidor escucha en localhost:7070.
 * Esta app conecta y pide frames bajo demanda.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val CHANNEL_ID  = "huesync_channel"
        const val NOTIF_ID    = 1
        const val ACTION_STOP = "com.huesync.tv.STOP"
        // Puerto del servidor JAR corriendo en el mismo TV
        private const val SERVER_PORT = 7070
        private const val SERVER_HOST = "127.0.0.1"
        var isRunning = false
            private set
    }

    private var config: Config? = null
    private var analyzer: ColorAnalyzer? = null
    private var bridge: HueBridge? = null
    private var running = false
    private var blackFrameCount = 0
    private var inDrmMode = false

    private var serverSocket: Socket? = null
    private var serverInput: DataInputStream? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "BarkLightScheduler").also { it.priority = Thread.MIN_PRIORITY }
    }
    private var scheduledTask: ScheduledFuture<*>? = null
    @Volatile private var capturing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // No necesitamos FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION — ya no grabamos pantalla
        startForeground(NOTIF_ID, buildNotif("BarkLight iniciando..."))
        Log.d(TAG, "onCreate — modo sin MediaProjection")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture(); stopSelf(); return START_NOT_STICKY
        }

        try {
            config   = Config.load(this)
            analyzer = ColorAnalyzer(config!!)
            bridge   = HueBridge(
                ip                   = config!!.bridgeIp,
                token                = config!!.bridgeToken,
                clientKey            = config!!.clientKey,
                entertainmentGroupId = config!!.entertainmentGroupId
            )

            isRunning = true
            running   = true

            bridge!!.startEntertainment { ok ->
                Handler(Looper.getMainLooper()).post {
                    updateNotif(if (ok) "Sincronizando (Entertainment API)" else "Sincronizando (HTTP)")
                }
            }

            // Conectar al servidor JAR en background
            Thread { connectToServer() }.also {
                it.name = "BarkLightConnect"; it.start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    // ── CONEXIÓN AL SERVIDOR JAR ──────────────────────────────────────────────

    private fun connectToServer() {
        var retries = 0
        while (running) {
            try {
                Log.d(TAG, "Conectando al servidor BarkLight en $SERVER_HOST:$SERVER_PORT")
                updateNotif("Conectando al servidor...")

                val socket = Socket(SERVER_HOST, SERVER_PORT)
                serverSocket = socket
                serverInput  = DataInputStream(socket.getInputStream())

                Log.d(TAG, "Conectado al servidor — iniciando captura")
                updateNotif("Capturando sin grabar pantalla")
                retries = 0

                startScheduler()

                // Bloquear hasta que se pierda la conexión
                while (running && !socket.isClosed) {
                    Thread.sleep(1000)
                }

                scheduledTask?.cancel(true)

            } catch (e: Exception) {
                if (!running) break
                retries++
                val waitSec = minOf(retries * 2L, 10L)
                Log.w(TAG, "Sin servidor ($e). Reintentando en ${waitSec}s...")
                updateNotif("Servidor no disponible. Reintentando...")
                updateNotif("Correr: adb shell CLASSPATH=/data/local/tmp/barklight-server.jar app_process / com.barklight.server.BarkLightServer")
                Thread.sleep(waitSec * 1000)
            }
        }
    }

    // ── SCHEDULER DE CAPTURA ─────────────────────────────────────────────────

    private fun startScheduler() {
        val intervalMs = 1000L / (config?.hueFps ?: 8)
        scheduledTask = scheduler.scheduleAtFixedRate({
            if (!running || capturing) return@scheduleAtFixedRate
            capturing = true
            try { requestFrame() }
            finally { capturing = false }
        }, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun requestFrame() {
        val socket = serverSocket ?: return
        if (socket.isClosed) return

        try {
            // Pedir frame al servidor
            socket.getOutputStream().write(1)
            socket.getOutputStream().flush()

            // Leer respuesta: [4 bytes tamaño][JPEG]
            val size = serverInput?.readInt() ?: return
            if (size <= 0) return

            val jpegBytes = ByteArray(size)
            serverInput?.readFully(jpegBytes) ?: return

            // Decodificar y procesar
            val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, size) ?: return
            processFrame(bmp)

        } catch (e: IOException) {
            Log.w(TAG, "Error leyendo frame: ${e.message}")
            try { serverSocket?.close() } catch (_: Exception) {}
            serverSocket = null
        }
    }

    // ── PROCESAMIENTO DE COLOR ────────────────────────────────────────────────

    private fun processFrame(frame: android.graphics.Bitmap) {
        val lightIds = config?.lights?.map { it.lightId } ?: return

        val isBlack = isFrameBlack(frame)
        if (isBlack) {
            blackFrameCount++
            if (blackFrameCount >= 30 && !inDrmMode) {
                inDrmMode = true
                bridge?.setDrmMode(true, lightIds)
                Handler(Looper.getMainLooper()).post { updateNotif("Contenido DRM - luz calida") }
            }
        } else {
            if (inDrmMode) {
                blackFrameCount = (blackFrameCount - 1).coerceAtLeast(0)
                if (blackFrameCount == 0) {
                    inDrmMode = false
                    bridge?.setDrmMode(false, lightIds)
                    Handler(Looper.getMainLooper()).post { updateNotif("Sincronizando pantalla") }
                }
            } else {
                blackFrameCount = 0
            }
        }

        if (!inDrmMode) {
            val colors = analyzer!!.analyze(frame)
            bridge?.setLights(colors)
        }
        frame.recycle()
    }

    private fun isFrameBlack(bmp: android.graphics.Bitmap): Boolean {
        val cx = bmp.width / 2; val cy = bmp.height / 2
        val sr = minOf(bmp.width, bmp.height) / 4
        if (sr < 5) return false
        val step = (sr / 5).coerceAtLeast(1)
        var total = 0L; var samples = 0
        for (x in cx - sr until cx + sr step step) {
            for (y in cy - sr until cy + sr step step) {
                if (x in 0 until bmp.width && y in 0 until bmp.height) {
                    val px = bmp.getPixel(x, y)
                    total += ((px shr 16) and 0xFF) + ((px shr 8) and 0xFF) + (px and 0xFF)
                    samples++
                }
            }
        }
        return samples > 0 && (total / samples / 3) < 8
    }

    // ── LIFECYCLE ─────────────────────────────────────────────────────────────

    private fun stopCapture() {
        running = false; isRunning = false
        scheduledTask?.cancel(true)
        scheduler.shutdown()
        try { serverSocket?.close() } catch (_: Exception) {}
        val cfg = config; val br = bridge
        if (cfg != null && br != null) {
            br.turnOffAll(cfg.lights.map { it.lightId })
            br.shutdown()
        }
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "BarkLight", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(this, 0,
            Intent(this, CaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BarkLight")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Detener", stopPi)
            .setOngoing(true).setSilent(true).build()
    }

    fun updateNotif(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotif(text))
    }
}
