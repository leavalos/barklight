package com.huesync.tv.hue

import android.util.Log
import com.huesync.tv.analyzer.ZoneColor
import org.bouncycastle.tls.*
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente para la Hue Entertainment API.
 *
 * Usa DTLS 1.2 con PSK (TLS_PSK_WITH_AES_128_GCM_SHA256) sobre UDP puerto 2100.
 * Permite enviar colores a hasta 10 luces por paquete a ~25 fps.
 */
class EntertainmentClient(
    private val ip: String,
    private val token: String,
    private val clientKey: String,   // HEX PSK
    private val groupId: String
) {
    companion object {
        private const val TAG = "EntertainmentClient"
        private const val UDP_PORT = 2100
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    private val active = AtomicBoolean(false)
    private var dtlsTransport: DTLSTransport? = null
    private var socket: DatagramSocket? = null

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    fun connect(): Boolean {
        return try {
            activateGroupRest(true)
            Thread.sleep(200)

            val addr = InetAddress.getByName(ip)
            val sock = DatagramSocket().also {
                it.soTimeout = SOCKET_TIMEOUT_MS
                it.connect(addr, UDP_PORT)
            }
            socket = sock

            val pskBytes = hexToBytes(clientKey)
            val crypto = BcTlsCrypto(SecureRandom())
            val client = HueDtlsClient(crypto, token, pskBytes)
            val transport = UDPTransport(sock, 1500)

            val protocol = DTLSClientProtocol()
            dtlsTransport = protocol.connect(client, transport)

            sock.soTimeout = 0
            active.set(true)
            Log.i(TAG, "Entertainment DTLS conectado al grupo $groupId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando Entertainment API: ${e.message}", e)
            cleanup()
            false
        }
    }

    fun sendColors(colors: Map<String, ZoneColor>) {
        if (!active.get()) return
        val dt = dtlsTransport ?: return
        try {
            val packet = buildPacket(colors)
            dt.send(packet, 0, packet.size)
        } catch (e: Exception) {
            Log.w(TAG, "Error enviando paquete Entertainment: ${e.message}")
        }
    }

    fun disconnect() {
        active.set(false)
        try { activateGroupRest(false) } catch (_: Exception) {}
        cleanup()
        Log.i(TAG, "Entertainment desconectado")
    }

    val isConnected: Boolean get() = active.get()

    // ── Formato de paquete Hue Entertainment ──────────────────────────────────
    // Header (16 bytes): "HueStream" | version 1.0 | seq | 0x0000 | colorspace RGB | 0x00
    // Por luz (9 bytes): type(2) | channel(2) | R(2) | G(2) | B(2)  — valores 16-bit

    private var sequence: Byte = 0

    private fun buildPacket(colors: Map<String, ZoneColor>): ByteArray {
        val numLights = colors.size.coerceAtMost(10)
        val buf = ByteBuffer.allocate(16 + numLights * 9)
        buf.put("HueStream".toByteArray(Charsets.US_ASCII))
        buf.put(0x01); buf.put(0x00)       // version 1.0
        buf.put(sequence++)                 // sequence
        buf.put(0x00); buf.put(0x00)        // reserved
        buf.put(0x00)                        // RGB color space
        buf.put(0x00)                        // reserved
        colors.entries.take(10).forEachIndexed { channel, (_, color) ->
            buf.put(0x00); buf.put(0x00)    // type: light
            buf.put(0x00); buf.put(channel.toByte())
            buf.putShort(((color.r shl 8) or color.r).toShort())
            buf.putShort(((color.g shl 8) or color.g).toShort())
            buf.putShort(((color.b shl 8) or color.b).toShort())
        }
        return buf.array()
    }

    // ── REST helpers ──────────────────────────────────────────────────────────

    private fun activateGroupRest(enable: Boolean) {
        val conn = java.net.URL("http://$ip/api/$token/groups/$groupId/action")
            .openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000; conn.readTimeout = 3000
        conn.requestMethod = "PUT"; conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            java.io.OutputStreamWriter(conn.outputStream).use {
                it.write(if (enable) """{"stream":{"active":true}}"""
                         else        """{"stream":{"active":false}}""")
            }
            conn.inputStream.bufferedReader().readText()
            Log.i(TAG, "Grupo $groupId stream active=$enable")
        } finally { conn.disconnect() }
    }

    private fun cleanup() {
        try { dtlsTransport?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        dtlsTransport = null; socket = null
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        return ByteArray(len / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) +
             Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}

// ── BouncyCastle DTLS PSK (API nueva: org.bouncycastle.tls) ──────────────────

private class HueDtlsClient(
    crypto: BcTlsCrypto,
    private val identity: String,
    private val psk: ByteArray
) : PSKTlsClient(crypto, identity.toByteArray(Charsets.UTF_8), psk) {

    override fun getSupportedCipherSuites(): IntArray =
        intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)

    override fun getAuthentication(): TlsAuthentication =
        object : ServerOnlyTlsAuthentication() {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) { }
        }

    override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) {
        Log.w("HueDtlsClient", "DTLS Alert level=$alertLevel desc=$alertDescription msg=$message")
    }
}

private class UDPTransport(
    private val socket: DatagramSocket,
    private val mtu: Int
) : DatagramTransport {

    override fun getReceiveLimit(): Int = mtu - 28
    override fun getSendLimit(): Int = mtu - 28

    @Throws(IOException::class)
    override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        socket.soTimeout = waitMillis.coerceAtLeast(1)
        return try {
            val dp = DatagramPacket(buf, off, len)
            socket.receive(dp)
            dp.length
        } catch (_: SocketTimeoutException) { -1 }
    }

    @Throws(IOException::class)
    override fun send(buf: ByteArray, off: Int, len: Int) {
        socket.send(DatagramPacket(buf, off, len, socket.remoteSocketAddress))
    }

    @Throws(IOException::class)
    override fun close() { }
}
