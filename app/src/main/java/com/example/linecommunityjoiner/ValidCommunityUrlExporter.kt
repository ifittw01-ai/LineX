package com.example.linecommunityjoiner

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

object ValidCommunityUrlExporter {
    fun rewriteToDownloads(context: Context, fileName: String, urls: Collection<String>): Boolean {
        return try {
            val content = buildString {
                urls.forEach {
                    append(it)
                    append('\n')
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                writeByMediaStore(context, fileName, content)
            } else {
                writeByLegacyFile(fileName, content)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeByLegacyFile(fileName: String, content: String) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
    }

    private fun writeByMediaStore(context: Context, fileName: String, content: String) {
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/"
        val existing = queryExistingDownloadFile(context, fileName, relativePath)
        val targetUri = existing ?: run {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("無法建立下載檔案")
        }
        resolver.openOutputStream(targetUri, "wt")?.use { os ->
            BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { it.write(content) }
        } ?: throw IllegalStateException("無法寫入下載檔案")
    }

    private fun queryExistingDownloadFile(context: Context, fileName: String, relativePath: String): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val args = arrayOf(fileName, relativePath)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }
}
