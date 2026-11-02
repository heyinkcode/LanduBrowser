package com.landu.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.landu.browser.browser.BrowserSessionService
import com.landu.browser.browser.SessionConnection
import com.landu.browser.data.Environment
import com.landu.browser.data.EnvironmentStore
import com.landu.browser.data.PrivateDocument
import com.landu.browser.ui.NativeUi as Ui

class MainActivity : Activity() {
    private var store: EnvironmentStore? = null
    private lateinit var connection: SessionConnection
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection = SessionConnection(this, { if (visible) render() }) { result ->
            result.getString("error")?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            result.getStringArrayList("deleted")?.let { deleted ->
                runCatching {
                    EnvironmentStore(PrivateDocument(this)).finishDeletions { profile -> check(profile in deleted) }
                }.onFailure { Toast.makeText(this, "清理状态保存失败，保留待清理标记供重试", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        render()
    }

    override fun onPause() { visible = false; super.onPause() }
    override fun onDestroy() { connection.release(); super.onDestroy() }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val root = Ui.column(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = Ui.column(this, 20)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        Ui.install(this, root)

        Ui.add(content, Ui.text(this, "岚渡浏览器", 26f, medium = true))
        Ui.add(content, Ui.text(this, "本地环境 · 用户自己的网络", color = Ui.muted), 6)
        val status = Ui.column(this, 16).apply { background = Ui.surface(context) }
        Ui.add(status, Ui.text(this, if (connection.busy) "浏览进程正在运行" else "环境管理已就绪", medium = true))
        Ui.add(status, Ui.text(this, if (connection.busy)
            "请先从正在浏览的环境返回，待进程结束后再打开或清理其他环境。"
        else "设置按环境保存，启动时检查内核能力。不连接管理后台，不提供代理线路。", color = Ui.muted), 8)
        Ui.add(content, status, 20)

        try {
            store = EnvironmentStore(PrivateDocument(this))
        } catch (_: Exception) {
            store = null
            Ui.add(content, Ui.text(this, "无法读取本地配置。为保护已有数据，已停止写入；请勿卸载或清除应用数据。"), 20)
            Ui.add(content, Ui.button(this, "重新读取") { render() }, 16)
            return
        }

        val actions = Ui.row(this)
        Ui.weighted(actions, Ui.button(this, "＋ 新建环境", true) { edit(null) }.apply { isEnabled = !connection.busy })
        Ui.weighted(actions, Ui.button(this, "使用说明") { about() }, 12)
        Ui.add(content, actions, 20)
        val environments = store!!.all()
        if (environments.any { it.pendingDeletion }) {
            Ui.add(content, Ui.button(this, "重试清理待删除环境") { connection.request(BrowserSessionService.CLEAN) }.apply { isEnabled = !connection.busy }, 12)
        }
        Ui.add(content, Ui.text(this, "我的环境  ${environments.count { !it.pendingDeletion }}", 18f, medium = true), 24)
        if (environments.isEmpty()) {
            val empty = Ui.column(this, 20).apply { background = Ui.surface(context) }
            Ui.add(empty, Ui.text(this, "从第一个环境开始", 18f, medium = true))
            Ui.add(empty, Ui.text(this, "先配置启动页、网络代理、UA 与隐私选项，再打开网站。登录数据存放在本机，不会自动同步到电脑或云端。", color = Ui.muted), 8)
            Ui.add(content, empty, 12)
        }
        environments.forEach { environment ->
            val card = Ui.column(this, 16).apply { background = Ui.surface(context) }
            Ui.add(card, Ui.text(this, environment.name, 18f, medium = true))
            Ui.add(card, Ui.text(this, "${environment.group.ifBlank { "未分组" }} · ${if (environment.options.proxyUrl.isEmpty()) "手机当前网络" else "手动代理"}", color = Ui.muted), 6)
            if (environment.pendingDeletion) {
                Ui.add(card, Ui.text(this, "等待清理网站数据；暂不能打开。请关闭浏览环境后点击重试清理，不要清除整个应用数据。", color = Ui.muted), 12)
            } else {
                Ui.add(card, Ui.text(this, if (environment.startUrl == "about:blank") "启动页：空白页" else environment.startUrl, color = Ui.muted).apply {
                    maxLines = 2
                }, 8)
                Ui.add(card, Ui.text(this, "${if (environment.options.userAgent.isEmpty()) "默认 UA" else "自定义 UA"} · 文字 ${environment.options.textZoom}% · 第三方 Cookie ${if (environment.options.thirdPartyCookies) "允许" else "阻止"}", color = Ui.muted), 6)
                if (environment.notes.isNotEmpty()) Ui.add(card, Ui.text(this, environment.notes, color = Ui.muted).apply { maxLines = 2 }, 6)
                val buttons = Ui.row(this)
                Ui.weighted(buttons, Ui.button(this, "打开", true) {
                    connection.request(BrowserSessionService.OPEN, environment.id)
                }.apply { isEnabled = !connection.busy })
                Ui.weighted(buttons, Ui.button(this, "设置") { edit(environment) }.apply { isEnabled = !connection.busy }, 8)
                Ui.weighted(buttons, Ui.button(this, "更多") {
                    AlertDialog.Builder(this).setTitle(environment.name).setItems(arrayOf("复制配置（不复制网站数据）", "删除环境")) { _, choice ->
                        if (choice == 0) {
                            runCatching { store!!.duplicate(environment.id) }.onFailure { Toast.makeText(this, it.message ?: "复制失败", Toast.LENGTH_LONG).show() }
                            render()
                        } else confirmDeletion(environment)
                    }.show()
                }.apply { isEnabled = !connection.busy }, 8)
                Ui.add(card, buttons, 16)
            }
            Ui.add(content, card, 12)
        }
        Ui.add(content, Ui.text(this, "免费使用 · 无需岚渡账号\nAndroid 本地原型 ${BuildConfig.VERSION_NAME}", color = Ui.muted), 24)
    }

    private fun edit(environment: Environment?) {
        startActivity(Intent(this, EnvironmentEditorActivity::class.java).apply {
            environment?.let { putExtra(BrowserActivity.ENVIRONMENT_ID, it.id) }
        })
    }

    private fun confirmDeletion(environment: Environment) {
        AlertDialog.Builder(this)
            .setTitle("删除“${environment.name}”？")
            .setMessage("将删除此环境及其本地网站数据，无法撤销；不会删除其他环境。若内核仍占用数据，会标记为待清理，并在应用进程重新启动后重试。")
            .setNegativeButton("保留", null)
            .setPositiveButton("删除") { _, _ ->
                try {
                    store!!.requestDeletion(environment.id)
                    connection.request(BrowserSessionService.CLEAN)
                } catch (_: Exception) {
                    Toast.makeText(this, "删除未完成，请重试；不会清理其他环境", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("本地运行，不需要服务器")
            .setMessage("① 新建环境，在四个设置分区配置网络、UA、显示与隐私。\n② 打开环境，在网站内登录；右上角可查看当前配置。\n③ 返回列表，旧浏览进程退出后打开另一个环境。\n\n代理由用户提供，仅支持无认证 HTTP / HTTPS / SOCKS5；不是 VPN，不保证 DNS / WebRTC 全部经过代理。文件上传需每次手动选择。\n\n认证代理、桌面指纹、扩展、下载和云同步尚未移植；请先用测试账号验证。\n\n岚渡桌面版基于 sheying2013/OpenBrowser，感谢原作者和上游贡献者。安卓为新增适配工程，系统 WebView 和 AndroidX 适用各自许可证。")
            .setPositiveButton("知道了", null).show()
    }
}
