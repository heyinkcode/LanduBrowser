package com.landu.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.landu.browser.data.BrowserOptions
import com.landu.browser.data.ConfigurationChangedException
import com.landu.browser.data.Environment
import com.landu.browser.data.EnvironmentStore
import com.landu.browser.data.PrivateDocument
import com.landu.browser.data.ProxyAddress
import com.landu.browser.data.UrlPolicy
import com.landu.browser.ui.NativeUi as Ui

class EnvironmentEditorActivity : Activity() {
    private var original: Environment? = null
    private var selectedTab = 0
    private val fields = linkedMapOf<String, EditText>()
    private val checks = linkedMapOf<String, CheckBox>()
    private val choices = linkedMapOf<String, Spinner>()
    private val pages = mutableListOf<ScrollView>()
    private val tabs = mutableListOf<Button>()
    private lateinit var feedback: TextView
    private lateinit var panel: FrameLayout
    private lateinit var store: EnvironmentStore
    private var restored: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restored = savedInstanceState
        try {
            store = EnvironmentStore(PrivateDocument(this))
            val id = intent.getStringExtra(BrowserActivity.ENVIRONMENT_ID)
            original = id?.let { store.find(it) ?: error("环境不存在或正在删除") }
        } catch (_: Exception) { finish(); return }
        val options = original?.options ?: BrowserOptions()
        val root = Ui.column(this)
        val heading = Ui.column(this, 20)
        Ui.add(heading, Ui.text(this, if (original == null) "新建浏览器环境" else "编辑浏览器环境", 24f, medium = true))
        Ui.add(heading, Ui.text(this, "配置保存在本机 · 下次打开此环境时应用", color = Ui.muted), 6)
        Ui.add(root, heading)
        val tabStrip = Ui.row(this).apply { setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), Ui.dp(context, 12)) }
        listOf("基本信息", "网络代理", "浏览设置", "隐私权限").forEachIndexed { index, title ->
            val button = Ui.button(this, title) { showTab(index) }
            tabs.add(button)
            tabStrip.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Ui.dp(this@EnvironmentEditorActivity, 8) })
        }
        Ui.add(root, HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabStrip) })
        panel = FrameLayout(this)
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))

        val basic = page("平台与启动", "同一环境保持稳定的独立 Profile，改名不改变登录数据。")
        field(basic, "name", "环境名称", original?.name.orEmpty(), "例如：工作账号")
        field(basic, "group", "归属分组", original?.group.orEmpty(), "可选，例如：工作 / 个人")
        field(basic, "url", "启动网址", original?.startUrl?.takeUnless { it == "about:blank" }.orEmpty(), "https://example.com；留空为空白页", uri = true)
        field(basic, "notes", "备注", original?.notes.orEmpty(), "最多 500 字，不要填写密码", multiline = true)
        Ui.add(basic, Ui.text(this, "网站账号密码在网站内输入。复制环境仅复制配置，不复制登录状态或网站数据。", color = Ui.muted), 20)

        val network = page("网络与代理", "不需要购买岚渡服务。默认使用手机当前网络；代理由你自行提供。")
        select(network, "network", "连接方式", listOf("手机当前网络（跟随系统）", "手动代理（当前环境）"), if (options.proxyUrl.isEmpty()) 0 else 1)
        val proxy = field(network, "proxy", "代理地址", options.proxyUrl, "http://主机:端口", uri = true)
        Ui.add(network, Ui.text(this, "支持无认证 HTTP / HTTPS / SOCKS5 地址，必须填写端口。账号密码认证、代理库和自动出口检测暂未接入。", color = Ui.muted), 10)
        val proxyConsent = check(network, "proxyConsent", "我了解：这里只配置网页代理，不是 VPN，不能保证 DNS / WebRTC 全部经过代理。", options.proxyLimitationsAccepted)
        Ui.add(network, Ui.text(this, "独立浏览进程只运行当前环境；切换前结束旧进程。代理规则不加入直连兜底，能力缺失或配置失败时阻止打开网页。无法联网时请检查代理，不会自动改为手机直连。", color = Ui.muted), 16)
        choices.getValue("network").onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                proxy.isEnabled = position == 1
                proxyConsent.isEnabled = position == 1
                proxy.alpha = if (position == 1) 1f else 0.5f
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val browser = page("浏览器与显示", "当前引擎为手机系统 WebView；不伪装成另一个操作系统。")
        field(browser, "ua", "User-Agent", options.userAgent, "留空跟随当前 WebView 默认 UA", multiline = true)
        Ui.add(browser, Ui.button(this, "恢复默认 UA") { fields.getValue("ua").setText("") }, 8)
        Ui.add(browser, Ui.text(this, "自定义 UA 会应用到网页请求与 navigator.userAgent；它不会改变真实系统、硬件或保证 Client Hints 一致。", color = Ui.muted), 10)
        check(browser, "wide", "启用宽屏网页布局（不是伪造屏幕分辨率）", options.wideViewport)
        field(browser, "zoom", "网页文字缩放（75–200%）", options.textZoom.toString(), "100", numeric = true)
        field(browser, "fontSize", "网页默认字号（12–32）", options.fontSize.toString(), "16", numeric = true)
        select(browser, "font", "网页默认字体", listOf("无衬线 sans-serif", "衬线 serif", "等宽 monospace"), listOf("sans-serif", "serif", "monospace").indexOf(options.fontFamily))
        Ui.add(browser, Ui.text(this, "这些选项调整网页显示，不改变 App 字体。网页 CSS 可覆盖默认字体/字号；这不是中文字体隔离。", color = Ui.muted), 14)

        val privacy = page("隐私与网页权限", "设置按环境保存；已存数据不会因关闭开关而自动删除。")
        check(privacy, "js", "允许 JavaScript（关闭可能导致网站不可用）", options.javaScript)
        check(privacy, "storage", "允许 DOM Storage（LocalStorage / SessionStorage）", options.domStorage)
        check(privacy, "images", "自动加载网页图片", options.images)
        check(privacy, "thirdParty", "允许第三方 Cookie（默认关闭）", options.thirdPartyCookies)
        check(privacy, "uploads", "允许手动选择上传文件（每次由系统选择器授权）", options.fileUploads)
        Ui.add(privacy, Ui.text(this, "固定安全策略", 18f, medium = true), 22)
        Ui.add(privacy, Ui.text(this, "仅 HTTPS；不忽略证书错误；不向网页暴露管理桥；摄像头、麦克风和定位目前拒绝。应用没有读取整个存储的权限。", color = Ui.muted), 8)
        Ui.add(privacy, Ui.text(this, "尚未移植的桌面能力", 18f, medium = true), 22)
        Ui.add(privacy, Ui.text(this, "语言 / 时区 / 地理位置伪装、WebRTC 防泄露、中文字体白名单、Canvas / WebGL / Audio 噪声、CPU / 内存指纹、扩展、下载和云同步尚未接入。不会给这些能力设置一个无效开关。", color = Ui.muted), 8)

        val footer = Ui.column(this, 16).apply { background = Ui.surface(context, radius = 0) }
        feedback = Ui.text(this, "", color = 0xFFB42318.toInt()).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        Ui.add(footer, feedback)
        val buttons = Ui.row(this)
        Ui.weighted(buttons, Ui.button(this, "取消") { confirmExit() })
        Ui.weighted(buttons, Ui.button(this, "保存环境", true) { save() }, 12)
        Ui.add(footer, buttons, 8)
        Ui.add(root, footer)
        Ui.install(this, root)
        showTab(savedInstanceState?.getInt("tab") ?: 0)
    }

    private fun page(title: String, subtitle: String): LinearLayout {
        val outer = Ui.column(this, 16)
        val card = Ui.column(this, 18).apply { background = Ui.surface(context) }
        Ui.add(card, Ui.text(this, title, 20f, medium = true))
        Ui.add(card, Ui.text(this, subtitle, color = Ui.muted), 8)
        Ui.add(outer, card)
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(outer) }
        pages.add(scroll)
        return card
    }

    private fun field(parent: LinearLayout, key: String, title: String, value: String, hint: String,
        multiline: Boolean = false, numeric: Boolean = false, uri: Boolean = false): EditText {
        val field = Ui.field(this, restored?.getString(key) ?: value, hint).apply {
            if (multiline) {
                isSingleLine = false
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minHeight = Ui.dp(context, 96)
                gravity = Gravity.TOP or Gravity.START
            }
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        fields[key] = field
        Ui.add(parent, Ui.label(this, title, field), 18)
        Ui.add(parent, field, 6)
        return field
    }

    private fun check(parent: LinearLayout, key: String, title: String, value: Boolean): CheckBox {
        val check = Ui.check(this, title, restored?.getBoolean(key) ?: value)
        checks[key] = check
        Ui.add(parent, check, 10)
        return check
    }

    private fun select(parent: LinearLayout, key: String, title: String, values: List<String>, value: Int) {
        val select = Ui.select(this, values, restored?.getInt(key) ?: value)
        choices[key] = select
        Ui.add(parent, Ui.text(this, title, medium = true).apply { labelFor = select.id }, 18)
        Ui.add(parent, select, 6)
    }

    private fun showTab(index: Int) {
        selectedTab = index.coerceIn(0, pages.lastIndex)
        panel.removeAllViews()
        panel.addView(pages[selectedTab], FrameLayout.LayoutParams(-1, -1))
        tabs.forEachIndexed { position, button ->
            button.isSelected = position == selectedTab
            button.setTextColor(if (button.isSelected) Ui.primary else Ui.ink)
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(if (button.isSelected) 0xFFE7F0FF.toInt() else android.graphics.Color.WHITE)
            button.contentDescription = button.text.toString() + if (button.isSelected) "，已选中" else ""
        }
    }

    private fun value(key: String) = fields.getValue(key).text.toString().trim()
    private fun checked(key: String) = checks.getValue(key).isChecked

    private fun validateField(key: String, tab: Int, validate: (String) -> Unit): String {
        val text = value(key)
        try { validate(text) } catch (exception: IllegalArgumentException) {
            showTab(tab)
            fields.getValue(key).apply { error = exception.message; requestFocus() }
            throw exception
        }
        return text
    }

    private fun save() {
        fields.values.forEach { it.error = null }
        try {
            val name = validateField("name", 0) { require(it.isNotBlank() && it.length <= 40) { "环境名称需要 1–40 个字符" } }
            val group = validateField("group", 0) { require(it.length <= 40) { "分组最多 40 字" } }
            val url = validateField("url", 0) { UrlPolicy.normalize(it) }
            val notes = validateField("notes", 0) { require(it.length <= 500) { "备注最多 500 字" } }
            val proxy = if (choices.getValue("network").selectedItemPosition == 0) "" else {
                val input = validateField("proxy", 1) { require(it.isNotEmpty()) { "请填写代理地址" }; ProxyAddress.normalize(it) }
                if (!checked("proxyConsent")) { showTab(1); throw IllegalArgumentException("请勾选代理限制确认，避免误认为全设备流量都已代理") }
                ProxyAddress.normalize(input)
            }
            val userAgent = validateField("ua", 2) { require(it.length <= 1024 && it.all { letter -> letter.code in 32..126 }) { "UA 只能包含最多 1024 个可打印 ASCII 字符" } }
            val zoom = validateField("zoom", 2) { require(it.toIntOrNull() in 75..200) { "文字缩放须为 75–200 的整数" } }.toInt()
            val fontSize = validateField("fontSize", 2) { require(it.toIntOrNull() in 12..32) { "默认字号须为 12–32 的整数" } }.toInt()
            val options = BrowserOptions(proxy, proxy.isNotEmpty() && checked("proxyConsent"), userAgent, checked("wide"), zoom, fontSize,
                listOf("sans-serif", "serif", "monospace")[choices.getValue("font").selectedItemPosition],
                checked("js"), checked("storage"), checked("images"), checked("thirdParty"), checked("uploads"))
            store.save(original?.id, name, group, url, notes, options)
            finish()
        } catch (exception: IllegalArgumentException) {
            feedback.text = exception.message ?: "请检查输入"
            feedback.visibility = View.VISIBLE
        } catch (exception: ConfigurationChangedException) {
            feedback.text = exception.message
            feedback.visibility = View.VISIBLE
        } catch (_: Exception) {
            feedback.text = "保存失败，原有配置未被替换，请检查存储空间后重试"
            feedback.visibility = View.VISIBLE
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this).setTitle("放弃本次编辑？").setMessage("尚未保存的更改不会应用。")
            .setNegativeButton("继续编辑", null).setPositiveButton("放弃") { _, _ -> finish() }.show()
    }

    @Deprecated("Legacy back callback used for API 28 compatibility")
    override fun onBackPressed() = confirmExit()

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        fields.forEach { (key, field) -> state.putString(key, field.text.toString()) }
        checks.forEach { (key, check) -> state.putBoolean(key, check.isChecked) }
        choices.forEach { (key, choice) -> state.putInt(key, choice.selectedItemPosition) }
        state.putInt("tab", selectedTab)
    }
}
