package com.overtone.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

internal enum class UsbState(val text: String) {
    OFF("关闭"), OLD_ANDROID("需要 Android 14 或以上"), NO_DEVICE("未连接 USB DAC"),
    EQ("EQ 已开启"), WAITING("等待播放"), SOURCE("无法确认音源位深"),
    NO_MATCH("当前格式不支持逐位输出"), UNAVAILABLE("逐位路由未建立，已用系统输出"),
    ROUTING("正在确认 USB 路由"), ACTIVE("逐位输出")
}

internal data class UsbStatus(val state: UsbState, val device: String? = null, val output: PcmPath? = null)

internal object UsbSettings {
    private val flow = MutableStateFlow(false)
    val requested = flow.asStateFlow()
    fun init(context: Context) {
        flow.value = context.getSharedPreferences("usb_output", Context.MODE_PRIVATE).getBoolean("enabled", false)
    }
    fun set(context: Context, requested: Boolean) {
        context.getSharedPreferences("usb_output", Context.MODE_PRIVATE).edit().putBoolean("enabled", requested).apply()
        flow.value = requested
    }
}

internal fun AudioDeviceInfo.isUsbOutput() = isSink && type in setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY
)

internal class UsbOutputCoordinator(context: Context, private val restart: (unplugged: Boolean) -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val failed = AtomicBoolean()
    private var lastPreference: Pair<Boolean, Boolean>? = null
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (added.any { it.isUsbOutput() }) refresh(false)
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.isUsbOutput() }) refresh(true)
        }
    }
    init {
        manager.registerAudioDeviceCallback(callback, handler)
        idle()
    }

    fun preferencesChanged(enabled: Boolean, eq: Boolean) {
        val preference = enabled to eq
        val previous = lastPreference
        if (previous == preference) return
        val active = AudioPath.info.value.usb.state in setOf(UsbState.ACTIVE, UsbState.ROUTING)
        lastPreference = preference
        failed.set(false)
        idle()
        if (previous != null && (enabled || previous.first) && Build.VERSION.SDK_INT >= 34 &&
            (active || usbDevices().isNotEmpty())) restart(false)
    }

    private fun refresh(removed: Boolean) {
        val wasDirect = AudioPath.info.value.usb.state in setOf(UsbState.ACTIVE, UsbState.ROUTING)
        failed.set(false)
        idle()
        if (UsbSettings.requested.value && Build.VERSION.SDK_INT >= 34) restart(removed && wasDirect)
    }

    private fun usbDevices() = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isUsbOutput() }.toTypedArray()

    fun initialState(): UsbState = when {
        !UsbSettings.requested.value -> UsbState.OFF
        Build.VERSION.SDK_INT < 34 -> UsbState.OLD_ANDROID
        usbDevices().isEmpty() -> UsbState.NO_DEVICE
        EqStore.parameters.enabled -> UsbState.EQ
        failed.get() -> UsbState.UNAVAILABLE
        else -> UsbState.WAITING
    }

    fun idle() { AudioPath.usb(UsbStatus(initialState())) }

    fun open(source: Format?, format: Format, sessionId: Int): UsbDirectTrack? {
        val state = initialState()
        if (state != UsbState.WAITING || Build.VERSION.SDK_INT < 34) {
            AudioPath.usb(UsbStatus(state))
            return null
        }
        val bits = exactSourceBits(source, format)
        if (bits == null || format.encoderDelay != 0 || format.encoderPadding != 0 ||
            (source != null && (source.encoderDelay != 0 || source.encoderPadding != 0))) {
            AudioPath.usb(UsbStatus(UsbState.SOURCE))
            return null
        }
        val result = try {
            UsbDirectTrack.open(manager, usbDevices(), format, bits, sessionId)
        } catch (_: RuntimeException) {
            UsbDirectTrack.Result(null, UsbState.UNAVAILABLE)
        }
        AudioPath.usb(UsbStatus(result.state, result.track?.name))
        return result.track
    }

    fun failed() {
        AudioPath.usb(UsbStatus(UsbState.UNAVAILABLE))
        if (failed.compareAndSet(false, true)) handler.post { restart(false) }
    }

    fun release() {
        manager.unregisterAudioDeviceCallback(callback)
        handler.removeCallbacksAndMessages(null)
    }
}

internal fun usbChannelMask(channels: Int): Int = when (channels) {
    1 -> AudioFormat.CHANNEL_OUT_MONO
    2 -> AudioFormat.CHANNEL_OUT_STEREO
    6 -> AudioFormat.CHANNEL_OUT_5POINT1
    8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    else -> 0
}
