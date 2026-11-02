package com.landu.browser.browser

class SessionLease {
    var environmentId: String? = null
        private set
    var attached = false
        private set
    var closing = false
        private set

    fun claim(id: String) {
        check(!closing) { "旧浏览进程正在退出，请稍后重试" }
        require(id.isNotBlank()) { "环境 ID 为空" }
        check(environmentId == null || environmentId == id) { "另一个环境尚未关闭，请返回该环境退出后再试" }
        environmentId = id
    }

    fun attach(id: String) {
        check(!closing && environmentId == id) { "浏览会话已经失效，请返回列表重新打开" }
        attached = true
    }

    fun requireIdle() {
        check(!closing && environmentId == null) { "请先关闭正在浏览的环境，再清理数据" }
    }

    fun shutdown() { closing = true }
}
