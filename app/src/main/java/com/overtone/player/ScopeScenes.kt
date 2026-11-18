package com.overtone.player

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.*
import kotlin.random.Random

private val ink = Color(0xFF080911)
private val cyan = Color(0xFF78F0FE)
private val cobalt = Color(0xFF5265FF)
private val violet = Color(0xFFBC86FF)
private val amber = Color(0xFFFFC27E)
private val ember = Color(0xFFFF686B)
private val lime = Color(0xFFB5FF87)
private val fuchsia = Color(0xFFFF63CB)

internal data class ScopeColors(val primary: Color, val secondary: Color, val amount: Float) {
    private val deep = vivid(primary, .65f, .88f)
    private val light = vivid(if (secondary == Color.White) primary else secondary, .42f, 1f)

    fun tone(fallback: Color, alternate: Boolean = false): Color =
        lerp(fallback, if (alternate) light else deep, amount * if (primary == Color.White) 1f else .88f)

    private fun vivid(sample: Color, saturation: Float, brightness: Float): Color {
        if (sample == Color.White) return lerp(Color(0xFF777B83), Color.White, brightness)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(sample.toArgb(), hsv)
        return Color.hsv(hsv[0], maxOf(hsv[1] * .88f, saturation), maxOf(hsv[2], brightness))
    }
}

internal fun DrawScope.drawScopeScene(
    scene: ScopeMode, motion: ScopeMotion, colors: ScopeColors, opacity: Float, center: Offset?
) {
    if (opacity <= 0f || size.minDimension <= 1f) return
    when (scene) {
        ScopeMode.AUTO, ScopeMode.RIBBONS -> drawRibbons(motion, opacity, colors, center)
        ScopeMode.ORBIT -> drawOrbit(motion, opacity, colors, center)
        ScopeMode.PRISM -> drawPrism(motion, opacity, colors, center)
        ScopeMode.WATERFALL -> drawWaterfall(motion.history, opacity, colors, center)
        else -> drawClassic(scene, motion, colors.tone(Color(0xFFECEBF0)), opacity, center)
    }
}

private fun DrawScope.sceneSpace(primary: Color, opacity: Float, center: Offset, radius: Float) {
    drawRect(Brush.radialGradient(listOf(lerp(ink, primary, .10f), ink), center, radius),
        alpha = opacity * .93f)
}

private fun DrawScope.drawRibbons(motion: ScopeMotion, opacity: Float, colors: ScopeColors, center: Offset?) {
    val mid = center ?: Offset(size.width * .50f, size.height * .51f)
    val field = min(size.height * .73f, size.width * 1.22f)
    val deep = colors.tone(cobalt)
    val lightTone = colors.tone(cyan, alternate = true)
    val violetTone = colors.tone(violet, alternate = true)
    sceneSpace(deep, opacity, mid, size.maxDimension * .58f)
    val ribbonWidth = min(size.width, field * 1.60f)
    val left = mid.x - ribbonWidth / 2
    val points = 86
    val wave = Path()
    val lower = FloatArray(points + 1)
    for (layer in 0..6) {
        wave.reset()
        val color = lerp(deep, if (layer % 3 == 0) violetTone else lightTone, (layer + 1) / 8f)
        for (i in 0..points) {
            val fraction = i.toFloat() / points
            val band = ((fraction * 35).toInt() + layer) % motion.bands.size
            val envelope = sin(PI.toFloat() * fraction).coerceAtLeast(0f)
            val swing = sin(fraction * 7.9f + motion.phase * 2.1f + layer * .41f)
            val overtone = sin(fraction * 17.7f - motion.phase * 1.2f + layer * .65f)
            val amplitude = .098f + motion.bass * .18f + motion.bands[band] * .16f
            val center = mid.y + envelope * field *
                (swing * amplitude + overtone * (.035f + motion.level * .076f) + (layer - 3) * .018f)
            val thickness = field * envelope * (.022f + .052f * motion.level + layer * .002f)
            val x = left + fraction * ribbonWidth
            lower[i] = center + thickness
            if (i == 0) wave.moveTo(x, center - thickness) else wave.lineTo(x, center - thickness)
        }
        val light = Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent), left, left + ribbonWidth)
        drawPath(wave, light, alpha = opacity * (.11f + motion.level * .09f), style = Stroke(4.1.dp.toPx()))
        drawPath(wave, light, alpha = opacity * (.46f + layer * .058f),
            style = Stroke((1f + layer * .12f).dp.toPx()))
        for (i in points downTo 0) wave.lineTo(left + i.toFloat() / points * ribbonWidth, lower[i])
        wave.close()
        drawPath(wave, light, alpha = opacity * (.105f + motion.level * .09f))
    }
}

private data class OrbitDot(val angle: Float, val altitude: Float, val speed: Float, val light: Float)
private val starlight = Random(17).let { random ->
    List(196) { OrbitDot(random.nextFloat() * (2f * PI.toFloat()), random.nextFloat(), random.nextFloat(), random.nextFloat()) }
}
private fun DrawScope.drawOrbit(motion: ScopeMotion, opacity: Float, colors: ScopeColors, center: Offset?) {
    val mid = center ?: Offset(size.width * .50f, size.height * .52f)
    val diameter = min(size.width * .92f, size.height * .66f)
    val warm = colors.tone(amber, alternate = true)
    val glow = colors.tone(ember)
    sceneSpace(glow, opacity, mid, size.maxDimension * .53f)
    val radius = diameter * (.218f + .066f * motion.bass)
    drawCircle(Brush.radialGradient(listOf(warm.copy(alpha = .19f), glow.copy(alpha = .09f), Color.Transparent),
        mid, radius * 1.95f), radius * 1.95f, mid, alpha = opacity * (.49f + motion.pulse * .51f))
    val orbitStroke = .65.dp.toPx()
    for (i in 0..2) {
        drawCircle(warm.copy(alpha = opacity * (.32f - i * .07f)),
            radius * (1f + i * .115f + motion.pulse * i * .075f), mid, style = Stroke(orbitStroke))
    }
    for ((i, star) in starlight.withIndex()) {
        val band = motion.bands[i % motion.bands.size]
        val angle = star.angle + motion.phase * (.11f + star.speed * .30f)
        val stretch = radius * (.97f + star.altitude * (.65f + band * .51f + motion.pulse * .36f))
        val dot = Offset(mid.x + cos(angle) * stretch, mid.y + sin(angle) * stretch)
        val opacityAtDot = opacity * ((.16f + star.light * .65f) * (.48f + band * .45f + motion.pulse * .25f)).coerceAtMost(1f)
        drawCircle(lerp(glow, warm, star.light).copy(alpha = opacityAtDot),
            (.50f + star.light * 1.06f + band * .70f).dp.toPx(), dot)
    }
    for (i in motion.bands.indices) {
        val angle = i * 2f * PI.toFloat() / motion.bands.size + motion.phase * .12f
        val vec = Offset(cos(angle), sin(angle))
        val fade = opacity * motion.bands[i]
        drawLine(warm.copy(alpha = fade * .70f), mid + vec * (radius * .87f),
            mid + vec * (radius * (.87f - motion.bands[i] * .48f)), 1.2.dp.toPx(), StrokeCap.Round)
    }
}

private fun DrawScope.drawPrism(motion: ScopeMotion, opacity: Float, colors: ScopeColors, center: Offset?) {
    val mid = center ?: Offset(size.width * .50f, size.height * .52f)
    val edge = min(size.width * .40f, size.height * .31f)
    val main = colors.tone(violet)
    val spectrumColors = listOf(colors.tone(fuchsia), main, colors.tone(lime, alternate = true))
    sceneSpace(main, opacity, mid, size.maxDimension * .56f)
    val count = 96
    val prism = Path()
    for (layer in 0..4) {
        prism.reset()
        val twist = (layer - 2) * .18f + sin(motion.phase * 1.34f + layer * .5f) * .20f
        for (point in 0 until count) {
            val phase = point.toFloat() / count
            val angle = phase * 2f * PI.toFloat() - PI.toFloat() / 2 + twist
            val band = motion.bands[(point * 7 + layer * 11) % motion.bands.size]
            val polygon = .82f + .24f * abs(cos(3f * (angle - twist)))
            val radius = edge * (.42f + layer * .113f) * polygon *
                (1f + band * (.25f + if (point % 4 == 0) .41f else 0f) + motion.pulse * .24f)
            val x = mid.x + cos(angle) * radius
            val y = mid.y + sin(angle) * radius
            if (point == 0) prism.moveTo(x, y) else prism.lineTo(x, y)
        }
        prism.close()
        val spectrum = Brush.linearGradient(spectrumColors,
            Offset(mid.x - edge, mid.y + edge), Offset(mid.x + edge, mid.y - edge))
        drawPath(prism, spectrum, alpha = opacity * (.11f + motion.level * .04f))
        drawPath(prism, spectrum, alpha = opacity * (.40f + layer * .11f),
            style = Stroke((.75f + layer * .16f).dp.toPx()))
    }
}

private const val historyWidth = 80
private const val rampSize = 96
private fun heatRamp(colors: ScopeColors): IntArray {
    val base = colors.tone(fuchsia)
    val peak = colors.tone(amber, alternate = true)
    val stops = listOf(ink, lerp(ink, base, .24f), lerp(ink, base, .60f),
        base, peak, lerp(peak, Color.White, .63f))
    return IntArray(rampSize) { index ->
        val position = index.toFloat() * (stops.size - 1) / (rampSize - 1)
        val first = position.toInt().coerceAtMost(stops.lastIndex - 1)
        lerp(stops[first], stops[first + 1], position - first).toArgb()
    }
}

internal class SpectrumHistory(private val bins: Int) {
    private var ramp = heatRamp(ScopeColors(Color.White, Color.White, 0f))
    private val ring = IntArray(historyWidth * bins)
    private val orderedPixels = IntArray(ring.size)
    private val bitmap = Bitmap.createBitmap(historyWidth, bins, Bitmap.Config.ARGB_8888)
    val image = bitmap.asImageBitmap()
    private var next = 0
    init { bitmap.eraseColor(ramp[0]) }

    fun recolor(colors: ScopeColors) {
        ramp = heatRamp(colors)
        redraw()
    }

    fun push(bands: FloatArray) {
        for (row in 0 until bins) {
            val intensity = bands[bins - row - 1]
            ring[row * historyWidth + next] = (sqrt(intensity) * (rampSize - 1)).roundToInt()
        }
        next = (next + 1) % historyWidth
        redraw()
    }

    private fun redraw() {
        for (row in 0 until bins) {
            val rowStart = row * historyWidth
            for (column in 0 until historyWidth) {
                orderedPixels[rowStart + column] = ramp[ring[rowStart + (column + next) % historyWidth]]
            }
        }
        bitmap.setPixels(orderedPixels, 0, historyWidth, 0, 0, historyWidth, bins)
    }
}

private fun DrawScope.drawWaterfall(history: SpectrumHistory, opacity: Float, colors: ScopeColors, center: Offset?) {
    val mid = center ?: Offset(size.width * .50f, size.height * .53f)
    sceneSpace(colors.tone(fuchsia), opacity, mid, size.maxDimension * .58f)
    val w = size.width * .88f
    val h = min(size.height * .63f, w * .80f)
    val x = mid.x - w / 2
    val y = mid.y - h / 2
    if (w < 1f || h < 1f) return
    drawImage(history.image, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()), alpha = opacity, filterQuality = FilterQuality.Low)
    for (i in 0..4) {
        val ordinate = y + i * h / 4
        drawLine(Color.White.copy(alpha = opacity * .17f), Offset(x, ordinate), Offset(x + w, ordinate), .65.dp.toPx())
    }
    drawLine(colors.tone(amber, alternate = true).copy(alpha = opacity * .8f), Offset(x + w, y), Offset(x + w, y + h), 1.dp.toPx())
}

private fun DrawScope.drawClassic(scene: ScopeMode, motion: ScopeMotion, tone: Color, opacity: Float, center: Offset?) {
    val mid = center ?: Offset(size.width * .50f, size.height * .53f)
    sceneSpace(tone, opacity, mid, size.maxDimension * .56f)
    val stroke = Stroke(1.dp.toPx())
    val grid = tone.copy(alpha = .16f * opacity)
    val bright = tone.copy(alpha = .94f * opacity)
    val w = size.width * .84f
    val h = min(size.height * .55f, w * .67f)
    val left = mid.x - w / 2
    val floor = mid.y + h / 2
    if (scene == ScopeMode.RING) {
        val radius = min(w, size.height * .76f) * .22f
        for (i in 0..4) drawCircle(grid, radius * (1f + i * .22f), mid, style = stroke)
        motion.bands.forEachIndexed { index, band ->
            val angle = index * 2f * PI.toFloat() / motion.bands.size - PI.toFloat() / 2
            val vector = Offset(cos(angle), sin(angle))
            drawLine(bright, mid + vector * radius, mid + vector * radius * (1.03f + band * .86f),
                1.65.dp.toPx(), StrokeCap.Round)
        }
        return
    }
    for (row in 0..5) {
        val y = floor - row * h / 5
        drawLine(grid, Offset(left, y), Offset(left + w, y), .65.dp.toPx())
    }
    if (scene == ScopeMode.BARS) {
        val step = w / motion.bands.size
        val fill = Brush.verticalGradient(listOf(bright, tone.copy(alpha = opacity * .28f)), floor - h, floor)
        motion.bands.forEachIndexed { index, band ->
            val length = max(1.dp.toPx(), band * h)
            drawRoundRect(fill,
                Offset(left + (index + .17f) * step, floor - length), Size(step * .66f, length), CornerRadius(step * .24f))
        }
    } else {
        val wave = Path()
        val stride = 8
        for (i in 0..motion.samples.size - stride step stride) {
            val x = left + i.toFloat() * w / (motion.samples.size - stride)
            val y = mid.y - motion.samples[i] * h * .48f
            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        drawPath(wave, tone.copy(alpha = .16f * opacity), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(wave, bright, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
    }
}
