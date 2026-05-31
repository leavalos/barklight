package com.huesync.tv.analyzer

import android.graphics.Bitmap
import android.graphics.Color
import com.huesync.tv.model.Config
import com.huesync.tv.model.Zone
import kotlin.math.pow

data class ZoneColor(val r: Int, val g: Int, val b: Int, val x: Float, val y: Float, val bri: Int)

class ColorAnalyzer(private val config: Config) {

    private val smooth = mutableMapOf<String, Triple<Float, Float, Float>>()

    fun analyze(bitmap: Bitmap): Map<String, ZoneColor> {
        val result = mutableMapOf<String, ZoneColor>()
        for (light in config.lights) {
            val region = extractZone(bitmap, light.zone)
            var (r, g, b) = dominantColor(region)
            if (region != bitmap) region.recycle()

            val prev  = smooth[light.lightId] ?: Triple(r.toFloat(), g.toFloat(), b.toFloat())
            val alpha = config.smoothing
            r = (alpha * prev.first  + (1 - alpha) * r).toInt()
            g = (alpha * prev.second + (1 - alpha) * g).toInt()
            b = (alpha * prev.third  + (1 - alpha) * b).toInt()
            smooth[light.lightId] = Triple(r.toFloat(), g.toFloat(), b.toFloat())

            val (br, bg, bb) = boostSaturation(r, g, b, config.satBoost)
            val (x, y) = rgbToXy(br, bg, bb)
            // Brillo siempre al maximo
            val bri = 254
            result[light.lightId] = ZoneColor(br, bg, bb, x, y, bri)
        }
        return result
    }

    private fun extractZone(bmp: Bitmap, zone: Zone): Bitmap {
        val w = bmp.width; val h = bmp.height
        val bh = (h * config.borderPct).toInt().coerceAtLeast(1)
        val bw = (w * config.borderPct).toInt().coerceAtLeast(1)
        // Para BOTTOM_LEFT y BOTTOM_RIGHT usamos el cuarto inferior
        // dividido en mitad izquierda y mitad derecha
        val bottomH = (h * 0.25f).toInt().coerceAtLeast(1)
        val halfW   = w / 2

        return when (zone) {
            Zone.TOP          -> Bitmap.createBitmap(bmp, 0, 0, w, bh)
            Zone.BOTTOM       -> Bitmap.createBitmap(bmp, 0, h - bh, w, bh)
            Zone.LEFT         -> Bitmap.createBitmap(bmp, 0, 0, bw, h)
            Zone.RIGHT        -> Bitmap.createBitmap(bmp, w - bw, 0, bw, h)
            Zone.CENTER       -> Bitmap.createBitmap(bmp, bw, bh, (w - bw * 2).coerceAtLeast(1), (h - bh * 2).coerceAtLeast(1))
            Zone.BOTTOM_LEFT  -> Bitmap.createBitmap(bmp, 0,      h - bottomH, halfW,     bottomH)
            Zone.BOTTOM_RIGHT -> Bitmap.createBitmap(bmp, halfW,  h - bottomH, w - halfW, bottomH)
        }
    }

    private fun dominantColor(bmp: Bitmap): Triple<Int, Int, Int> {
        val scaled = if (bmp.width > 20 || bmp.height > 20)
            Bitmap.createScaledBitmap(bmp, 20, 20, false) else bmp
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled != bmp) scaled.recycle()
        return kMeans(pixels, 3)
    }

    private fun kMeans(pixels: IntArray, k: Int): Triple<Int, Int, Int> {
        if (pixels.isEmpty()) return Triple(128, 128, 128)

        val step = (pixels.size / k).coerceAtLeast(1)
        val cx = FloatArray(k); val cy = FloatArray(k); val cz = FloatArray(k)
        for (i in 0 until k) {
            val px = pixels[(i * step).coerceAtMost(pixels.size - 1)]
            cx[i] = Color.red(px).toFloat()
            cy[i] = Color.green(px).toFloat()
            cz[i] = Color.blue(px).toFloat()
        }

        val assignments = IntArray(pixels.size)
        repeat(5) {
            for (idx in pixels.indices) {
                val px = pixels[idx]
                val r = Color.red(px).toFloat()
                val g = Color.green(px).toFloat()
                val b = Color.blue(px).toFloat()
                var minDist = Float.MAX_VALUE; var nearest = 0
                for (ci in 0 until k) {
                    val d = (r - cx[ci]).pow(2) + (g - cy[ci]).pow(2) + (b - cz[ci]).pow(2)
                    if (d < minDist) { minDist = d; nearest = ci }
                }
                assignments[idx] = nearest
            }
            val sumR = FloatArray(k); val sumG = FloatArray(k)
            val sumB = FloatArray(k); val cnt  = FloatArray(k)
            for (idx in pixels.indices) {
                val c = assignments[idx]; val px = pixels[idx]
                sumR[c] += Color.red(px).toFloat()
                sumG[c] += Color.green(px).toFloat()
                sumB[c] += Color.blue(px).toFloat()
                cnt[c]  += 1f
            }
            for (i in 0 until k) {
                if (cnt[i] > 0f) {
                    cx[i] = sumR[i] / cnt[i]
                    cy[i] = sumG[i] / cnt[i]
                    cz[i] = sumB[i] / cnt[i]
                }
            }
        }
        val counts = IntArray(k)
        for (a in assignments) counts[a]++
        val best = counts.indices.maxByOrNull { counts[it] } ?: 0
        return Triple(cx[best].toInt(), cy[best].toInt(), cz[best].toInt())
    }

    private fun boostSaturation(r: Int, g: Int, b: Int, factor: Float): Triple<Int, Int, Int> {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        hsv[1] = (hsv[1] * factor).coerceIn(0f, 1f)
        val c = Color.HSVToColor(hsv)
        return Triple(Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun rgbToXy(r: Int, g: Int, b: Int): Pair<Float, Float> {
        fun gamma(c: Float) = if (c > 0.04045f) ((c + 0.055f) / 1.055f).pow(2.4f) else c / 12.92f
        val rL = gamma(r / 255f); val gL = gamma(g / 255f); val bL = gamma(b / 255f)
        val X = rL * 0.664511f + gL * 0.154324f + bL * 0.162028f
        val Y = rL * 0.283881f + gL * 0.668433f + bL * 0.047685f
        val Z = rL * 0.000088f + gL * 0.072310f + bL * 0.986039f
        val t = X + Y + Z
        return if (t == 0f) Pair(0.3127f, 0.3290f) else Pair(X / t, Y / t)
    }
}
