package com.overtone.player

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class EqPreset(val name: String, val settings: EqSettings)

internal object EqStore {
    private val settingsFlow = MutableStateFlow(EqSettings())
    private val presetsFlow = MutableStateFlow<List<EqPreset>>(emptyList())
    private val sampleRateFlow = MutableStateFlow(48000)
    val settings = settingsFlow.asStateFlow()
    val presets = presetsFlow.asStateFlow()
    val sampleRate = sampleRateFlow.asStateFlow()
    @Volatile var parameters = EqSettings().parameters()
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSave = Runnable { persist() }
    private var storage: SharedPreferences? = null

    fun init(context: Context) {
        if (storage != null) return
        val prefs = context.applicationContext.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
        storage = prefs
        runCatching {
            val raw = JSONObject(prefs.getString("settings", "{}").orEmpty())
            set(loadSettings(raw))
        }
        runCatching {
            val raw = JSONArray(prefs.getString("presets", "[]"))
            presetsFlow.value = (0 until minOf(raw.length(), 32)).mapNotNull { index ->
                runCatching {
                    val item = raw.getJSONObject(index)
                    EqPreset(item.getString("name"), loadSettings(item.getJSONObject("settings")))
                }.getOrNull()
            }
        }
    }

    fun setSampleRate(rate: Int) { sampleRateFlow.value = rate }

    private fun set(settings: EqSettings) {
        parameters = settings.parameters()
        settingsFlow.value = settings
    }

    fun change(update: (EqSettings) -> EqSettings) {
        val next = update(settingsFlow.value)
        if (next == settingsFlow.value) return
        set(next)
        handler.removeCallbacks(pendingSave)
        handler.postDelayed(pendingSave, 220)
    }

    fun applyPreset(preset: EqPreset) {
        val selected = preset.settings
        change { old ->
            when (selected.mode) {
                EqMode.SIMPLE -> old.copy(enabled = true, mode = selected.mode, low = selected.low,
                    mid = selected.mid, high = selected.high, autoHeadroom = selected.autoHeadroom)
                EqMode.PROFESSIONAL -> old.copy(enabled = true, mode = selected.mode, bands = selected.bands,
                    preamp = selected.preamp, autoHeadroom = selected.autoHeadroom)
            }
        }
        persist()
    }

    fun savePreset(name: String) {
        val trimmed = name.trim().take(28)
        if (trimmed.isEmpty()) return
        val existing = presetsFlow.value.filterNot { it.name == trimmed }
        presetsFlow.value = (existing + EqPreset(trimmed, settings.value)).takeLast(32)
        persist()
    }

    fun deletePreset(name: String) {
        presetsFlow.value = presetsFlow.value.filterNot { it.name == name }
        persist()
    }

    fun persist() {
        handler.removeCallbacks(pendingSave)
        storage?.edit()
            ?.putString("settings", encode(settingsFlow.value).toString())
            ?.putString("presets", JSONArray(presetsFlow.value.map {
                JSONObject().put("name", it.name).put("settings", encode(it.settings))
            }).toString())
            ?.apply()
    }

    private fun encode(value: EqSettings) = JSONObject().apply {
        put("enabled", value.enabled)
        put("mode", value.mode.name)
        put("low", value.low)
        put("mid", value.mid)
        put("high", value.high)
        put("preamp", value.preamp)
        put("autoHeadroom", value.autoHeadroom)
        put("bands", JSONArray(value.bands.map { band ->
            JSONObject().put("frequency", band.frequency).put("gain", band.gain).put("q", band.q)
                .put("type", band.type.name).put("enabled", band.enabled)
        }))
    }

    private fun loadSettings(raw: JSONObject): EqSettings {
        val base = EqSettings()
        val saved = raw.optJSONArray("bands")
        fun JSONObject.number(key: String, default: Double, min: Double, max: Double) =
            optDouble(key, default).takeIf(Double::isFinite)?.coerceIn(min, max) ?: default
        val bands = base.bands.mapIndexed { index, band ->
            val item = saved?.optJSONObject(index) ?: return@mapIndexed band
            val type = FilterType.entries.find { it.name == item.optString("type") } ?: band.type
            EqBand(item.number("frequency", band.frequency, 20.0, 20000.0), item.number("gain", 0.0, -18.0, 18.0),
                item.number("q", 1.0, if (type.isShelf) .2 else .1, if (type.isShelf) 1.0 else 18.0),
                type, item.optBoolean("enabled", true))
        }
        return EqSettings(raw.optBoolean("enabled"), EqMode.entries.find { it.name == raw.optString("mode") } ?: base.mode,
            raw.number("low", 0.0, -12.0, 12.0), raw.number("mid", 0.0, -12.0, 12.0),
            raw.number("high", 0.0, -12.0, 12.0), bands, raw.number("preamp", 0.0, -24.0, 12.0),
            raw.optBoolean("autoHeadroom", true))
    }
}

internal val builtinEqPresets = listOf(
    EqPreset("平直", EqSettings()),
    EqPreset("摇滚", EqSettings(low = 4.0, mid = -2.0, high = 3.5)),
    EqPreset("重低音", EqSettings(low = 8.0, mid = -1.0, high = .5)),
    EqPreset("流行", EqSettings(low = 2.0, mid = 2.5, high = 1.5)),
    EqPreset("人声", EqSettings(low = -2.0, mid = 3.5, high = 1.0)),
    EqPreset("爵士", EqSettings(low = 2.5, mid = -.5, high = 2.0)),
    EqPreset("古典", EqSettings(low = 1.5, mid = 0.0, high = 2.5)),
    EqPreset("低音量", EqSettings(low = 4.0, mid = -1.0, high = 3.0)),
)
