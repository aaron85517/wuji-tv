package app.orchard.tv.ui.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orchard.tv.weather.Sky
import kotlin.math.cos
import kotlin.math.sin

/**
 * The weather marks: the same drawn-not-bundled approach as [app.orchard.tv.ui.theme.Glyph], so they
 * stay crisp from 17dp in the capsule to 46dp in the panel and never depend on an emoji font (TV
 * firmwares ship emoji sets that render as boxes or in colour, which is exactly what a quiet clock
 * corner does not want).
 *
 * Each mark is a silhouette of the sky plus, where it helps, a line accent: a filled cloud reads at
 * 17dp where an outlined one turns to mush.
 */
@Composable
fun WeatherIcon(sky: Sky, night: Boolean, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawSky(sky, night, color) }
}

private fun DrawScope.drawSky(sky: Sky, night: Boolean, color: Color) {
    val s = size.minDimension
    val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), strokeWidth = s * 0.075f, cap = StrokeCap.Round)

    when (sky) {
        Sky.Clear -> if (night) moon(color, s) else sun(color, s, Offset(0.5f, 0.5f), 0.15f)
        Sky.PartlyCloudy -> {
            if (night) {
                moon(color, s, Offset(0.34f, 0.30f), 0.86f)
            } else {
                sun(color, s, Offset(0.34f, 0.30f), 0.12f)
            }
            cloud(color, s, 0.02f, 0.16f)
        }
        Sky.Overcast -> {
            // Two clouds, the far one a touch higher, the way an overcast sky stacks up.
            cloud(color, s, -0.10f, -0.06f, alpha = 0.55f)
            cloud(color, s, 0.06f, 0.10f)
        }
        Sky.Rain -> {
            cloud(color, s, 0f, -0.04f)
            listOf(0.30f, 0.50f, 0.70f).forEach { x -> line(x, 0.80f, x - 0.07f, 0.95f) }
        }
        Sky.Snow -> {
            cloud(color, s, 0f, -0.04f)
            listOf(0.30f, 0.50f, 0.70f).forEach { x ->
                drawCircle(color, radius = s * 0.045f, center = Offset(x * s, 0.88f * s))
            }
        }
        Sky.Thunder -> {
            cloud(color, s, 0f, -0.06f)
            val bolt = Path().apply {
                moveTo(0.54f * s, 0.66f * s); lineTo(0.40f * s, 0.88f * s); lineTo(0.52f * s, 0.88f * s)
                lineTo(0.44f * s, 1.0f * s); lineTo(0.68f * s, 0.78f * s); lineTo(0.55f * s, 0.78f * s)
                close()
            }
            drawPath(bolt, color)
        }
        Sky.Fog -> {
            // No cloud: three lies of the same stroke, narrowing upwards.
            line(0.14f, 0.32f, 0.80f, 0.32f)
            line(0.22f, 0.50f, 0.86f, 0.50f)
            line(0.14f, 0.68f, 0.72f, 0.68f)
        }
        Sky.Unknown -> cloud(color, s, 0f, 0.04f)
    }
}

/**
 * A filled cloud: a rounded base with two domes. [dx]/[dy] shift the whole shape so a sun or moon can
 * sit behind it, and [alpha] lets the back cloud of an overcast sky sit behind the front one.
 */
private fun DrawScope.cloud(color: Color, s: Float, dx: Float, dy: Float, alpha: Float = 1f) {
    val c = color.copy(alpha = color.alpha * alpha)
    drawRoundRect(
        color = c,
        topLeft = Offset((0.12f + dx) * s, (0.56f + dy) * s),
        size = Size(0.76f * s, 0.24f * s),
        cornerRadius = CornerRadius(0.12f * s),
    )
    drawCircle(c, radius = 0.19f * s, center = Offset((0.40f + dx) * s, (0.55f + dy) * s))
    drawCircle(c, radius = 0.14f * s, center = Offset((0.64f + dx) * s, (0.58f + dy) * s))
}

/** The sun, as a disc with eight rays; [r] is the disc radius as a fraction of the box. */
private fun DrawScope.sun(color: Color, s: Float, center: Offset, r: Float) {
    val c = Offset(center.x * s, center.y * s)
    drawCircle(color, radius = r * s, center = c)
    for (i in 0 until 8) {
        val a = (i * 45f) * (Math.PI / 180).toFloat()
        val inner = r + 0.09f
        val outer = r + 0.20f
        drawLine(
            color,
            Offset(c.x + inner * s * cos(a), c.y + inner * s * sin(a)),
            Offset(c.x + outer * s * cos(a), c.y + outer * s * sin(a)),
            strokeWidth = s * 0.075f,
            cap = StrokeCap.Round,
        )
    }
}

/** A crescent, cut from a disc, with two small stars for the clear-night case. */
private fun DrawScope.moon(color: Color, s: Float, center: Offset = Offset(0.5f, 0.48f), scale: Float = 1f) {
    val r = 0.24f * s * scale
    val c = Offset(center.x * s, center.y * s)
    val disc = Path().apply {
        addOval(Rect(Offset(c.x - r, c.y - r), Size(2 * r, 2 * r)))
    }
    val bite = Path().apply {
        addOval(Rect(Offset(c.x - r + 0.14f * r, c.y - r - 0.10f * r), Size(1.85f * r, 1.85f * r)))
    }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), color)
    if (scale > 0.95f) {
        drawCircle(color.copy(alpha = color.alpha * 0.85f), radius = s * 0.022f, center = Offset(0.78f * s, 0.26f * s))
        drawCircle(color.copy(alpha = color.alpha * 0.7f), radius = s * 0.016f, center = Offset(0.86f * s, 0.44f * s))
    }
}
