package com.landu.browser.data

import java.net.URI

data class BrowserOptions(
    val proxyUrl: String = "",
    val proxyLimitationsAccepted: Boolean = false,
    val userAgent: String = "",
    val wideViewport: Boolean = false,
    val textZoom: Int = 100,
    val fontSize: Int = 16,
    val fontFamily: String = "sans-serif",
    val javaScript: Boolean = true,
    val domStorage: Boolean = true,
    val images: Boolean = true,
    val thirdPartyCookies: Boolean = false,
    val fileUploads: Boolean = true,
) {
    init {
        require(proxyUrl == ProxyAddress.normalize(proxyUrl)) { "代理地址格式不正确" }
        require(proxyUrl.isEmpty() || proxyLimitationsAccepted) { "请先确认代理能力与限制" }
        require(userAgent.length <= 1024 && userAgent.all { it.code in 32..126 }) { "UA 仅支持最多 1024 个可打印 ASCII 字符" }
        require(textZoom in 75..200) { "网页文字缩放须在 75–200% 之间" }
        require(fontSize in 12..32) { "网页默认字号须在 12–32 之间" }
        require(fontFamily in listOf("sans-serif", "serif", "monospace")) { "不支持的网页字体" }
    }
}

object ProxyAddress {
    fun normalize(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return ""
        require(value.length <= 512 && value.none { it.isWhitespace() || it.isISOControl() }) { "代理地址包含非法字符或过长" }
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("请输入 协议://主机:端口") }
        require(uri.scheme in listOf("http", "https", "socks5") && !uri.host.isNullOrEmpty() &&
            uri.port in 1..65535 && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
            "代理格式为 http://主机:端口、https://主机:端口 或 socks5://主机:端口；暂不支持账号密码"
        }
        return "${uri.scheme}://${uri.host.lowercase()}:${uri.port}"
    }
}
