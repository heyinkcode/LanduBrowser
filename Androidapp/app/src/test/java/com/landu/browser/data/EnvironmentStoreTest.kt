package com.landu.browser.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnvironmentStoreTest {
    private class MemoryDocument(var value: String? = null) : LocalDocument {
        var failWrites = false
        override fun read() = value
        override fun write(value: String) {
            if (failWrites) error("disk full")
            this.value = value
        }
    }

    @Test fun firstRunIsEmptyWithoutDemoData() {
        val document = MemoryDocument()
        assertTrue(EnvironmentStore(document).all().isEmpty())
        assertNull(document.value)
    }

    @Test fun staleEditorCannotOverwriteChangesFromAnotherWindow() {
        val document = MemoryDocument()
        val first = EnvironmentStore(document)
        val stale = EnvironmentStore(document)
        val saved = first.save(null, "另一个窗口", "", "")
        val snapshot = document.value
        assertThrows(ConfigurationChangedException::class.java) { stale.save(null, "旧窗口", "", "") }
        assertEquals(snapshot, document.value)
        assertEquals(saved, EnvironmentStore(document).find(saved.id))
        assertTrue(stale.all().isEmpty())
    }

    @Test fun reloadingAllowsEditingWithoutLosingOtherEnvironments() {
        val document = MemoryDocument()
        val original = EnvironmentStore(document).save(null, "保留", "", "")
        val reloaded = EnvironmentStore(document)
        val added = reloaded.save(null, "新增", "", "")
        reloaded.save(added.id, "修改新增", "", "")
        val current = EnvironmentStore(document)
        assertEquals(original, current.find(original.id))
        assertEquals("修改新增", current.find(added.id)?.name)
        assertEquals(2, current.all().size)
    }

    @Test fun twoEnvironmentsHaveDifferentStableProfiles() {
        val document = MemoryDocument()
        val store = EnvironmentStore(document)
        val first = store.save(null, "日常", "个人", "example.com")
        val second = store.save(null, "工作", "工作", "")
        assertNotEquals(first.profileName, second.profileName)
        assertEquals(first, EnvironmentStore(document).find(first.id))
        assertEquals("https://example.com", first.startUrl)
        assertEquals("about:blank", second.startUrl)
    }

    @Test fun editPreservesProfileAndIncrementsRevision() {
        val store = EnvironmentStore(MemoryDocument())
        val original = store.save(null, "原名称", "", "")
        val updated = store.save(original.id, " 新名称 ", " 分组 ", "https://example.com/a")
        assertEquals(original.profileName, updated.profileName)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals(2, updated.revision)
        assertEquals("新名称", updated.name)
        assertEquals("分组", updated.group)
    }

    @Test fun failedWriteDoesNotMutateMemoryOrDisk() {
        val document = MemoryDocument()
        val store = EnvironmentStore(document)
        val original = store.save(null, "保留", "", "")
        val saved = document.value
        document.failWrites = true
        assertThrows(IllegalStateException::class.java) { store.save(original.id, "不应保存", "", "") }
        assertEquals(original, store.find(original.id))
        assertEquals(saved, document.value)
    }

    @Test fun corruptDataIsNotResetOrOverwritten() {
        val document = MemoryDocument("broken")
        assertThrows(Exception::class.java) { EnvironmentStore(document) }
        assertEquals("broken", document.value)
    }

    @Test fun unknownSchemaIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EnvironmentStore(MemoryDocument("""{"schemaVersion":99,"environments":[]}"""))
        }
    }

    @Test fun unsupportedNetworkModeIsNotSilentlyIgnored() {
        val document = MemoryDocument()
        EnvironmentStore(document).save(null, "测试", "", "")
        document.value = document.value!!.replace("\"system\"", "\"proxy\"")
        assertThrows(IllegalArgumentException::class.java) { EnvironmentStore(document) }
    }

    @Test fun duplicateIdsAreRejected() {
        val document = MemoryDocument()
        EnvironmentStore(document).save(null, "测试", "", "")
        val root = JSONObject(document.value!!)
        val entries = root.getJSONArray("environments")
        entries.put(entries.getJSONObject(0))
        document.value = root.toString()
        assertThrows(IllegalArgumentException::class.java) { EnvironmentStore(document) }
    }

    @Test fun unsafeIdsCannotSelectDefaultOrOtherProfiles() {
        assertThrows(IllegalArgumentException::class.java) {
            Environment("Default", "测试", "", "about:blank", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Environment("../../cookies", "测试", "", "about:blank", 1)
        }
    }

    @Test fun pendingDeletionSurvivesRestartAndCannotBeOpened() {
        val document = MemoryDocument()
        val store = EnvironmentStore(document)
        val target = store.save(null, "删除", "", "")
        val keep = store.save(null, "保留", "", "")
        store.requestDeletion(target.id)
        assertNull(store.find(target.id))
        assertEquals(1, store.finishDeletions { error("profile still loaded") })
        val restarted = EnvironmentStore(document)
        assertTrue(restarted.all().first { it.id == target.id }.pendingDeletion)
        val removed = mutableListOf<String>()
        assertEquals(0, restarted.finishDeletions { removed.add(it) })
        assertEquals(listOf(target.profileName), removed)
        assertEquals(listOf(keep), restarted.all())
    }

    @Test fun failedTombstoneWriteNeverCallsProfileDeletion() {
        val document = MemoryDocument()
        val store = EnvironmentStore(document)
        val target = store.save(null, "保留", "", "")
        document.failWrites = true
        assertThrows(IllegalStateException::class.java) { store.requestDeletion(target.id) }
        var invoked = false
        store.finishDeletions { invoked = true }
        assertFalse(invoked)
        assertEquals(target, store.find(target.id))
    }

    @Test fun failedDeletionCommitRemainsRetryable() {
        val document = MemoryDocument()
        val store = EnvironmentStore(document)
        val target = store.save(null, "删除", "", "")
        store.requestDeletion(target.id)
        document.failWrites = true
        assertEquals(1, store.finishDeletions { })
        assertTrue(EnvironmentStore(document).all().single().pendingDeletion)
        document.failWrites = false
        assertEquals(0, store.finishDeletions { })
        assertTrue(store.all().isEmpty())
    }

    @Test fun blankAndLongNamesAreRejected() {
        val store = EnvironmentStore(MemoryDocument())
        assertThrows(IllegalArgumentException::class.java) { store.save(null, " ", "", "") }
        assertThrows(IllegalArgumentException::class.java) { store.save(null, "名".repeat(41), "", "") }
        assertThrows(IllegalArgumentException::class.java) { store.save(null, "正常", "组".repeat(41), "") }
    }

    @Test fun deletingUnknownEnvironmentIsRejected() {
        val store = EnvironmentStore(MemoryDocument())
        assertThrows(IllegalStateException::class.java) { store.requestDeletion("unknown") }
    }

    @Test fun prototypeLimitIsExplicit() {
        val store = EnvironmentStore(MemoryDocument())
        repeat(100) { index -> store.save(null, "环境 $index", "", "") }
        assertThrows(IllegalArgumentException::class.java) { store.save(null, "超限", "", "") }
    }

    @Test fun legacySettingsMigrateWithoutChangingProfileOrWritingOnRead() {
        val document = MemoryDocument()
        val original = EnvironmentStore(document).save(null, "保留账号", "工作", "")
        val legacy = JSONObject(document.value!!).put("schemaVersion", 1)
        legacy.getJSONArray("environments").getJSONObject(0).apply { remove("options"); remove("notes") }
        document.value = legacy.toString()
        val before = document.value
        val store = EnvironmentStore(document)
        assertEquals(original.profileName, store.find(original.id)!!.profileName)
        assertEquals(BrowserOptions(), store.find(original.id)!!.options)
        assertEquals(before, document.value)
        store.save(original.id, "新名称", "工作", "")
        assertEquals(2, JSONObject(document.value!!).getInt("schemaVersion"))
        assertEquals(original.profileName, EnvironmentStore(document).find(original.id)!!.profileName)
    }

    @Test fun allSettingsRoundTripAndRemainPerEnvironment() {
        val document = MemoryDocument()
        val store = EnvironmentStore(document)
        val options = BrowserOptions("https://proxy.example:8443", true, "Landu-Test/1", true, 150, 22, "serif", false, false, false, true, false)
        val first = store.save(null, "配置 A", "工作", "example.com", "仅测试", options)
        val second = store.save(null, "配置 B", "", "")
        val reopened = EnvironmentStore(document)
        assertEquals(options, reopened.find(first.id)!!.options)
        assertEquals("仅测试", reopened.find(first.id)!!.notes)
        assertEquals(BrowserOptions(), reopened.find(second.id)!!.options)
    }

    @Test fun renamePreservesOptionsWhenNotExplicitlyChanged() {
        val store = EnvironmentStore(MemoryDocument())
        val original = store.save(null, "设置", "", "", "备注", BrowserOptions(textZoom = 150))
        assertEquals(original.options, store.save(original.id, "改名", "", "").options)
        assertEquals("备注", store.find(original.id)!!.notes)
    }

    @Test fun copyingSettingsNeverReusesIdentity() {
        val store = EnvironmentStore(MemoryDocument())
        val original = store.save(null, "账户", "工作", "example.com", "说明", BrowserOptions(textZoom = 125))
        val copied = store.duplicate(original.id)
        assertNotEquals(original.id, copied.id)
        assertNotEquals(original.profileName, copied.profileName)
        assertEquals(original.options, copied.options)
        assertEquals(original.notes, copied.notes)
        assertEquals(1, copied.revision)
    }

    @Test fun failedSettingsWriteKeepsOldSettings() {
        val document = MemoryDocument()
        val store = EnvironmentStore(document)
        val original = store.save(null, "设置", "", "")
        val before = document.value
        document.failWrites = true
        assertThrows(Exception::class.java) { store.save(original.id, "设置", "", "", options = BrowserOptions(textZoom = 175)) }
        assertEquals(original, store.find(original.id))
        assertEquals(before, document.value)
    }

    @Test fun malformedV2SettingsAreNotDefaultedOrOverwritten() {
        val document = MemoryDocument()
        EnvironmentStore(document).save(null, "设置", "", "")
        val root = JSONObject(document.value!!)
        root.getJSONArray("environments").getJSONObject(0).getJSONObject("options").put("textZoom", 500)
        document.value = root.toString()
        val before = document.value
        assertThrows(IllegalArgumentException::class.java) { EnvironmentStore(document) }
        assertEquals(before, document.value)
    }

    @Test fun missingV2OptionsCannotSilentlyDisableConfiguredProxy() {
        val document = MemoryDocument()
        EnvironmentStore(document).save(null, "设置", "", "", options = BrowserOptions("http://proxy.example:8080", true))
        val root = JSONObject(document.value!!)
        root.getJSONArray("environments").getJSONObject(0).remove("options")
        document.value = root.toString()
        assertThrows(Exception::class.java) { EnvironmentStore(document) }
    }

    @Test fun notesLimitIsEnforced() {
        assertThrows(IllegalArgumentException::class.java) { EnvironmentStore(MemoryDocument()).save(null, "设置", "", "", "长".repeat(501)) }
    }
}
