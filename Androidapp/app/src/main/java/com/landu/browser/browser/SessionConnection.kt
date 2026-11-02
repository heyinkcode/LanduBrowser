package com.landu.browser.browser

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.landu.browser.BrowserActivity

class SessionConnection(private val activity: Activity, private val changed: () -> Unit,
    private val result: (Bundle) -> Unit) : ServiceConnection {
    var busy = false
        private set
    private var bound = false
    private var action = 0
    private var sequence = 0
    private var environmentId = ""
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { result(Bundle().apply { putString("error", "浏览进程响应超时，请重试") }); release(); changed() }
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { response ->
        if (!bound || !busy || response.arg1 != sequence || response.what != action || activity.isDestroyed || activity.isFinishing) return@Handler true
        handler.removeCallbacks(timeout)
        if (response.data.getString("error") == null && action == BrowserSessionService.OPEN) {
            try {
                check(response.data.getString("id") == environmentId)
                activity.startActivity(Intent(activity, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.ENVIRONMENT_ID, environmentId)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (_: Exception) {
                response.data.putString("error", "未能打开浏览窗口，请返回列表重试")
            }
        }
        result(response.data)
        if (response.data.getString("error") != null) { release(); changed() }
        true
    })

    fun request(command: Int, id: String = "") {
        if (busy) return
        sequence++
        action = command
        environmentId = id
        busy = true
        bound = runCatching { activity.bindService(Intent(activity, BrowserSessionService::class.java), this, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) {
            busy = false
            result(Bundle().apply { putString("error", "无法启动本地浏览进程") })
        } else handler.postDelayed(timeout, 15000)
        changed()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        if (!bound || !busy) return
        try {
            Messenger(binder).send(Message.obtain(null, action).apply {
                arg1 = sequence
                replyTo = receiver
                data = Bundle().apply { putString("id", environmentId) }
            })
        } catch (_: Exception) { timeout.run() }
    }

    override fun onServiceDisconnected(name: ComponentName) { release(); changed() }
    override fun onBindingDied(name: ComponentName) { release(); changed() }
    override fun onNullBinding(name: ComponentName) { timeout.run() }

    fun release() {
        handler.removeCallbacks(timeout)
        if (bound) runCatching { activity.unbindService(this) }
        bound = false
        busy = false
    }
}
