package com.landu.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.PermissionRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.landu.browser.browser.LocalBrowser
import com.landu.browser.browser.BrowserSessionService
import com.landu.browser.data.Environment
import com.landu.browser.data.EnvironmentStore
import com.landu.browser.data.PrivateDocument
import com.landu.browser.data.UrlPolicy
import com.landu.browser.ui.NativeUi as Ui

class BrowserActivity : Activity() {
    private var browser: WebView? = null
    private lateinit var environment: Environment
    private lateinit var address: EditText
    private lateinit var feedback: TextView
    private lateinit var progress: ProgressBar
    private lateinit var viewport: FrameLayout
    private lateinit var previous: Button
    private lateinit var next: Button
    private val handler = Handler(Looper.getMainLooper())
    private var opening = false
    private var generation = 0
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val networkTimeout = Runnable {
        opening = false
        generation++
        progress.visibility = View.GONE
        message("网络设置未能完成，已阻止加载网页；请退出检查配置后重试")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selected = runCatching {
            EnvironmentStore(PrivateDocument(this)).find(intent.getStringExtra(ENVIRONMENT_ID).orEmpty())
        }.getOrNull()
        if (selected == null || BrowserSessionService.activeEnvironment != selected.id) {
            finish()
            return
        }
        if (runCatching { BrowserSessionService.lease.attach(selected.id) }.isFailure) {
            finish()
            return
        }
        environment = selected
        val root = Ui.column(this)
        val header = Ui.column(this, 12)
        val title = Ui.row(this)
        title.addView(Ui.button(this, "‹ 环境") { finish() })
        Ui.weighted(title, Ui.text(this, environment.name, 18f, medium = true).apply { maxLines = 1 }, 12)
        title.addView(Ui.button(this, "配置") { showConfiguration() })
        Ui.add(header, title)
        address = Ui.field(this, "", "输入 HTTPS 网址").apply {
            contentDescription = "网站地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { navigate(); true } else false
            }
        }
        val navigation = Ui.row(this)
        Ui.weighted(navigation, address)
        navigation.addView(Ui.button(this, "访问", true) { navigate() }, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = Ui.dp(this@BrowserActivity, 8)
        })
        Ui.add(header, navigation, 12)
        feedback = Ui.text(this, "手机本地运行 · 当前设备网络", color = Ui.muted).apply {
            maxLines = 3
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        Ui.add(header, feedback, 8)
        Ui.add(root, header)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(-1, Ui.dp(this, 3)))
        viewport = FrameLayout(this)
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        val toolbar = Ui.row(this).apply { setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8)) }
        previous = Ui.button(this, "后退") { browser?.goBack() }
        next = Ui.button(this, "前进") { browser?.goForward() }
        Ui.weighted(toolbar, previous)
        Ui.weighted(toolbar, next, 8)
        Ui.weighted(toolbar, Ui.button(this, "刷新") {
            if (browser == null && !opening) openBrowser() else browser?.reload()
        }, 8)
        Ui.add(root, toolbar)
        Ui.install(this, root)
        openBrowser()
    }

    private fun openBrowser() {
        opening = true
        val attempt = ++generation
        message("正在应用本环境的网络与浏览设置…")
        handler.postDelayed(networkTimeout, 15000)
        try {
            LocalBrowser.configureNetwork(this, environment.options) {
                if (attempt == generation && !isFinishing && !isDestroyed) {
                    handler.removeCallbacks(networkTimeout)
                    opening = false
                    createBrowser()
                }
            }
        } catch (exception: Exception) {
            handler.removeCallbacks(networkTimeout)
            opening = false
            generation++
            message(exception.message ?: "网络设置失败，未加载网页，也未回退直连")
        }
    }

    private fun createBrowser() {
        try {
            val view = LocalBrowser.create(this, environment)
            browser = view
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (UrlPolicy.canNavigate(request.url.toString())) return false
                    message("此链接使用了原型暂不支持的协议，未调用外部应用")
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    address.setText(url.orEmpty())
                    message(if (url == "about:blank") "空白页 · 输入网址开始浏览" else "正在加载网页…")
                    progress.visibility = View.VISIBLE
                    updateNavigation()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    progress.visibility = View.GONE
                    if (feedback.text.toString().startsWith("正在加载")) message(if (environment.options.proxyUrl.isEmpty()) "手机本地运行 · 跟随系统网络" else "手动代理规则已应用 · 出口尚未检测")
                    updateNavigation()
                    runCatching { LocalBrowser.flush(view) }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) message("网页未能加载。请检查手机网络或网址；本地环境仍可使用。")
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) message("网站返回 HTTP ${response.statusCode}，请检查地址或稍后重试")
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    message("网站证书校验失败，已阻止访问，不会忽略证书错误")
                }

                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                    handler.cancel()
                    message("当前版本尚未支持 HTTP / 代理账号密码认证，未发送任何凭据")
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    releaseBrowser()
                    message("网页进程已退出，请点击刷新重新打开此环境")
                    return true
                }
            }
            view.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    progress.progress = value
                    progress.visibility = if (value < 100) View.VISIBLE else View.GONE
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                    message("测试版暂不开放网站摄像头和麦克风权限")
                }

                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, parameters: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    if (!environment.options.fileUploads) {
                        callback.onReceiveValue(null)
                        message("本环境已禁止文件上传，可返回环境设置修改")
                        return true
                    }
                    fileCallback = callback
                    try {
                        val types = parameters.acceptTypes.filter { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.*+-]+")) }.distinct()
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = if (types.size == 1) types.first() else "*/*"
                            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, parameters.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivityForResult(intent, FILE_PICKER)
                    } catch (_: Exception) {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = null
                        message("未找到系统文件选择器，未授予文件权限")
                    }
                    return true
                }
            }
            view.setDownloadListener { _, _, _, _, _ -> message("文件下载尚未接入；不会将登录数据转交给其他浏览器") }
            viewport.addView(view, FrameLayout.LayoutParams(-1, -1))
            view.loadUrl(environment.startUrl)
            updateNavigation()
        } catch (_: Exception) {
            releaseBrowser()
            message("无法启动独立环境，请更新系统 WebView 后重启。未使用共享环境替代。")
        }
    }

    private fun navigate() {
        try {
            val target = UrlPolicy.normalize(address.text.toString())
            if (browser == null) {
                message("环境尚未就绪，请先点击刷新重试")
                return
            }
            browser?.loadUrl(target)
            address.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        } catch (exception: IllegalArgumentException) {
            message(exception.message ?: "请输入 HTTPS 网址")
        }
    }

    private fun message(value: String) {
        feedback.text = value
    }

    private fun updateNavigation() {
        previous.isEnabled = browser?.canGoBack() == true
        next.isEnabled = browser?.canGoForward() == true
        previous.alpha = if (previous.isEnabled) 1f else 0.4f
        next.alpha = if (next.isEnabled) 1f else 0.4f
    }

    private fun releaseBrowser() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        browser?.let { runCatching { LocalBrowser.destroy(it) } }
        browser = null
    }

    override fun onPause() {
        browser?.let { view ->
            runCatching { LocalBrowser.flush(view) }
            view.onPause()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser?.onResume()
    }

    @Deprecated("Legacy back callback used for API 28 compatibility")
    override fun onBackPressed() {
        if (browser?.canGoBack() == true) browser?.goBack() else finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(networkTimeout)
        generation++
        releaseBrowser()
        super.onDestroy()
        if (isFinishing && !isChangingConfigurations) {
            BrowserSessionService.lease.shutdown()
            Process.killProcess(Process.myPid())
        }
    }

    @Deprecated("Legacy activity result used for API 28 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_PICKER) return
        val callback = fileCallback ?: return
        fileCallback = null
        val selected = if (resultCode == RESULT_OK) {
            val clip = data?.clipData
            if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            else listOfNotNull(data?.data)
        } else emptyList()
        val safe = selected.filter { it.scheme == "content" }.distinct().take(20)
        callback.onReceiveValue(safe.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private fun showConfiguration() {
        val options = environment.options
        AlertDialog.Builder(this).setTitle("当前环境实际设置")
            .setMessage("环境：${environment.name}\n引擎：${LocalBrowser.provider(this)}\nProfile：${environment.profileName}\n网络：${if (options.proxyUrl.isEmpty()) "手机当前网络" else options.proxyUrl}\nUA：${browser?.settings?.userAgentString ?: "尚未创建"}\n文字缩放：${options.textZoom}%\n默认字号：${options.fontSize}\n默认字体：${options.fontFamily}\n宽屏布局：${options.wideViewport}\nJavaScript：${options.javaScript}\nDOM Storage：${options.domStorage}\n图片：${options.images}\n第三方 Cookie：${options.thirdPartyCookies}\n手动上传：${options.fileUploads}\n\n这些是配置值，不是出口检测或指纹通过证明。更多参数请返回环境设置查看支持范围。")
            .setPositiveButton("关闭", null).show()
    }

    companion object {
        const val ENVIRONMENT_ID = "environmentId"
        private const val FILE_PICKER = 1001
    }
}
