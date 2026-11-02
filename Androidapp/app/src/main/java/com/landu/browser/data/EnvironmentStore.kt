package com.landu.browser.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

interface LocalDocument {
    fun read(): String?
    fun write(value: String)
}

class ConfigurationChangedException : IllegalStateException("配置已在其他窗口更新，请返回列表重新编辑")

class EnvironmentStore(private val document: LocalDocument) {
    private var snapshot = document.read()
    private var records: List<Environment> = decode(snapshot)

    fun all(): List<Environment> = records.toList()

    fun find(id: String): Environment? = records.firstOrNull { it.id == id && !it.pendingDeletion }

    fun save(id: String?, name: String, group: String, startUrl: String,
        notes: String? = null, options: BrowserOptions? = null): Environment {
        val previous = id?.let { find(it) ?: error("环境不存在或正在删除") }
        require(previous != null || records.size < 100) { "测试版最多保存 100 个环境" }
        val environment = Environment(
            id = previous?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            group = group.trim(),
            startUrl = UrlPolicy.normalize(startUrl),
            createdAt = previous?.createdAt ?: System.currentTimeMillis(),
            revision = (previous?.revision ?: 0) + 1,
            notes = notes?.trim() ?: previous?.notes.orEmpty(),
            options = options ?: previous?.options ?: BrowserOptions(),
        )
        val updated = if (previous == null) records + environment
            else records.map { if (it.id == environment.id) environment else it }
        commit(updated)
        return environment
    }

    fun duplicate(id: String): Environment {
        val original = find(id) ?: error("环境不存在或正在删除")
        return save(null, original.name.take(36) + " 副本", original.group, original.startUrl, original.notes, original.options)
    }

    fun requestDeletion(id: String) {
        val environment = find(id) ?: error("环境不存在或正在删除")
        commit(records.map {
            if (it.id == id) environment.copy(pendingDeletion = true, revision = it.revision + 1) else it
        })
    }

    fun finishDeletions(deleteProfile: (String) -> Unit): Int {
        var remaining = 0
        records.filter { it.pendingDeletion }.forEach { environment ->
            try {
                deleteProfile(environment.profileName)
                commit(records.filterNot { it.id == environment.id })
            } catch (_: Exception) {
                remaining++
            }
        }
        return remaining
    }

    private fun commit(updated: List<Environment>) {
        val entries = JSONArray()
        updated.forEach { environment ->
            entries.put(JSONObject().apply {
                put("id", environment.id)
                put("name", environment.name)
                put("group", environment.group)
                put("startUrl", environment.startUrl)
                put("createdAt", environment.createdAt)
                put("revision", environment.revision)
                put("pendingDeletion", environment.pendingDeletion)
                put("networkMode", if (environment.options.proxyUrl.isEmpty()) "system" else "proxy")
                put("notes", environment.notes)
                put("options", encodeOptions(environment.options))
            })
        }
        val serialized = JSONObject().put("schemaVersion", 2).put("environments", entries).toString()
        if (document.read() != snapshot) throw ConfigurationChangedException()
        document.write(serialized)
        snapshot = serialized
        records = updated
    }

    private fun decode(text: String?): List<Environment> {
        if (text == null) return emptyList()
        val root = JSONObject(text)
        val schema = root.getInt("schemaVersion")
        require(schema == 1 || schema == 2) { "不支持的配置版本，请勿覆盖原数据" }
        val entries = root.getJSONArray("environments")
        require(entries.length() <= 100) { "配置数量超出测试版限制" }
        val result = (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            val options = if (schema == 1) BrowserOptions() else decodeOptions(entry.getJSONObject("options"))
            require(entry.getString("networkMode") == if (options.proxyUrl.isEmpty()) "system" else "proxy") { "不支持或不一致的网络模式" }
            Environment(
                id = entry.getString("id"),
                name = entry.getString("name"),
                group = entry.getString("group"),
                startUrl = entry.getString("startUrl"),
                createdAt = entry.getLong("createdAt"),
                revision = entry.getInt("revision"),
                pendingDeletion = entry.getBoolean("pendingDeletion"),
                notes = if (schema == 1) "" else entry.getString("notes"),
                options = options,
            )
        }
        require(result.map { it.id }.distinct().size == result.size) { "环境 ID 重复" }
        return result
    }

    private fun encodeOptions(options: BrowserOptions) = JSONObject().apply {
        put("proxyUrl", options.proxyUrl)
        put("proxyLimitationsAccepted", options.proxyLimitationsAccepted)
        put("userAgent", options.userAgent)
        put("wideViewport", options.wideViewport)
        put("textZoom", options.textZoom)
        put("fontSize", options.fontSize)
        put("fontFamily", options.fontFamily)
        put("javaScript", options.javaScript)
        put("domStorage", options.domStorage)
        put("images", options.images)
        put("thirdPartyCookies", options.thirdPartyCookies)
        put("fileUploads", options.fileUploads)
    }

    private fun decodeOptions(value: JSONObject) = BrowserOptions(
        proxyUrl = value.getString("proxyUrl"),
        proxyLimitationsAccepted = value.getBoolean("proxyLimitationsAccepted"),
        userAgent = value.getString("userAgent"),
        wideViewport = value.getBoolean("wideViewport"),
        textZoom = value.getInt("textZoom"),
        fontSize = value.getInt("fontSize"),
        fontFamily = value.getString("fontFamily"),
        javaScript = value.getBoolean("javaScript"),
        domStorage = value.getBoolean("domStorage"),
        images = value.getBoolean("images"),
        thirdPartyCookies = value.getBoolean("thirdPartyCookies"),
        fileUploads = value.getBoolean("fileUploads"),
    )
}
