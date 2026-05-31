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

class HueBridge(private val ip: String, private val token: String) {

    companion object {
        private const val TAG = "HueBridge"
        private const val TIMEOUT_MS = 3000
        private const val WARM_CT = 366  // ~2700K

        fun requestToken(ip: String, onResult: (String?, String?) -> Unit) {
            Thread {
                try {
                    val conn = URL("http://$ip/api").openConnection() as HttpURLConnection
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout    = TIMEOUT_MS
                    conn.requestMethod  = "POST"
                    conn.doOutput       = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    try {
                        OutputStreamWriter(conn.outputStream).use {
                            it.write(JSONObject().apply { put("devicetype", "huesync_tv#android") }.toString())
                        }
                        val response = conn.inputStream.bufferedReader().readText()
                        val first = JSONArray(response).getJSONObject(0)
                        when {
                            first.has("success") -> {
                                val tok = first.getJSONObject("success").getString("username")
                                Log.i(TAG, "Token obtenido: $tok")
                                onResult(tok, null)
                            }
                            first.has("error") -> {
                                val errType = first.getJSONObject("error").getInt("type")
                                val msg = if (errType == 101) "Presiona el boton del Bridge primero"
                                          else first.getJSONObject("error").getString("description")
                                Log.w(TAG, "Error de pairing: $msg")
                                onResult(null, msg)
                            }
                            else -> onResult(null, "Respuesta inesperada del Bridge")
                        }
                    } finally {
                        conn.disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error conectando al Bridge: ${e.message}")
                    onResult(null, "No se pudo conectar a $ip")
                }
            }.also { it.name = "HuePairing" }.start()
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val baseUrl get() = "http://$ip/api/$token"
    private val drmMode = AtomicBoolean(false)

    fun verifyConnection(onResult: (ok: Boolean, lights: List<String>) -> Unit) {
        executor.submit {
            try {
                val response = get("$baseUrl/lights")
                // Bridge devuelve JSONObject con luces si token valido,
                // o JSONArray con error si token invalido
                if (response.trimStart().startsWith("[")) {
                    val errDesc = try {
                        JSONArray(response).getJSONObject(0)
                            .optJSONObject("error")?.optString("description") ?: "token invalido"
                    } catch (e: Exception) { "token invalido" }
                    Log.e(TAG, "Bridge rechazo el token: $errDesc")
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

    fun setDrmMode(enabled: Boolean, lightIds: List<String>) {
        if (drmMode.getAndSet(enabled) == enabled) return
        executor.submit {
            if (enabled) {
                Log.i(TAG, "DRM detectado — activando luz calida")
                lightIds.forEach { id ->
                    try {
                        val payload = JSONObject().apply {
                            put("on", true); put("ct", WARM_CT)
                            put("bri", 254); put("transitiontime", 4)
                        }
                        put("$baseUrl/lights/$id/state", payload.toString())
                    } catch (e: Exception) { Log.w(TAG, "Error luz $id: ${e.message}") }
                }
            }
        }
    }

    fun setLights(colors: Map<String, ZoneColor>) {
        if (drmMode.get()) return
        colors.forEach { (id, color) ->
            executor.submit {
                try {
                    val payload = JSONObject().apply {
                        put("on", true)
                        put("xy", JSONArray().apply {
                            put(color.x.toDouble())
                            put(color.y.toDouble())
                        })
                        put("bri", color.bri)
                        put("transitiontime", 1)
                    }
                    put("$baseUrl/lights/$id/state", payload.toString())
                } catch (e: Exception) { Log.w(TAG, "Error luz $id: ${e.message}") }
            }
        }
    }

    fun turnOffAll(lightIds: List<String>) {
        executor.submit {
            lightIds.forEach { id ->
                try {
                    put("$baseUrl/lights/$id/state",
                        JSONObject().apply { put("on", false) }.toString())
                } catch (e: Exception) { /* ignorar */ }
            }
        }
    }

    fun shutdown() { executor.shutdown() }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout    = TIMEOUT_MS
        return try { conn.inputStream.bufferedReader().readText() }
        finally { conn.disconnect() }
    }

    private fun put(url: String, body: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout    = TIMEOUT_MS
        conn.requestMethod  = "PUT"
        conn.doOutput       = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }
}
