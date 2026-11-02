package com.landu.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.landu.browser.data.Environment
import com.landu.browser.data.BrowserOptions

object LocalBrowser {
    fun configureNetwork(context: Context, options: BrowserOptions, ready: () -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            if (options.proxyUrl.isEmpty()) {
                ProxyController.getInstance().clearProxyOverride(context.mainExecutor, ready)
            } else {
                val config = ProxyConfig.Builder().addProxyRule(options.proxyUrl).removeImplicitRules().build()
                ProxyController.getInstance().setProxyOverride(config, context.mainExecutor, ready)
            }
        } else {
            check(options.proxyUrl.isEmpty()) { "系统 WebView 不支持代理设置，已阻止启动，未回退到系统网络" }
            ready()
        }
    }

    fun supported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }.getOrDefault(false)

    fun provider(context: Context): String = runCatching {
        val provider = WebViewCompat.getCurrentWebViewPackage(context)
        "${provider?.packageName ?: "未知 WebView"} · ${provider?.versionName ?: "未知版本"}"
    }.getOrDefault("无法读取 WebView 版本")

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, environment: Environment): WebView {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            check(!environment.pendingDeletion) { "该环境正在删除" }
            val view = WebView(context)
            try {
                WebViewCompat.setProfile(view, environment.profileName)
                val options = environment.options
                view.settings.apply {
                    javaScriptEnabled = options.javaScript
                    domStorageEnabled = options.domStorage
                    loadsImagesAutomatically = options.images
                    textZoom = options.textZoom
                    defaultFontSize = options.fontSize
                    standardFontFamily = options.fontFamily
                    useWideViewPort = options.wideViewport
                    loadWithOverviewMode = options.wideViewport
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    if (options.userAgent.isNotEmpty()) userAgentString = options.userAgent
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(false)
                    mediaPlaybackRequiresUserGesture = true
                    safeBrowsingEnabled = true
                }
                WebViewCompat.getProfile(view).cookieManager.setAcceptThirdPartyCookies(view, options.thirdPartyCookies)
                return view
            } catch (exception: Exception) {
                view.destroy()
                throw exception
            }
        }
        error("当前系统 WebView 不支持多环境隔离，请更新 Android System WebView 后重试")
    }

    fun flush(view: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.getProfile(view).cookieManager.flush()
        }
    }

    fun destroy(view: WebView) {
        runCatching { flush(view) }
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }

    fun delete(profileName: String) {
        require(profileName.matches(Regex("landu_[0-9a-f]{32}")))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().deleteProfile(profileName)
        } else {
            error("当前 WebView 无法清理隔离数据")
        }
    }
}
