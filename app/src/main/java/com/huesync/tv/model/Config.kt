package com.huesync.tv.model

import android.content.Context

data class LightZone(val lightId: String, val zone: Zone)
enum class Zone { TOP, BOTTOM, LEFT, RIGHT, CENTER, BOTTOM_LEFT, BOTTOM_RIGHT }
enum class CaptureMode { SCREEN, USB }

data class Config(
    val bridgeIp: String = "",
    val bridgeToken: String = "",
    // Entertainment API — se obtiene junto con bridgeToken al hacer pairing con generateclientkey:true
    val clientKey: String = "",
    // ID del grupo de entretenimiento (Entertainment Area) configurado en la app Hue
    val entertainmentGroupId: String = "",
    val captureMode: CaptureMode = CaptureMode.SCREEN,
    val targetFps: Int = 24,
    val hueFps: Int = 8,    // 8fps suficiente para Hue, evita competir con decoder de video
    val smoothing: Float = 0.25f,
    val borderPct: Float = 0.12f,
    val satBoost: Float = 1.4f,
    val minBri: Float = 0.05f,
    val maxBri: Float = 1.0f,
    val lights: List<LightZone> = listOf(
        LightZone("1", Zone.BOTTOM_RIGHT),
        LightZone("2", Zone.BOTTOM_LEFT)
    )
) {
    /** Devuelve true si tenemos todo lo necesario para usar Entertainment API */
    val entertainmentReady: Boolean
        get() = bridgeIp.isNotEmpty() && bridgeToken.isNotEmpty() &&
                clientKey.isNotEmpty() && entertainmentGroupId.isNotEmpty()

    companion object {
        private const val PREFS = "huesync_config"

        fun load(context: Context): Config {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val n = p.getInt("light_count", 2)
            val lights = (0 until n).map { i ->
                LightZone(
                    p.getString("light_${i}_id", "${i + 1}") ?: "${i + 1}",
                    Zone.valueOf(p.getString("light_${i}_zone", Zone.LEFT.name) ?: Zone.LEFT.name)
                )
            }
            return Config(
                bridgeIp             = p.getString("bridge_ip", "") ?: "",
                bridgeToken          = p.getString("bridge_token", "") ?: "",
                clientKey            = p.getString("client_key", "") ?: "",
                entertainmentGroupId = p.getString("entertainment_group_id", "") ?: "",
                captureMode          = CaptureMode.valueOf(p.getString("capture_mode", CaptureMode.SCREEN.name) ?: CaptureMode.SCREEN.name),
                targetFps            = p.getInt("target_fps", 24),
                hueFps               = p.getInt("hue_fps", 8),
                smoothing            = p.getFloat("smoothing", 0.25f),
                borderPct            = p.getFloat("border_pct", 0.12f),
                satBoost             = p.getFloat("sat_boost", 1.4f),
                minBri               = p.getFloat("min_bri", 0.05f),
                maxBri               = p.getFloat("max_bri", 1.0f),
                lights               = lights
            )
        }

        fun save(context: Context, config: Config) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                putString("bridge_ip",              config.bridgeIp)
                putString("bridge_token",           config.bridgeToken)
                putString("client_key",             config.clientKey)
                putString("entertainment_group_id", config.entertainmentGroupId)
                putString("capture_mode",           config.captureMode.name)
                putInt("target_fps",                config.targetFps)
                putInt("hue_fps",                   config.hueFps)
                putFloat("smoothing",               config.smoothing)
                putFloat("border_pct",              config.borderPct)
                putFloat("sat_boost",               config.satBoost)
                putFloat("min_bri",                 config.minBri)
                putFloat("max_bri",                 config.maxBri)
                putInt("light_count",               config.lights.size)
                config.lights.forEachIndexed { i, l ->
                    putString("light_${i}_id",   l.lightId)
                    putString("light_${i}_zone", l.zone.name)
                }
                apply()
            }
        }
    }
}
