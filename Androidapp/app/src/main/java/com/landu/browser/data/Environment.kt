package com.landu.browser.data

import java.net.URI
import java.util.UUID

data class Environment(
    val id: String,
    val name: String,
    val group: String,
    val startUrl: String,
    val createdAt: Long,
    val revision: Int = 1,
    val pendingDeletion: Boolean = false,
    val notes: String = "",
    val options: BrowserOptions = BrowserOptions(),
) {
    val profileName: String get() = "landu_" + id.replace("-", "")

    init {
        require(UUID.fromString(id).toString() == id) { "Invalid environment ID" }
        require(name.isNotBlank() && name.length <= 40) { "环境名称需要 1–40 个字符" }
        require(group.length <= 40) { "分组名称不能超过 40 个字符" }
        require(notes.length <= 500) { "备注不能超过 500 个字符" }
        require(createdAt > 0 && revision > 0) { "Invalid environment revision" }
        require(startUrl == UrlPolicy.normalize(startUrl)) { "Invalid start URL" }
    }
}

object UrlPolicy {
    fun normalize(input: String): String {
        val value = input.trim()
        if (value.isEmpty() || value == "about:blank") return "about:blank"
        require(value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() }) {
            "地址过长或包含空格，请输入完整 HTTPS 网址"
        }
        val candidate = if (value.contains("://")) value else "https://$value"
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            throw IllegalArgumentException("请输入有效的 HTTPS 网址")
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0) {
            "本地原型仅支持 HTTPS 网址，不支持账号嵌入地址或外部应用协议"
        }
        return "https" + candidate.substring(candidate.indexOf(':'))
    }

    fun canNavigate(url: String): Boolean = runCatching { normalize(url) == url }.getOrDefault(false)
}
