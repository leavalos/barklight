package com.huesync.tv.capture

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.huesync.tv.MainActivity
import com.huesync.tv.R
import com.huesync.tv.analyzer.ColorAnalyzer
import com.huesync.tv.hue.HueBridge
import com.huesync.tv.model.Config

class UsbCaptureService : Service() {

    companion object {
        private const val TAG = "UsbCaptureService"
        const val ACTION_STOP = "com.huesync.tv.USB_STOP"
        var isRunning = false
            private set
        private val KNOWN_DEVICES = listOf(0x534D to 0x2109, 0x1B3F to 0x2247, 0x0c45 to 0x636b)
    }

    private var usbConnection: UsbDeviceConnection? = null
    private var captureThread: Thread? = null
    private var running = false
    private lateinit var config: Config
    private lateinit var analyzer: ColorAnalyzer
    private lateinit var bridge: HueBridge

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CaptureService.CHANNEL_ID, "HueSync TV", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        config = Config.load(this); analyzer = ColorAnalyzer(config); bridge = HueBridge(config.bridgeIp, config.bridgeToken)
        startForeground(CaptureService.NOTIF_ID, buildNotif("Buscando capturadora USB..."))
        isRunning = true
        val mgr = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findDevice(mgr) ?: run { updateNotif("No se encontro capturadora UVC"); stopSelf(); return START_NOT_STICKY }
        usbConnection = mgr.openDevice(device) ?: run { updateNotif("Sin permiso USB"); stopSelf(); return START_NOT_STICKY }
        updateNotif("Capturando desde: ${device.productName ?: "capturadora USB"}")
        startLoop(device, usbConnection!!)
        return START_STICKY
    }

    private fun findDevice(mgr: UsbManager): UsbDevice? {
        val devs = mgr.deviceList.values
        devs.firstOrNull { d -> KNOWN_DEVICES.any { it.first == d.vendorId && it.second == d.productId } }?.let { return it }
        return devs.firstOrNull { d -> (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO } }
    }

    private fun startLoop(device: UsbDevice, conn: UsbDeviceConnection) {
        running = true
        val hueMs = 1000L / config.hueFps
        captureThread = Thread {
            val iface = findVideoIface(device) ?: return@Thread
            val ep    = findBulkIn(iface) ?: findIsoIn(iface) ?: return@Thread
            conn.claimInterface(iface, true)
            val buf = ByteArray(ep.maxPacketSize * 32)
            val acc = mutableListOf<Byte>()
            var inFrame = false; var lastSend = 0L
            while (running) {
                val n = conn.bulkTransfer(ep, buf, buf.size, 100)
                if (n <= 0) continue
                for (i in 0 until n) {
                    val b = buf[i]
                    if (!inFrame && i < n - 1 && b == 0xFF.toByte() && buf[i+1] == 0xD8.toByte()) { inFrame = true; acc.clear() }
                    if (inFrame) {
                        acc.add(b)
                        if (acc.size >= 2 && acc[acc.size-2] == 0xFF.toByte() && acc[acc.size-1] == 0xD9.toByte()) {
                            inFrame = false
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastSend >= hueMs) { processJpeg(acc.toByteArray()); lastSend = now }
                        }
                    }
                }
            }
            conn.releaseInterface(iface)
        }.also { it.name = "HueSyncUSB"; it.start() }
    }

    private fun processJpeg(bytes: ByteArray) {
        try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            bridge.setLights(analyzer.analyze(bmp)); bmp.recycle()
        } catch (e: Exception) { Log.w(TAG, "Error frame: ${e.message}") }
    }

    private fun findVideoIface(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) { val f = d.getInterface(i); if (f.interfaceClass == UsbConstants.USB_CLASS_VIDEO && f.interfaceSubclass == 2) return f }
        for (i in 0 until d.interfaceCount) { val f = d.getInterface(i); if (findBulkIn(f) != null || findIsoIn(f) != null) return f }
        return null
    }

    private fun findBulkIn(f: UsbInterface) = (0 until f.endpointCount).map { f.getEndpoint(it) }.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
    private fun findIsoIn(f: UsbInterface)  = (0 until f.endpointCount).map { f.getEndpoint(it) }.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && it.direction == UsbConstants.USB_DIR_IN }

    private fun stopCapture() {
        running = false; isRunning = false; captureThread?.interrupt()
        if (::bridge.isInitialized && ::config.isInitialized) { bridge.turnOffAll(config.lights.map { it.lightId }); bridge.shutdown() }
        usbConnection?.close()
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null

    private fun buildNotif(text: String): Notification {
        val stopPi = PendingIntent.getService(this, 1, Intent(this, UsbCaptureService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openPi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CaptureService.CHANNEL_ID)
            .setContentTitle("HueSync TV - USB").setContentText(text)
            .setSmallIcon(R.drawable.ic_sync).setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Detener", stopPi)
            .setOngoing(true).setSilent(true).build()
    }

    private fun updateNotif(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(CaptureService.NOTIF_ID, buildNotif(text))
    }
}
