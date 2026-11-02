package com.landu.browser.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

class PrivateDocument(context: Context) : LocalDocument {
    private val file = AtomicFile(File(context.noBackupFilesDir, "environments-v1.json"))

    override fun read(): String? = try {
        file.readFully().toString(Charsets.UTF_8)
    } catch (exception: FileNotFoundException) {
        if (file.baseFile.exists()) throw exception else null
    }

    override fun write(value: String) {
        val output = file.startWrite()
        try {
            output.write(value.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (exception: Exception) {
            file.failWrite(output)
            throw exception
        }
    }
}
