package com.overtone.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class FrostPalette(val tint: Color = Color.White, val secondary: Color = Color.White, val amount: Float = 0f) {
    private val primaryHue = coverHsv(tint)
    private val secondaryHue = coverHsv(secondary)
    private fun tone(neutral: Long, lightness: Float, saturation: Float, hsv: FloatArray = primaryHue): Color {
        val colored = Color.hsl(hsv[0], saturation * (.62f + hsv[1] * .38f), lightness)
        return lerp(Color(neutral), colored, amount)
    }

    val ink = tone(0xFF101010, .075f, .51f)
    val glow = tone(0xFF101010, .165f, .68f, secondaryHue)
    val panel = tone(0xFF1D1D1D, .13f, .48f)
    val raised = tone(0xFF2C2C2C, .185f, .52f)
    val line = tone(0xFF393939, .29f, .30f)
    val accent = tone(0xFFF5F4F2, .79f, .75f)
    val accentWell = tone(0xFF2E2E2E, .235f, .52f, secondaryHue)
    val muted = tone(0xFFB8B8B8, .75f, .20f)
    val dim = tone(0xFFA1A1A1, .67f, .17f)
}

private fun coverHsv(color: Color): FloatArray =
    FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }

internal val LocalFrost = compositionLocalOf { FrostPalette() }
val Ink: Color @Composable get() = LocalFrost.current.ink
val Panel: Color @Composable get() = LocalFrost.current.panel
val Raised: Color @Composable get() = LocalFrost.current.raised
val Line: Color @Composable get() = LocalFrost.current.line
val Accent: Color @Composable get() = LocalFrost.current.accent
val AccentWell: Color @Composable get() = LocalFrost.current.accentWell
val Paper = Color(0xFFF5F4F2)
val Muted: Color @Composable get() = LocalFrost.current.muted
val Dim: Color @Composable get() = LocalFrost.current.dim

@Composable
fun Modifier.frosted(radius: Int = 24, opacity: Float = .72f): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    val panel = Panel
    val edge = Accent
    return this.clip(shape)
        .background(panel.copy(alpha = opacity))
        .background(Brush.verticalGradient(listOf(edge.copy(alpha = .08f), edge.copy(alpha = .006f))))
        .border(1.dp, Brush.verticalGradient(listOf(edge.copy(alpha = .21f), edge.copy(alpha = .08f))), shape)
}
val Ease = CubicBezierEasing(.22f, .8f, .23f, 1f)
fun clock(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(java.util.Locale.ROOT, s / 60, s % 60)
}
@Composable fun Label(text: String, modifier: Modifier = Modifier, color: Color = Paper, size: Int = 14,
    weight: FontWeight = FontWeight.Normal, lines: Int = 1, mono: Boolean = false) {
    Text(text, modifier, color, fontSize = size.sp, fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        letterSpacing = (if (size >= 24) -0.6f else 0f).sp, maxLines = lines, lineHeight = (size + 5).sp, overflow = TextOverflow.Ellipsis)
}
@Composable fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Line.copy(alpha = .68f)))

@Composable internal fun TextTabs(
    labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit
) = PlayerTabs(labels.map { PlayerTab(it) }, selected, modifier, onSelect)

internal data class PlayerTab(val label: String, val icon: String? = null, val count: Int? = null)

@Composable internal fun PlayerTabs(
    tabs: List<PlayerTab>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit
) {
    Box(modifier.selectableGroup()) {
        Hairline(Modifier.align(Alignment.BottomCenter))
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                val color by animateColorAsState(if (active) Accent else Dim, tween(170), label = "tab text")
                val rule by animateColorAsState(if (active) Accent else Color.Transparent, tween(170), label = "tab rule")
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .selectable(active, role = Role.Tab) { onSelect(index) }
                        .then(if (tab.icon == null) Modifier else Modifier.semantics {
                            contentDescription = tab.count?.let { "${tab.label}，$it 个" } ?: tab.label
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    if (tab.icon == null) {
                        Label(tab.label, Modifier.padding(horizontal = 3.dp, vertical = 9.dp), color = color,
                            size = 13, weight = if (active) FontWeight.SemiBold else FontWeight.Medium, lines = 2)
                    } else {
                        Row(Modifier.clearAndSetSemantics {},
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(if (active && tab.icon == "star") "starFilled" else tab.icon, Modifier.size(22.dp), color)
                            tab.count?.let { Label(it.toString(), color = color, size = 12, mono = true) }
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).width(24.dp).height(2.dp).background(rule))
                }
            }
        }
    }
}
@Composable fun IconButton(icon: String, label: String, modifier: Modifier = Modifier, tint: Color = Muted,
    background: Color = Color.Transparent, size: Int = 42, glyphSize: Int = if (size >= 52) 26 else 20, action: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(modifier.size(size.dp).clip(shape).background(background).clickable(onClick = action, role = Role.Button).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) { Glyph(icon, Modifier.size(glyphSize.dp), tint) }
}
@Composable fun Glyph(name: String, modifier: Modifier = Modifier, tint: Color = Muted) {
    Canvas(modifier) {
        val d = size.width / 24f
        fun pt(x: Number, y: Number) = Offset(x.toFloat() * d, y.toFloat() * d)
        fun poly(vararg xy: Number, fill: Boolean = false) {
            val p = Path().apply {
                moveTo(xy[0].toFloat() * d, xy[1].toFloat() * d)
                for (i in 2 until xy.size step 2) lineTo(xy[i].toFloat() * d, xy[i + 1].toFloat() * d)
                if (fill) close()
            }
            if (fill) drawPath(p, tint) else drawPath(p, tint, style = Stroke(1.65f*d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        fun oval(x: Number, y: Number, r: Number, fill: Boolean = true) {
            if (fill) drawCircle(tint, r.toFloat()*d, pt(x,y)) else drawCircle(tint, r.toFloat()*d, pt(x,y), style = Stroke(1.65f*d))
        }
        when (name) {
            "mark", "scope" -> { poly(4,10,4,14); poly(8,7,8,17); poly(12,3,12,21); poly(16,6,16,18); poly(20,10,20,14) }
            "info" -> { oval(12,12,9,false); oval(12,8,1); poly(12,11,12,16) }
            "eq" -> {
                poly(5,3,5,8); poly(5,13,5,21); oval(5,10.5,2.4,false)
                poly(12,3,12,13); poly(12,18,12,21); oval(12,15.5,2.4,false)
                poly(19,3,19,6); poly(19,11,19,21); oval(19,8.5,2.4,false)
            }
            "folder" -> poly(3,7,3,19,21,19,21,8,12,8,10,5,3,5,3,7)
            "library" -> { poly(4,4,4,20); poly(9,4,9,20); poly(14,4,14,20); poly(17,5,21,19) }
            "disc" -> { oval(12,12,9,false); oval(12,12,2,false); poly(7,8,8,7); poly(16,17,17,16) }
            "music" -> { poly(10,17,10,5,20,3,20,15); oval(7.7,17.6,2.8); oval(17.7,15.6,2.8) }
            "filter" -> poly(3,5,21,5,15,12,15,18,9,21,9,12,3,5)
            "search" -> { oval(10.5,10.5,5.7,false); poly(15,15,20,20) }
            "plus" -> { poly(12,5,12,19); poly(5,12,19,12) }
            "left" -> poly(14,5,7,12,14,19)
            "right" -> poly(9,5,16,12,9,19)
            "down" -> poly(5,9,12,16,19,9)
            "up" -> poly(5,15,12,8,19,15)
            "close" -> { poly(6,6,18,18); poly(18,6,6,18) }
            "play" -> poly(7,4,20,12,7,20, fill = true)
            "pause" -> { poly(6,4,10,4,10,20,6,20, fill=true); poly(14,4,18,4,18,20,14,20, fill=true) }
            "previous" -> { poly(17,5,7,12,17,19,fill=true); poly(5,5,5,19) }
            "next" -> { poly(7,5,17,12,7,19,fill=true); poly(19,5,19,19) }
            "vibration" -> {
                poly(8,5,16,5,16,19,8,19,8,5)
                poly(5,7,4,9,4,15,5,17)
                poly(19,7,20,9,20,15,19,17)
            }
            "queue" -> { poly(3,6,21,6); poly(3,12,14,12); poly(3,18,14,18); poly(18,14,22,17,18,20,fill=true) }
            "repeat", "repeat1" -> { poly(4,9,4,6,19,6,16,3); poly(19,6,16,9); poly(20,15,20,18,5,18,8,21); poly(5,18,8,15)
                if (name == "repeat1") poly(11,11,13,10,13,15) }
            "shuffle" -> { poly(3,6,7,6,16,18,21,18); poly(17,14,21,18,17,22); poly(3,18,7,18,10,14); poly(14,9,16,6,21,6); poly(17,2,21,6,17,10) }
            "more" -> { oval(5,12,1.35); oval(12,12,1.35); oval(19,12,1.35) }
            "refresh" -> { poly(20,4,20,9,15,9); poly(20,9,17,5,12,4,7,6,4,11,5,16,9,20,15,20,19,16) }
            "lyrics" -> { poly(4,5,20,5); poly(4,10,20,10); poly(4,15,16,15); poly(4,20,12,20) }
            "star", "starFilled" -> poly(12,3,14.8,8.5,21,9.5,16.5,14,17.5,20,12,17.2,6.5,20,7.5,14,3,9.5,9.2,8.5,12,3, fill = name == "starFilled")
            "check" -> poly(4,12,9,17,20,6)
            "bolt" -> poly(13,2,5,13,11,13,10,22,19,10,13,10,13,2)
            "palette" -> {
                oval(12,12,9,false); oval(8,9,1.3); oval(13,7,1.3); oval(17,11,1.3); oval(8,14,1.3)
                poly(17,18,13,18,12,20)
            }
            "save" -> {
                poly(4,4,17,4,20,7,20,20,4,20,4,4)
                poly(8,4,8,9,16,9,16,4); poly(8,20,8,13,16,13,16,20)
            }
            "help" -> { oval(12,12,9,false); poly(9,9,10,7,13,7,15,9,14,11,12,12,12,13); oval(12,16,1) }
            "skip" -> { oval(12,12,9,false); poly(6,18,18,6) }
            "spark" -> poly(12,3,14,10,21,12,14,14,12,21,10,14,3,12,10,10,12,3)
            "orbit" -> { oval(12,12,7,false); oval(12,12,1.6); oval(19,12,2.1) }
            "prism" -> { poly(12,4,21,19,3,19,12,4); poly(12,4,12,19) }
            "waterfall" -> { poly(4,6,8,6,11,8,20,8); poly(4,11,10,11,14,13,20,13); poly(4,16,8,16,12,18,20,18) }
            "bars" -> { poly(5,19,5,13); poly(10,19,10,5); poly(15,19,15,9); poly(20,19,20,15) }
            "wave" -> poly(2,12,5,12,8,6,12,18,16,7,19,12,22,12)
            "ring" -> { oval(12,12,7,false); poly(12,2,12,5); poly(22,12,19,12); poly(12,22,12,19); poly(2,12,5,12) }
            else -> oval(12,12,6,false)
        }
    }
}

@Composable internal fun panelHeight(): androidx.compose.ui.unit.Dp {
    val height = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    return minOf(800.dp, height * if (height < 500.dp) .90f else .84f)
}
