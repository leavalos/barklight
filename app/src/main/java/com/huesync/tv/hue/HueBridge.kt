package com.huesync.tv.hue

import android.util.Log
import com.huesync.tv.analyzer.ZoneColor
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstracción del Philips Hue Bridge.
 *
 * Soporta dos modos de envío de color:
 *  - Entertainment API (DTLS/UDP): hasta 25 fps, ~6-10ms latencia.
 *    Requiere clientKey y entertainmentGroupId.
 *  - REST HTTP clásico: fallback cuando no hay Entertainment configurado.
 *
 * El modo se selecciona automáticamente al llamar a [startEntertainment].
 * Si falla, [setLights] usa HTTP como respaldo.
 */
class HueBridge(
    private val ip: String,
    private val token: String,
    private val clientKey: String = "",
    private val entertainmentGroupId: String = ""
) {

    companion object {
        private const val TAG = "HueBridge"
        private const val TIMEOUT_MS = 3000
        private const val WARM_CT = 366  // ~2700K

        /**
         * Solicita pairing al Bridge.
         * Pide SIEMPRE generateclientkey:true para obtener el clientKey
         * necesario para la Entertainment API.
         *
         * Devuelve Pair(token, clientKey) o (null, errorMsg).
         */
        fun requestToken(
            ip: String,
            onResult: (token: String?, clientKey: String?, error: String?) -> Unit
        ) {
            Thread {
                try {
                    val conn = URL("http://$ip/api").openConnection() as HttpURLConnection
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    try {
                        OutputStreamWriter(conn.outputStream).use {
                            it.write(
                                JSONObject().apply {
                                    put("devicetype", "huesync_tv#android")
                                    put("generateclientkey", true)  // ← Entertainment API
                                }.toString()
                            )
                        }
                        val response = conn.inputStream.bufferedReader().readText()
                        val first = JSONArray(response).getJSONObject(0)
                        when {
                            first.has("success") -> {
                                val success = first.getJSONObject("success")
                                val tok = success.getString("username")
                                val key = success.optString("clientkey", "")
                                Log.i(TAG, "Pairing OK — token=$tok clientkey=${key.take(8)}...")
                                onResult(tok, key.ifEmpty { null }, null)
                            }
                            first.has("error") -> {
                                val errType = first.getJSONObject("error").getInt("type")
                                val msg = if (errType == 101) "Presiona el boton del Bridge primero"
                                          else first.getJSONObject("error").getString("description")
                                Log.w(TAG, "Error pairing: $msg")
                                onResult(null, null, msg)
                            }
                            else -> onResult(null, null, "Respuesta inesperada del Bridge")
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error conectando al Bridge: ${e.message}")
                    onResult(null, null, "No se pudo conectar a $ip")
                }
            }.also { it.name = "HuePairing" }.start()
        }

        /**
         * Obtiene los grupos de entretenimiento disponibles en el Bridge.
         * Devuelve lista de Pair(id, name).
         */
        fun getEntertainmentGroups(
            ip: String,
            token: String,
            onResult: (List<Pair<String, String>>, String?) -> Unit
        ) {
            Thread {
                try {
                    val response = httpGet("http://$ip/api/$token/groups")
                    if (response.trimStart().startsWith("[")) {
                        onResult(emptyList(), "Token inválido")
                        return@Thread
                    }
                    val json = JSONObject(response)
                    val groups = mutableListOf<Pair<String, String>>()
                    json.keys().forEach { id ->
                        val g = json.getJSONObject(id)
                        if (g.optString("type") == "Entertainment") {
                            groups.add(Pair(id, g.optString("name", "Grupo $id")))
                        }
                    }
                    Log.i(TAG, "Grupos Entertainment encontrados: $groups")
                    onResult(groups, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Error obteniendo grupos: ${e.message}")
                    onResult(emptyList(), e.message)
                }
            }.also { it.name = "HueGroups" }.start()
        }

        private fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            return try { conn.inputStream.bufferedReader().readText() }
            finally { conn.disconnect() }
        }
    }

    // ── Estado interno ────────────────────────────────────────────────────────

    private val executor = Executors.newSingleThreadExecutor()
    private val baseUrl get() = "http://$ip/api/$token"
    private val drmMode = AtomicBoolean(false)

    /** Cliente Entertainment API — null si no está configurado o falló */
    @Volatile private var entClient: EntertainmentClient? = null
    @Volatile private var usingEntertainment = false

    // ── Entertainment API ─────────────────────────────────────────────────────

    /**
     * Inicia la sesión Entertainment API en background.
     * Llama [onReady](true) si se conectó, [onReady](false) si falló (seguirá con HTTP).
     */
    fun startEntertainment(onReady: (Boolean) -> Unit = {}) {
        if (clientKey.isEmpty() || entertainmentGroupId.isEmpty()) {
            Log.w(TAG, "Entertainment no configurado — usando HTTP")
            onReady(false)
            return
        }
        Thread {
            val client = EntertainmentClient(ip, token, clientKey, entertainmentGroupId)
            val ok = client.connect()
            if (ok) {
                entClient = client
                usingEntertainment = true
                Log.i(TAG, "Entertainment API activa (DTLS/UDP)")
            } else {
                Log.w(TAG, "Entertainment API falló — fallback a HTTP")
            }
            onReady(ok)
        }.also { it.name = "EntConnect" }.start()
    }

    fun stopEntertainment() {
        val c = entClient
        entClient = null
        usingEntertainment = false
        Thread { c?.disconnect() }.also { it.name = "EntDisconnect" }.start()
    }

    fun isUsingEntertainment(): Boolean = usingEntertainment && (entClient?.isConnected == true)

    // ── Verificación ──────────────────────────────────────────────────────────

    fun verifyConnection(onResult: (ok: Boolean, lights: List<String>) -> Unit) {
        executor.submit {
            try {
                val response = httpGet("$baseUrl/lights")
                if (response.trimStart().startsWith("[")) {
                    Log.e(TAG, "Bridge rechazó el token")
                    onResult(false, emptyList())
                } else {
                    val json = JSONObject(response)
                    val ids = mutableListOf<String>()
                    json.keys().forEach { ids.add(it) }
                    Log.i(TAG, "Bridge OK — luces: $ids")
                    onResult(true, ids)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verificando Bridge: ${e.message}")
                onResult(false, emptyList())
            }
        }
    }

    // ── Envío de colores ──────────────────────────────────────────────────────

    fun setLights(colors: Map<String, ZoneColor>) {
        if (drmMode.get()) return

        val ec = entClient
        if (ec != null && ec.isConnected) {
            // PATH RÁPIDO: Entertainment API UDP (~6-10ms)
            ec.sendColors(colors)
        } else {
            // FALLBACK: REST HTTP individual por luz (~80-150ms)
            colors.forEach { (id, color) ->
                executor.submit {
                    try {
                        val payload = JSONObject().apply {
                            put("on", true)
                            put("xy", org.json.JSONArray().apply {
                                put(color.x.toDouble())
                                put(color.y.toDouble())
                            })
                            put("bri", color.bri)
                            put("transitiontime", 1)
                        }
                        httpPut("$baseUrl/lights/$id/state", payload.toString())
                    } catch (e: Exception) { Log.w(TAG, "Error luz $id: ${e.message}") }
                }
            }
        }
    }

    // ── DRM / modos especiales ────────────────────────────────────────────────

    fun setDrmMode(enabled: Boolean, lightIds: List<String>) {
        if (drmMode.getAndSet(enabled) == enabled) return
        executor.submit {
            if (enabled) {
                Log.i(TAG, "DRM detectado — activando luz cálida")
                lightIds.forEach { id ->
                    try {
                        val payload = JSONObject().apply {
                            put("on", true); put("ct", WARM_CT)
                            put("bri", 254); put("transitiontime", 4)
                        }
                        httpPut("$baseUrl/lights/$id/state", payload.toString())
                    } catch (e: Exception) { Log.w(TAG, "Error luz $id: ${e.message}") }
                }
            }
        }
    }

    fun turnOffAll(lightIds: List<String>) {
        executor.submit {
            lightIds.forEach { id ->
                try {
                    httpPut(
                        "$baseUrl/lights/$id/state",
                        JSONObject().apply { put("on", false) }.toString()
                    )
                } catch (_: Exception) {}
            }
        }
    }

    fun shutdown() {
        stopEntertainment()
        executor.shutdown()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        return try { conn.inputStream.bufferedReader().readText() }
        finally { conn.disconnect() }
    }

    private fun httpPut(url: String, body: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }
}
