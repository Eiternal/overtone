package com.overtone.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EqualizerSheet(close: () -> Unit) {
    val settings by EqStore.settings.collectAsState()
    val presets by EqStore.presets.collectAsState()
    val rate by EqStore.sampleRate.collectAsState()
    val parameters = remember(settings) { settings.parameters() }
    val plan = remember(parameters, rate) { parameters.compile(rate) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var presetOpen by remember { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    val maxBody = panelHeight()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val filter = settings.bands[index]

    fun changeBand(change: (EqBand) -> EqBand) {
        EqStore.change { current ->
            val next = change(current.bands[index])
            current.copy(enabled = true, bands = current.bands.toMutableList().apply { set(index, next) })
        }
    }

    ModalBottomSheet(onDismissRequest = { EqStore.persist(); close() }, sheetState = sheetState,
        containerColor = Panel, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = {}) {
        DarkSheetBars()
        Column(Modifier.fillMaxWidth().heightIn(max = maxBody)) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 13.dp, top = 13.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Label("均衡器", Modifier.weight(1f), size = 23, weight = FontWeight.SemiBold)
                Switch(settings.enabled, { enabled -> EqStore.change { it.copy(enabled = enabled) }; EqStore.persist() },
                    Modifier.semantics { contentDescription = "启用均衡器" },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent,
                        uncheckedThumbColor = Muted, uncheckedTrackColor = Raised, uncheckedBorderColor = Line))
                Spacer(Modifier.width(5.dp))
                IconButton("close", "关闭均衡器", size = 44) { EqStore.persist(); close() }
            }
            val scroll = rememberScrollState()
            LaunchedEffect(settings.mode) { scroll.scrollTo(0) }
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 21.dp)) {
                TextTabs(listOf("简单", "专业"), settings.mode.ordinal, Modifier.fillMaxWidth()) { index ->
                    EqStore.change { it.copy(mode = EqMode.entries[index]) }
                }
                if (settings.mode == EqMode.SIMPLE) {
                    Spacer(Modifier.height(11.dp))
                    builtinEqPresets.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            row.forEach { preset ->
                                val target = preset.settings
                                val active = settings.enabled && settings.low == target.low && settings.mid == target.mid &&
                                    settings.high == target.high
                                Box(Modifier.weight(1f).height(41.dp).clip(RoundedCornerShape(19.dp))
                                    .background(if (active) AccentWell else Color.Transparent)
                                    .semantics { selected = active }
                                    .clickable { EqStore.applyPreset(preset) }, contentAlignment = Alignment.Center) {
                                    Label(preset.name, color = if (active) Accent else Muted, size = 13)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                EqGraph(plan, rate, settings.enabled, parameters.bands)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 49.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton("library", "打开预设", size = 48) { presetOpen = true }
                    IconButton("save", "保存预设", size = 48) { saveOpen = true }
                    Spacer(Modifier.weight(1f))
                    IconButton("refresh", "重置当前模式", tint = Muted, size = 48) {
                        EqStore.change(EqSettings::resetMode)
                        EqStore.persist()
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (settings.mode == EqMode.SIMPLE) {
                    Parameter("低频", settings.low, -12.0, 12.0, .5, "dB") { value ->
                        EqStore.change { it.copy(enabled = true, low = value) }
                    }
                    Parameter("中频", settings.mid, -12.0, 12.0, .5, "dB") { value ->
                        EqStore.change { it.copy(enabled = true, mid = value) }
                    }
                    Parameter("高频", settings.high, -12.0, 12.0, .5, "dB") { value ->
                        EqStore.change { it.copy(enabled = true, high = value) }
                    }
                } else {
                    repeat(2) { row ->
                        if (row > 0) Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (number in row * 5 until row * 5 + 5) {
                                val active = number == index
                                Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (active) AccentWell else Color.Transparent)
                                    .semantics { selected = active; contentDescription = "第 ${number + 1} 段" }
                                    .clickable { index = number }, contentAlignment = Alignment.Center) {
                                    val band = settings.bands[number]
                                    Label((number + 1).toString(), size = 13,
                                        color = if (active) Accent else if (band.enabled) Paper else Dim, mono = true)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        var typeMenu by remember { mutableStateOf(false) }
                        Box {
                            Chip(filter.type.label, "down") { typeMenu = true }
                            DropdownMenu(typeMenu, { typeMenu = false }, containerColor = Panel) {
                                FilterType.entries.forEach { type ->
                                    DropdownMenuItem(text = { Label(type.label) }, onClick = {
                                        typeMenu = false
                                        changeBand { band ->
                                            val q = if (type.isShelf) band.q.coerceIn(.2, 1.0) else band.q.coerceIn(.1, 18.0)
                                            band.copy(type = type, q = q)
                                        }
                                    })
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Switch(filter.enabled, { enabled ->
                            changeBand { it.copy(enabled = enabled) }
                        }, Modifier.semantics { contentDescription = "启用当前频段" })
                    }
                    key(index) {
                        Parameter("频率", filter.frequency, 20.0, 20000.0, .1, "Hz", logarithmic = true) { value ->
                            changeBand { it.copy(frequency = value) }
                        }
                        if (filter.frequency >= rate * .495) Label("超出当前采样率范围", Modifier.padding(bottom = 6.dp), color = Muted, size = 12)
                        if (filter.type.hasGain) Parameter("增益", filter.gain, -18.0, 18.0, .1, "dB") { value ->
                            changeBand { it.copy(gain = value) }
                        }
                        Parameter(if (filter.type.isShelf) "斜率 S" else "Q", filter.q,
                            if (filter.type.isShelf) .2 else .1, if (filter.type.isShelf) 1.0 else 18.0,
                            .01, "", logarithmic = !filter.type.isShelf) { value ->
                            changeBand { it.copy(q = value) }
                        }
                    }
                    Parameter("前级增益", settings.preamp, -24.0, 12.0, .1, "dB") { value ->
                        EqStore.change { it.copy(enabled = true, preamp = value) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 49.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("自动余量", Modifier.weight(1f), size = 14)
                    if (plan.trim > 0.0) Label(db(-plan.trim), Modifier.padding(end = 10.dp), color = Muted, size = 12, mono = true)
                    Switch(settings.autoHeadroom, { enabled -> EqStore.change { it.copy(autoHeadroom = enabled) } },
                        Modifier.semantics { contentDescription = "自动余量" },
                        colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Accent,
                            uncheckedThumbColor = Muted, uncheckedTrackColor = Raised, uncheckedBorderColor = Line))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (presetOpen) AlertDialog(onDismissRequest = { presetOpen = false },
        title = { Label("预设", size = 20, weight = FontWeight.Medium) },
        text = {
            Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
                if (presets.isEmpty()) Label("暂无预设", color = Muted, size = 14)
                presets.forEach { preset ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(preset.name, Modifier.weight(1f).clickable {
                            EqStore.applyPreset(preset)
                            presetOpen = false
                        }.padding(vertical = 11.dp), size = 14)
                        IconButton("close", "删除预设 ${preset.name}", size = 40) {
                            EqStore.deletePreset(preset.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { presetOpen = false }) { Text("关闭") } })
    if (saveOpen) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saveOpen = false }, title = { Label("保存预设", size = 20) },
            text = {
                OutlinedTextField(name, { name = it.take(28) }, singleLine = true, label = { Text("名称") })
            },
            confirmButton = {
                TextButton(onClick = { EqStore.savePreset(name); saveOpen = false }, enabled = name.isNotBlank()) {
                    Text(if (presets.any { it.name == name.trim() }) "替换" else "保存")
                }
            },
            dismissButton = { TextButton(onClick = { saveOpen = false }) { Text("取消") } })
    }
}

@Composable internal fun DarkSheetBars() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (window != null) {
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            insets.isAppearanceLightNavigationBars = false
            insets.isAppearanceLightStatusBars = false
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
}

@Composable private fun Chip(text: String, icon: String? = null, action: () -> Unit) {
    Row(Modifier.height(39.dp).frosted(20, .36f).clickable(onClick = action).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Label(text, size = 13)
        if (icon != null) Glyph(icon, Modifier.size(12.dp), Muted)
    }
}

private fun db(value: Double) = String.format(Locale.ROOT, "%+.1f dB", if (abs(value) < .05) 0.0 else value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Parameter(label: String, value: Double, min: Double, max: Double, step: Double,
    unit: String, logarithmic: Boolean = false, onChange: (Double) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    fun fromSlider(position: Double): Double = if (logarithmic) min * (max / min).pow(position) else min + (max - min) * position
    val position = if (logarithmic) ln(value / min) / ln(max / min) else (value - min) / (max - min)
    val valueLabel = when (unit) {
        "dB" -> db(value)
        "Hz" -> String.format(Locale.ROOT, "%.1f Hz", value)
        else -> String.format(Locale.ROOT, "%.2f", value)
    }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.width(64.dp), color = Paper, size = 13)
        Slider(position.toFloat().coerceIn(0f, 1f), { new ->
            val rounded = round(fromSlider(new.toDouble()) / step) * step
            onChange(rounded.coerceIn(min, max))
        }, Modifier.weight(1f).semantics { contentDescription = label + " " + valueLabel },
            onValueChangeFinished = { EqStore.persist() },
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Raised),
            thumb = { Box(Modifier.size(12.dp).background(Accent, RoundedCornerShape(50))) },
            track = { state ->
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Raised)) {
                    Box(Modifier.fillMaxWidth(state.value.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                }
            })
        Box(Modifier.width(94.dp).heightIn(min = 41.dp).clip(RoundedCornerShape(10.dp)).clickable { editing = true },
            contentAlignment = Alignment.CenterEnd) {
            Label(valueLabel, Modifier.padding(end = 3.dp), color = Accent, size = 12, mono = true)
        }
    }
    if (editing) {
        var text by rememberSaveable { mutableStateOf(String.format(Locale.ROOT, if (step < .1) "%.2f" else "%.1f", value)) }
        val parsed = text.trim().replace(',', '.').toDoubleOrNull()
        val valid = parsed != null && parsed.isFinite() && parsed in min..max
        AlertDialog(onDismissRequest = { editing = false }, title = { Label(label, size = 20) },
            text = {
                OutlinedTextField(text, { text = it.take(16) }, label = { Text("$min – $max $unit".trim()) },
                    singleLine = true, isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = if (min < 0) KeyboardType.Text else KeyboardType.Decimal))
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    if (parsed != null) { onChange(parsed); EqStore.persist() }
                    editing = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
}

private val frequencyMarks = listOf(20 to "20 Hz", 100 to "100", 1000 to "1k", 10000 to "10k", 20000 to "20k")
private fun frequencyX(frequency: Double) = (ln(frequency / 20.0) / ln(1000.0)).toFloat()

@Composable private fun EqGraph(plan: EqPlan, rate: Int, enabled: Boolean, bands: List<EqBand>) {
    val topFrequency = minOf(20000.0, rate * .495)
    val data = remember(plan, rate, bands) {
        val samples = (0..196).map { index -> 20 * 1000.0.pow(index / 196.0) }
        val centers = bands.filter { it.enabled }.map(EqBand::frequency)
        (samples + centers + topFrequency).filter { it <= topFrequency }.distinct().sorted()
            .map { frequencyX(it) to plan.response(it) }
    }
    val scale = remember(data) { (ceil((data.maxOfOrNull { abs(it.second) } ?: 0.0) / 6) * 6).coerceIn(12.0, 36.0) }
    val ink = Accent
    val grid = Line
    Column(Modifier.fillMaxWidth().frosted(19, .44f).padding(top = 10.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(125.dp).padding(end = 14.dp)) {
            Column(Modifier.width(36.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Label("+${scale.toInt()}", size = 10, color = Dim, mono = true)
                Label("0", size = 10, color = Dim, mono = true)
                Label("−${scale.toInt()}", size = 10, color = Dim, mono = true)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(vertical = 7.dp)) {
                val w = size.width
                val h = size.height
                for ((frequency, _) in frequencyMarks) {
                    val x = w * frequencyX(frequency.toDouble())
                    drawLine(grid, Offset(x, 0f), Offset(x, h))
                }
                for (i in 0..4) {
                    val y = h * i / 4
                    drawLine(if (i == 2) grid.copy(alpha = 1f) else grid.copy(alpha = .64f), Offset(0f, y), Offset(w, y))
                }
                val points = data.map { (x, response) ->
                    Offset(w * x, h * (.5 - (response / scale).coerceIn(-1.0, 1.0) / 2).toFloat())
                }
                if (points.isEmpty()) return@Canvas
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                val fill = Path().apply {
                    addPath(line); lineTo(points.last().x, h); lineTo(0f, h); close()
                }
                if (enabled) drawPath(fill, Brush.verticalGradient(listOf(ink.copy(alpha = .22f), ink.copy(alpha = .02f))))
                drawPath(line, ink.copy(alpha = if (enabled) 1f else .45f), style = Stroke(1.6.dp.toPx()))
            }
        }
        Layout(content = {
            for ((_, tick) in frequencyMarks) Label(tick, color = Dim, size = 10, mono = true)
        }, modifier = Modifier.fillMaxWidth().padding(start = 34.dp, end = 13.dp)) { measurables, constraints ->
            val labels = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            layout(constraints.maxWidth, labels.maxOf { it.height }) {
                labels.forEachIndexed { index, label ->
                    val fraction = frequencyX(frequencyMarks[index].first.toDouble())
                    val x = (constraints.maxWidth * fraction - label.width / 2f).roundToInt()
                    label.placeRelative(x.coerceIn(0, constraints.maxWidth - label.width), 0)
                }
            }
        }
    }
}
