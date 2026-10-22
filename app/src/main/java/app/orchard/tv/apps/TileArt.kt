package app.orchard.tv.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.orchard.tv.wallpaper.Images
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a tile draws: one card, plus the artwork the home screen needs to light its surroundings.
 *
 * - [back] always fills the tile: the card that ships in `assets/icons` for this package, or the
 *   unified graphite plate with the app's own icon on it.
 * - [fore] is the layer that used to shift against the plate under parallax. Every card is
 *   single-layer, so it is null everywhere today (and [foreFraction] with it); the field and the
 *   drawing code stay so the tiles keep their existing motion without touching the composables.
 */
class TileArt(
    val back: ImageBitmap,
    val fore: ImageBitmap?,
    val foreFraction: Float,
    val tint: Color,
    /**
     * The whole home backdrop for this app, baked small: its blurred artwork as ambient light plus
     * the shading that keeps the clock and grid legible. Scaled up to full screen it costs the GPU
     * one textured draw per frame, which matters on TV chips.
     */
    val backdrop: ImageBitmap,
)

object TileArtFactory {
    // Rendered at the tile's focused size on a 1080p panel, so the lifted tile is still pixel-sharp.
    const val W = 352
    const val H = 198

    private const val GRAPHITE_TOP = 0xFF3A3A3C.toInt()
    private const val GRAPHITE_BOTTOM = 0xFF242426.toInt()

    /**
     * The unified plate for an app that has no card of its own: one graphite colour, lit from above,
     * top mixed 5% toward white and bottom 13 levels darker. Same colour and same ramp as the
     * shipped `generic-app` fallback, so a TV full of unmatched apps still reads as one family.
     */
    private const val PLATE = 0xFF3E444F.toInt()
    private const val PLATE_LIFT = 0.05f
    private const val PLATE_DROP = 13f

    /** The plate's logo box: 58% of the tile height, square and centred, as in the approved design. */
    private const val PLATE_ART = 0.58f

    /** Launcher-like corner rounding applied to that logo box (24% of its side). */
    private const val PLATE_ART_RADIUS = 0.24f

    fun fromBanner(banner: Drawable): TileArt {
        val back = createBitmap(W, H)
        Canvas(back).apply {
            drawColor(GRAPHITE_BOTTOM)
            drawCover(banner, W, H)
        }
        return finish(back, null, 0f)
    }

    /**
     * The fallback for an app with no card: the icon the TV itself shows, untouched, sitting on
     * [PLATE] at [PLATE_ART] of the tile height.
     *
     * The icon keeps its own proportion and is only scaled down to fit the box — never stretched,
     * never cropped — so a wide icon gets a smaller box rather than losing its edges. No parallax:
     * one square logo, centred, exactly like the cards that ship in the APK.
     */
    fun fromIconTile(icon: Drawable): TileArt {
        val back = plate()
        val side = (H * PLATE_ART).roundToInt()
        val art = iconSquare(icon, side)
        Canvas(back).drawBitmap(art, ((W - side) / 2).toFloat(), ((H - side) / 2).toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return finish(back, null, 0f)
    }

    /** Absolute last resort, when even the generic card cannot be read: the bare plate. */
    fun fromPlainPlate(): TileArt = finish(plate(), null, 0f)

    private fun plate(): Bitmap {
        val back = createBitmap(W, H)
        Canvas(back).drawBitmap(toneColumn(H), null, android.graphics.Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
        return back
    }

    /** [PLATE] as a 1×h column: cosine ramp from the lit top to the darkened bottom. */
    private fun toneColumn(h: Int): Bitmap {
        val px = IntArray(h)
        for (y in 0 until h) {
            val k = (0.5 + 0.5 * cos(Math.PI * y / (h - 1).toDouble())).toFloat()
            var colour = 0xFF shl 24
            for (shift in intArrayOf(16, 8, 0)) {
                val base = (PLATE shr shift) and 0xFF
                val light = base + (255 - base) * PLATE_LIFT
                val dark = (base - PLATE_DROP).coerceAtLeast(0f)
                val v = (dark + (light - dark) * k).roundToInt().coerceIn(0, 255)
                colour = colour or (v shl shift)
            }
            px[y] = colour
        }
        val column = createBitmap(1, h)
        column.setPixels(px, 0, 1, 0, 0, 1, h)
        return column
    }

    /** Draws [d] into a [side]-square, fitted (contain) so it keeps its aspect, then rounds it. */
    private fun iconSquare(d: Drawable, side: Int): Bitmap {
        val out = createBitmap(side, side)
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight
        if (iw > 0 && ih > 0 && iw != ih) {
            val k = min(side / iw.toFloat(), side / ih.toFloat())
            val w = max(1, (iw * k).roundToInt())
            val h = max(1, (ih * k).roundToInt())
            val l = (side - w) / 2
            val t = (side - h) / 2
            d.setBounds(l, t, l + w, t + h)
        } else {
            d.setBounds(0, 0, side, side)
        }
        d.draw(Canvas(out))
        return roundCorners(out, PLATE_ART_RADIUS)
    }

    private fun roundCorners(src: Bitmap, radiusFraction: Float): Bitmap {
        val out = createBitmap(src.width, src.height)
        val r = src.width * radiusFraction
        Canvas(out).drawRoundRect(
            0f, 0f, src.width.toFloat(), src.height.toFloat(), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = android.graphics.BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) },
        )
        return out
    }

    private fun finish(back: Bitmap, fore: Bitmap?, foreFraction: Float): TileArt {
        val composed = if (fore == null) back else back.copy(Bitmap.Config.ARGB_8888, true).also {
            val size = (H * foreFraction).roundToInt()
            val left = (W - size) / 2
            val top = (H - size) / 2
            Canvas(it).drawBitmap(fore, null, android.graphics.Rect(left, top, left + size, top + size), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val tint = dominant(composed)
        val backdrop = backdropOf(composed)
        if (composed !== back) composed.recycle()
        return TileArt(
            back = back.asImageBitmap(),
            fore = fore?.asImageBitmap(),
            foreFraction = foreFraction,
            tint = Color(tint),
            backdrop = backdrop.asImageBitmap(),
        )
    }

    private fun Canvas.drawCover(d: Drawable, w: Int, h: Int) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = max(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val l = (w - dw) / 2
        val t = (h - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(this)
    }

    /** Saturation-weighted mean color: the hue a person would name when describing the tile. */
    private fun dominant(b: Bitmap): Int {
        val small = b.scale(24, 14)
        var r = 0.0; var g = 0.0; var bl = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val wgt = hsv[1] * hsv[2] * hsv[2] + 0.04
            r += android.graphics.Color.red(c) * wgt
            g += android.graphics.Color.green(c) * wgt
            bl += android.graphics.Color.blue(c) * wgt
            total += wgt
        }
        if (small !== b) small.recycle()
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((r / total).roundToInt(), (g / total).roundToInt(), (bl / total).roundToInt())
    }

    private fun backdropOf(src: Bitmap): Bitmap {
        val w = 64
        val h = 36
        val small = src.scale(w, h)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { Images.boxBlur(px, w, h, 4) }
        val blurred = createBitmap(w, h)
        blurred.setPixels(px, 0, w, 0, 0, w, h)
        // Lift saturation and pull brightness down: ambient light, not a picture. Pale artwork
        // (white banners) would spread a grey haze, so it gets dimmed much further.
        val hsv = FloatArray(3)
        var sat = 0f; var value = 0f
        for (c in px) { android.graphics.Color.colorToHSV(c, hsv); sat += hsv[1]; value += hsv[2] }
        sat /= px.size; value /= px.size
        val gain = if (sat < 0.2f) 0.82f - 0.5f * value * (1f - sat / 0.2f) else 0.82f
        val cm = ColorMatrix().apply {
            setSaturation(1.5f)
            postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })
        }
        val bw = 192
        val bh = 108
        val out = createBitmap(bw, bh)
        Canvas(out).apply {
            drawBitmap(
                blurred, null, android.graphics.Rect(0, 0, bw, bh),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) },
            )
            // Light kept up top where the shelf is, near-black under the grid.
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bh.toFloat(),
                    intArrayOf(black(0.10f), black(0.32f), black(0.80f), black(0.94f)),
                    floatArrayOf(0f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP,
                )
            })
            // The left edge shaded so the clock reads over bright artwork.
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, bw * 0.55f, 0f, black(0.38f), 0, Shader.TileMode.CLAMP)
            })
        }
        small.recycle(); blurred.recycle()
        return out
    }

    private fun black(alpha: Float) = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
}
