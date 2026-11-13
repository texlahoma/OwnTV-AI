package tv.own.owntv.features.shell.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import tv.own.owntv.core.weather.WeatherInfo

/** The weather symbol beside the temperature in the top-right cluster. */
@Composable
internal fun WeatherConditionIcon(info: WeatherInfo, modifier: Modifier = Modifier) {
    val key = info.symbolKey()
    val sunC = Color(0xFFFFD166); val moonC = Color(0xFFDDF8FF)
    val cloudC = Color(0xFFDDEFE9); val rainC = Color(0xFF76A7FF)
    val snowC = Color(0xFFF0FCFF); val fogC = Color(0xFFDDF8FF)
    val thunderC = Color(0xFFFFD166)

    Canvas(modifier) {
        val s = size.minDimension / 100f
        val fill = androidx.compose.ui.graphics.drawscope.Fill
        val stk = Stroke(width = 4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * s, y * s)

        fun sun(cx: Float, cy: Float, r: Float) {
            for (i in 0 until 10) { val a = i * kotlin.math.PI.toFloat() / 5f; drawLine(sunC, o(cx + kotlin.math.cos(a) * (r + 8f), cy + kotlin.math.sin(a) * (r + 8f)), o(cx + kotlin.math.cos(a) * (r + 20f), cy + kotlin.math.sin(a) * (r + 20f)), strokeWidth = 4f * s, cap = StrokeCap.Round) }
            drawCircle(sunC, r * s, o(cx, cy))
        }
        fun moon(cx: Float, cy: Float, r: Float) {
            drawCircle(moonC, r * s, o(cx, cy))
            drawCircle(Color.Black, (r * 0.92f * s), o(cx + r * 0.45f * s, cy - r * 0.20f * s), style = fill, blendMode = BlendMode.Clear)
            listOf(-0.42f to -0.28f, -0.20f to 0.25f, 0.02f to -0.06f).forEach { (dx, dy) -> drawCircle(moonC.copy(alpha = 0.55f), 2.2f * s, o(cx + dx * r, cy + dy * r)) }
        }
        fun cloud(cx: Float, cy: Float, k: Float) {
            drawCircle(cloudC, 16f * k * s, o(cx - 19f * k, cy + 5f * k))
            drawCircle(cloudC, 23f * k * s, o(cx, cy - 9f * k))
            drawCircle(cloudC, 18f * k * s, o(cx + 24f * k, cy + 2f * k))
            drawCircle(cloudC, 13f * k * s, o(cx + 39f * k, cy + 10f * k))
        }
        fun drops(cx: Float, cy: Float, n: Int, c: Color) {
            for (i in 0 until n) drawLine(c, o(cx + i * 18f, cy), o(cx - 5f + i * 18f, cy + 18f), strokeWidth = 4f * s, cap = StrokeCap.Round)
        }
        fun snow(cx: Float, cy: Float) {
            for (i in 0 until 3) { val x = cx + i * 20f; val y = cy + (i % 2) * 4f; val w = 3f * s; val c = StrokeCap.Round; drawLine(snowC, o(x - 7f, y), o(x + 7f, y), w, c); drawLine(snowC, o(x, y - 7f), o(x, y + 7f), w, c); drawLine(snowC, o(x - 5f, y - 5f), o(x + 5f, y + 5f), w, c); drawLine(snowC, o(x + 5f, y - 5f), o(x - 5f, y + 5f), w, c) }
        }
        fun fog(cx: Float, cy: Float) {
            for (i in 0 until 4) drawLine(fogC.copy(alpha = 0.74f), o(cx - 38f, cy + i * 12f), o(cx + 38f, cy + i * 12f), strokeWidth = 5f * s, cap = StrokeCap.Round)
        }
        fun bolt(cx: Float, cy: Float) { val p = Path().apply { moveTo(cx * s, cy * s); lineTo((cx - 12f) * s, (cy + 26f) * s); lineTo((cx + 1f) * s, (cy + 23f) * s); lineTo((cx - 8f) * s, (cy + 48f) * s); lineTo((cx + 18f) * s, (cy + 15f) * s); lineTo((cx + 4f) * s, (cy + 18f) * s); close() }; drawPath(p, thunderC, style = fill) }

        when (key) {
            "sunny" -> sun(50f, 50f, 23f)
            "clearNight" -> moon(50f, 50f, 28f)
            "partlyDay" -> { sun(36f, 36f, 17f); cloud(56f, 60f, 1f) }
            "partlyNight" -> { moon(36f, 35f, 20f); cloud(56f, 60f, 1f) }
            "cloudy" -> { cloud(46f, 48f, 1.15f); cloud(60f, 62f, 0.82f) }
            "fog" -> { cloud(50f, 36f, 0.9f); fog(50f, 58f) }
            "drizzle" -> { cloud(50f, 38f, 1f); drops(35f, 62f, 3, rainC.copy(alpha = 0.72f)) }
            "rain" -> { cloud(50f, 36f, 1.05f); drops(30f, 60f, 4, rainC) }
            "snow" -> { cloud(50f, 36f, 1.05f); snow(32f, 65f) }
            "thunder" -> { cloud(50f, 35f, 1.05f); bolt(52f, 48f); drops(28f, 66f, 2, rainC) }
            else -> cloud(46f, 48f, 1.15f)
        }
    }
}
