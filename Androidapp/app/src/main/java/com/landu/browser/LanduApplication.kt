package com.landu.browser

import android.app.Application
import android.webkit.WebView

class LanduApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":manager")) WebView.disableWebView()
    }
}
