package com.huesync.tv.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.huesync.tv.MainActivity
import com.huesync.tv.R
import com.huesync.tv.analyzer.ColorAnalyzer
import com.huesync.tv.hue.HueBridge
import com.huesync.tv.model.Config
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val CHANNEL_ID  = "huesync_channel"
        const val NOTIF_ID    = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.huesync.tv.STOP"
        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var config: Config? = null
    private var analyzer: ColorAnalyzer? = null
    private var bridge: HueBridge? = null
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0
    private var blackFrameCount = 0
    private var inDrmMode = false
    private var running = false

    // Scheduler de baja prioridad — dispara la captura N veces por segundo
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HueSyncScheduler").also {
            it.priority = Thread.MIN_PRIORITY  // mínima prioridad del sistema
        }
    }
    private var scheduledTask: ScheduledFuture<*>? = null

    // Flag para evitar capturas superpuestas
    @Volatile private var capturing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif("HueSync TV activo"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotif("HueSync TV activo"))
        }
        Log.d(TAG, "onCreate")
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

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
            @Suppress("DEPRECATION")
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)

            if (resultCode == 0 || resultData == null) {
                Log.e(TAG, "Sin datos de proyeccion"); stopSelf(); return START_NOT_STICKY
            }

            isRunning = true
            running   = true
            initScreen()
            initProjection(resultCode, resultData)

            bridge!!.startEntertainment { ok ->
                Handler(Looper.getMainLooper()).post {
                    updateNotif(if (ok) "Sincronizando (Entertainment API)" else "Sincronizando (HTTP)")
                }
            }

            startScheduler()
            Log.d(TAG, "Servicio iniciado a ${config!!.hueFps}fps")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando: ${e.message}", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun initScreen() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
            screenDpi = resources.displayMetrics.densityDpi
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
            screenW = m.widthPixels; screenH = m.heightPixels; screenDpi = m.densityDpi
        }
        if (screenW > 120) {
            val s = 120f / screenW
            screenW = 120; screenH = (screenH * s).toInt()
        }
        Log.d(TAG, "Resolucion captura: ${screenW}x${screenH}")
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture(); stopSelf() }
            }, Handler(Looper.getMainLooper()))
        }

        // maxImages=1: solo un frame en memoria a la vez
        // No conectamos al VirtualDisplay todavía — lo haremos on-demand
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 1)

        // CLAVE: FLAG_AUTO_MIRROR pero SIN surface activa al inicio
        // El VirtualDisplay existe pero no renderiza nada hasta que conectemos la surface
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "BarkLightCapture", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null,  // surface null = VirtualDisplay inactivo, NO consume GPU
            null, null
        )
        Log.d(TAG, "VirtualDisplay creado (inactivo)")
    }

    /**
     * Scheduler de captura on-demand.
     *
     * En vez de tener el VirtualDisplay renderizando continuamente,
     * lo activamos solo el tiempo necesario para capturar un frame,
     * luego lo desactivamos inmediatamente.
     *
     * Esto libera la GPU entre capturas, permitiendo que el decoder
     * de video funcione sin competencia.
     */
    private fun startScheduler() {
        val intervalMs = 1000L / (config?.hueFps ?: 8)

        scheduledTask = scheduler.scheduleAtFixedRate({
            if (!running || capturing) return@scheduleAtFixedRate

            capturing = true
            try {
                captureOnce()
            } finally {
                capturing = false
            }
        }, 500, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun captureOnce() {
        val reader = imageReader ?: return
        val vd     = virtualDisplay ?: return

        // 1. Conectar surface al VirtualDisplay — Android empieza a renderizar
        vd.surface = reader.surface

        // 2. Pequeño sleep para dar tiempo a que llegue el primer frame
        //    125ms es el tiempo de un frame a 8fps — suficiente
        Thread.sleep(80)

        // 3. Leer el frame disponible
        val image = reader.acquireLatestImage()

        // 4. INMEDIATAMENTE desconectar — el VirtualDisplay deja de renderizar
        vd.surface = null

        if (image == null) return

        try {
            val plane  = image.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * screenW
            val bmp = Bitmap.createBitmap(
                screenW + rowPad / plane.pixelStride,
                screenH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            image.close()

            val frame = if (rowPad > 0)
                Bitmap.createBitmap(bmp, 0, 0, screenW, screenH).also { bmp.recycle() }
            else bmp

            processFrame(frame)
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando frame: ${e.message}")
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun processFrame(frame: Bitmap) {
        val lightIds = config?.lights?.map { it.lightId } ?: return

        val isBlack = isFrameBlack(frame)
        if (isBlack) {
            blackFrameCount++
            if (blackFrameCount >= 30 && !inDrmMode) {
                inDrmMode = true
                bridge?.setDrmMode(true, lightIds)
                Handler(Looper.getMainLooper()).post {
                    updateNotif("Contenido DRM - luz calida")
                }
            }
        } else {
            if (inDrmMode) {
                blackFrameCount = (blackFrameCount - 1).coerceAtLeast(0)
                if (blackFrameCount == 0) {
                    inDrmMode = false
                    bridge?.setDrmMode(false, lightIds)
                    Handler(Looper.getMainLooper()).post {
                        updateNotif("Sincronizando pantalla")
                    }
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

    private fun isFrameBlack(bmp: Bitmap): Boolean {
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

    private fun stopCapture() {
        running = false; isRunning = false
        scheduledTask?.cancel(true)
        scheduler.shutdown()
        // Desconectar surface antes de liberar
        virtualDisplay?.surface = null
        val cfg = config; val br = bridge
        if (cfg != null && br != null) {
            br.turnOffAll(cfg.lights.map { it.lightId })
            br.shutdown()
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null; imageReader = null; mediaProjection = null
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "HueSync TV", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
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
