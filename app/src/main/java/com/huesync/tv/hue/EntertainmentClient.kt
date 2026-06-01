package com.huesync.tv.hue

import android.util.Log
import com.huesync.tv.analyzer.ZoneColor
import org.bouncycastle.crypto.tls.*
import org.bouncycastle.util.encoders.Hex
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente para la Hue Entertainment API.
 *
 * Usa DTLS 1.2 con PSK (TLS_PSK_WITH_AES_128_GCM_SHA256) sobre UDP puerto 2100.
 * Permite enviar colores a hasta 10 luces por paquete a ~25 fps, con latencia
 * mucho menor que la API REST HTTP clásica (~6-10ms vs ~80-150ms por petición).
 *
 * Protocolo:
 *   1. Activar el grupo de entretenimiento vía REST (PUT /api/<token>/groups/<id>)
 *   2. Establecer sesión DTLS con:
 *        - identity = username (bridgeToken)
 *        - psk      = clientKey en bytes hex
 *   3. Enviar paquetes UDP con el formato binario de Hue Entertainment
 *   4. Al terminar, desactivar el grupo vía REST
 */
class EntertainmentClient(
    private val ip: String,
    private val token: String,       // username / bridgeToken
    private val clientKey: String,   // clientkey HEX (PSK)
    private val groupId: String
) {
    companion object {
        private const val TAG = "EntertainmentClient"
        private const val UDP_PORT = 2100
        private const val TIMEOUT_CONNECT_MS = 5000
    }

    private val active = AtomicBoolean(false)
    private var transport: UDPTransport? = null
    private var dtlsClient: HueDtlsClient? = null
    private var socket: DatagramSocket? = null

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    /**
     * Conecta: activa el grupo REST + handshake DTLS.
     * Llamar en un hilo de background.
     * @return true si la conexión se estableció correctamente.
     */
    fun connect(): Boolean {
        return try {
            activateGroupRest(true)
            Thread.sleep(200) // dar tiempo al bridge para activar el grupo

            val addr = InetAddress.getByName(ip)
            socket = DatagramSocket().also {
                it.soTimeout = TIMEOUT_CONNECT_MS
                it.connect(addr, UDP_PORT)
            }

            val pskBytes = Hex.decode(clientKey)
            dtlsClient = HueDtlsClient(token, pskBytes)
            transport = UDPTransport(socket!!, 1500)

            val protocol = DTLSClientProtocol(java.security.SecureRandom())
            dtlsClient!!.dtlsTransport = protocol.connect(dtlsClient!!, transport!!)

            // Quitar timeout para streaming continuo
            socket!!.soTimeout = 0
            active.set(true)
            Log.i(TAG, "Entertainment DTLS conectado al grupo $groupId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando Entertainment API: ${e.message}", e)
            cleanup()
            false
        }
    }

    /**
     * Envía los colores de las luces en un paquete DTLS UDP.
     * Thread-safe; no bloquea (dispara y olvida).
     * @param colors map de lightId -> ZoneColor
     */
    fun sendColors(colors: Map<String, ZoneColor>) {
        if (!active.get()) return
        val dt = dtlsClient?.dtlsTransport ?: return
        try {
            val packet = buildPacket(colors)
            dt.send(packet, 0, packet.size)
        } catch (e: Exception) {
            Log.w(TAG, "Error enviando paquete Entertainment: ${e.message}")
            // No desconectar aquí — el loop principal decide
        }
    }

    /**
     * Desconecta limpiamente: desactiva el grupo REST + cierra DTLS.
     */
    fun disconnect() {
        active.set(false)
        try { activateGroupRest(false) } catch (_: Exception) {}
        cleanup()
        Log.i(TAG, "Entertainment desconectado")
    }

    val isConnected: Boolean get() = active.get()

    // ── Formato de paquete Hue Entertainment ──────────────────────────────────
    //
    // Header fijo (16 bytes):
    //   "HueStream" (9 bytes) | version (2 bytes: 0x01 0x00) |
    //   sequence (1 byte) | 0x00 0x00 (2 bytes reservados) |
    //   color space (1 byte: 0x00=RGB, 0x01=XY) | 0x00 (1 byte reservado)
    //
    // Por cada luz (9 bytes):
    //   type (2 bytes: 0x00 0x00 = luz) | channel (2 bytes) |
    //   R (2 bytes big-endian) | G (2 bytes big-endian) | B (2 bytes big-endian)
    //
    // Los valores de color son 16-bit (0–65535), escalamos desde 8-bit.

    private var sequence: Byte = 0

    private fun buildPacket(colors: Map<String, ZoneColor>): ByteArray {
        val numLights = colors.size.coerceAtMost(10)
        val buf = ByteBuffer.allocate(16 + numLights * 9)

        // Header
        buf.put("HueStream".toByteArray(Charsets.US_ASCII))   // 9 bytes
        buf.put(0x01); buf.put(0x00)                           // version 1.0
        buf.put(sequence++)                                     // sequence
        buf.put(0x00); buf.put(0x00)                           // reserved
        buf.put(0x00)                                           // RGB color space
        buf.put(0x00)                                           // reserved

        // Datos de luces (canal = índice 0-based)
        colors.entries.take(10).forEachIndexed { channel, (_, color) ->
            buf.put(0x00); buf.put(0x00)                        // type: light
            buf.put(0x00); buf.put(channel.toByte())            // channel id
            // Escalar 8-bit → 16-bit (repetir byte: 0xFF → 0xFFFF)
            val r16 = (color.r shl 8) or color.r
            val g16 = (color.g shl 8) or color.g
            val b16 = (color.b shl 8) or color.b
            buf.putShort(r16.toShort())
            buf.putShort(g16.toShort())
            buf.putShort(b16.toShort())
        }
        return buf.array()
    }

    // ── REST helpers ──────────────────────────────────────────────────────────

    private fun activateGroupRest(active: Boolean) {
        val url = java.net.URL("http://$ip/api/$token/groups/$groupId/action")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            val body = if (active) """{"stream":{"active":true}}"""
                       else        """{"stream":{"active":false}}"""
            java.io.OutputStreamWriter(conn.outputStream).use { it.write(body) }
            conn.inputStream.bufferedReader().readText()
            Log.i(TAG, "Grupo $groupId stream active=$active")
        } finally {
            conn.disconnect()
        }
    }

    private fun cleanup() {
        try { dtlsClient?.dtlsTransport?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        transport = null
        dtlsClient = null
        socket = null
    }
}

// ── BouncyCastle DTLS PSK helpers ────────────────────────────────────────────

/**
 * Cliente DTLS que usa PSK con identity = bridgeToken y key = clientKey bytes.
 * Fuerza el cipher suite TLS_PSK_WITH_AES_128_GCM_SHA256 (único que acepta el Bridge).
 */
private class HueDtlsClient(
    private val identity: String,
    private val psk: ByteArray
) : PSKTlsClient() {

    var dtlsTransport: DTLSTransport? = null

    override fun getPSKIdentity(): ByteArray = identity.toByteArray(Charsets.UTF_8)
    override fun getPSK(): ByteArray = psk

    override fun getCipherSuites(): IntArray = intArrayOf(
        CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256
    )

    override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
        override fun getClientCredentials(cr: CertificateRequest?): TlsCredentials? = null
        override fun notifyServerCertificate(cert: Certificate?) { /* aceptar todo */ }
    }

    override fun notifyAlertRaised(level: Short, description: Short, msg: String?, cause: Throwable?) {
        Log.w("HueDtlsClient", "Alert level=$level desc=$description msg=$msg")
    }
}

/**
 * Adaptador UDP DatagramSocket → TlsTransport de BouncyCastle.
 */
private class UDPTransport(
    private val socket: DatagramSocket,
    private val mtu: Int
) : DatagramTransport {

    override fun getReceiveLimit(): Int = mtu - 28  // IP+UDP overhead
    override fun getSendLimit(): Int = mtu - 28

    override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
        socket.soTimeout = waitMillis.coerceAtLeast(1)
        return try {
            val dp = DatagramPacket(buf, off, len)
            socket.receive(dp)
            dp.length
        } catch (_: SocketTimeoutException) {
            -1
        }
    }

    override fun send(buf: ByteArray, off: Int, len: Int) {
        val dp = DatagramPacket(buf, off, len, socket.remoteSocketAddress)
        socket.send(dp)
    }

    @Throws(IOException::class)
    override fun close() { /* el socket lo cierra EntertainmentClient */ }
}
