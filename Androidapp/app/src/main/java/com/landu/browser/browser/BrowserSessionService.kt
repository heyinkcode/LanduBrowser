package com.landu.browser.browser

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.landu.browser.data.EnvironmentStore
import com.landu.browser.data.PrivateDocument

class BrowserSessionService : Service() {
    private val handler: Handler = Handler(Looper.getMainLooper()) { request ->
        var terminate = false
        val result = Bundle()
        try {
            check(!lease.closing) { "旧浏览进程正在退出，请稍后重试" }
            check(LocalBrowser.supported()) { "系统 WebView 不支持多环境隔离，请更新后重试" }
            val store = EnvironmentStore(PrivateDocument(this))
            if (request.what == OPEN) {
                val id = request.data.getString("id").orEmpty()
                check(store.find(id) != null) { "环境不存在或正在清理" }
                lease.claim(id)
                handler.postDelayed({ if (!lease.attached) shutdown() }, 15000)
                result.putString("id", id)
            } else if (request.what == CLEAN) {
                lease.requireIdle()
                terminate = true
                val deleted = arrayListOf<String>()
                store.all().filter { it.pendingDeletion }.forEach { environment ->
                    if (runCatching { LocalBrowser.delete(environment.profileName) }.isSuccess) deleted.add(environment.profileName)
                }
                result.putStringArrayList("deleted", deleted)
            } else error("未知操作")
        } catch (exception: Exception) {
            result.putString("error", exception.message ?: "无法打开本地环境")
            terminate = activeEnvironment == null
        }
        runCatching { request.replyTo?.send(Message.obtain(null, request.what).apply { data = result; arg1 = request.arg1 }) }
        if (terminate) shutdown()
        true
    }
    private val messenger = Messenger(handler)
    override fun onBind(intent: Intent) = messenger.binder
    override fun onUnbind(intent: Intent): Boolean {
        if (!lease.attached) shutdown()
        return false
    }

    private fun shutdown() {
        if (lease.closing) return
        lease.shutdown()
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 150)
    }

    companion object {
        const val OPEN = 1
        const val CLEAN = 2
        val lease = SessionLease()
        val activeEnvironment: String? get() = lease.environmentId
    }
}
