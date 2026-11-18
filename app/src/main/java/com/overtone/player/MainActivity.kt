package com.overtone.player

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val remote = mutableStateOf<MediaController?>(null)
    private var connection: ListenableFuture<MediaController>? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EqStore.init(this)
        UsbSettings.init(this)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { Overtone(remote.value) }
    }
    override fun onStart() {
        super.onStart()
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        connection = future
        future.addListener({
            if (connection === future) runCatching { future.get() }
                .onSuccess { remote.value = it }
                .onFailure { Toast.makeText(this, "播放器连接失败，请重新打开应用", Toast.LENGTH_LONG).show() }
        }, mainExecutor)
    }
    override fun onStop() {
        EqStore.persist()
        remote.value = null
        connection?.let { MediaController.releaseFuture(it) }
        connection = null
        Signal.listening = false
        Signal.clear()
        super.onStop()
    }
}
