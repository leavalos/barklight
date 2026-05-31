package com.huesync.tv.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.pm.ServiceInfo
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
    private var captureThread: Thread? = null
    private var running = false
    private var config: Config? = null
    private var analyzer: ColorAnalyzer? = null
    private var bridge: HueBridge? = null
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0
    private var blackFrameCount = 0
    private var inDrmMode = false

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "onCreate llamado")
        createChannel()
        // startForeground inmediatamente en onCreate con tipo mediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif("HueSync TV activo"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotif("HueSync TV activo"))
        }
        Log.e(TAG, "startForeground llamado en onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "onStartCommand llamado action=${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            Log.e(TAG, "Deteniendo servicio")
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            config   = Config.load(this)
            analyzer = ColorAnalyzer(config!!)
            bridge   = HueBridge(config!!.bridgeIp, config!!.bridgeToken)

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
            @Suppress("DEPRECATION")
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)

            Log.e(TAG, "resultCode=$resultCode resultData=$resultData")

            // RESULT_OK = -1 en Android, RESULT_CANCELED = 0
            if (resultCode == 0 || resultData == null) {
                Log.e(TAG, "Sin datos de proyeccion validos resultCode=$resultCode")
                stopSelf()
                return START_NOT_STICKY
            }

            isRunning = true
            updateNotif("Capturando pantalla...")
            initScreen()
            initProjection(resultCode, resultData)
            startLoop()
            Log.e(TAG, "Loop de captura iniciado")
        } catch (e: Exception) {
            Log.e(TAG, "Error en onStartCommand: ${e.message}", e)
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
        if (screenW > 480) {
            val s = 480f / screenW
            screenW = 480; screenH = (screenH * s).toInt()
        }
        Log.e(TAG, "Resolucion: ${screenW}x${screenH}")
    }

    private fun initProjection(resultCode: Int, data: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HueSyncCapture", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.e(TAG, "VirtualDisplay creado")
    }

    private fun startLoop() {
        running = true
        val cfg = config!!
        val frameMs = 1000L / cfg.targetFps
        val hueMs   = 1000L / cfg.hueFps
        val lightIds = cfg.lights.map { it.lightId }

        captureThread = Thread {
            var lastHueSend = 0L
            Log.e(TAG, "Loop iniciado frameMs=$frameMs hueMs=$hueMs")

            while (running) {
                val t0 = SystemClock.elapsedRealtime()
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
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

                        val isBlack = isFrameBlack(frame)
                        if (isBlack) {
                            blackFrameCount++
                            if (blackFrameCount >= 30 && !inDrmMode) {
                                inDrmMode = true
                                bridge?.setDrmMode(true, lightIds)
                                updateNotif("Contenido DRM - luz calida")
                            }
                        } else {
                            if (inDrmMode) {
                                blackFrameCount = (blackFrameCount - 1).coerceAtLeast(0)
                                if (blackFrameCount == 0) {
                                    inDrmMode = false
                                    bridge?.setDrmMode(false, lightIds)
                                    updateNotif("Sincronizando pantalla")
                                }
                            } else {
                                blackFrameCount = 0
                            }
                        }

                        val now = SystemClock.elapsedRealtime()
                        if (!inDrmMode && now - lastHueSend >= hueMs) {
                            val colors = analyzer!!.analyze(frame)
                            Log.d(TAG, "Enviando colores a ${colors.size} luces")
                            bridge?.setLights(colors)
                            lastHueSend = now
                        }
                        frame.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error procesando frame: ${e.message}")
                        image.close()
                    }
                }
                val sleep = frameMs - (SystemClock.elapsedRealtime() - t0)
                if (sleep > 0) Thread.sleep(sleep)
            }
            Log.e(TAG, "Loop terminado")
        }.also { it.name = "HueSyncLoop"; it.start() }
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
        captureThread?.interrupt()
        val cfg = config
        val br  = bridge
        if (cfg != null && br != null) {
            br.turnOffAll(cfg.lights.map { it.lightId })
            br.shutdown()
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null; imageReader = null; mediaProjection = null
    }

    override fun onDestroy() {
        Log.e(TAG, "onDestroy")
        stopCapture()
        super.onDestroy()
    }

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
            .setContentTitle("HueSync TV")
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
