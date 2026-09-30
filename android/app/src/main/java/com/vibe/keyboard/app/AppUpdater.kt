package com.vibe.keyboard.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads a published build inside Vibe and hands it to Android's installer.
 *
 * Opening the download link in "a browser" is not reliable: on phones where a
 * media player (VLC) also claims web links, Android can send the APK link
 * there, which tries to play it and saves nothing. Downloading here, with the
 * system DownloadManager, and opening the result with ACTION_INSTALL_PACKAGE
 * leaves the installer as the only thing that can handle it.
 */
class AppUpdater(private val context: Context) {

    private val downloads = context.getSystemService(DownloadManager::class.java)

    /** Android asks once, per app, before it may install updates. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun askInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Downloads [url], reporting progress 0..100, then opens the installer.
     * @return null on success, or why it failed.
     */
    suspend fun downloadAndInstall(url: String, onProgress: (Int) -> Unit): String? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return "No storage for the download."
        File(dir, FILE_NAME).delete()
        val id = downloads.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle("Vibe update")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME),
        )
        while (true) {
            val (status, done, total) = withContext(Dispatchers.IO) { query(id) } ?: return "The download disappeared."
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> break
                DownloadManager.STATUS_FAILED -> return "The download failed. Check the connection and try again."
                else -> if (total > 0) onProgress((done * 100 / total).toInt())
            }
            delay(400)
        }
        onProgress(100)
        val uri = downloads.getUriForDownloadedFile(id) ?: return "The download finished but can't be opened."
        @Suppress("DEPRECATION") // Still the one intent only the system installer answers.
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return null
    }

    private fun query(id: Long): Triple<Int, Long, Long>? =
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            Triple(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val FILE_NAME = "vibe-update.apk"
    }
}
