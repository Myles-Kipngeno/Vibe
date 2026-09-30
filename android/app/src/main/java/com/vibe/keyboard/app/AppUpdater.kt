package com.vibe.keyboard.app

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Where an update is, as the card shows it. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    /** Handed to Android; waiting for it to finish (or for the user to confirm). */
    data object Installing : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Updates Vibe by installing the new build itself, through PackageInstaller.
 *
 * Earlier versions handed the file to Android's installer screen
 * (ACTION_INSTALL_PACKAGE). On the user's phone that screen froze at 90% on
 * two updates out of four while a direct install of the same file took 18
 * seconds -- and a frozen session owned by the installer screen could not even
 * be cancelled. Installing through our own session removes that screen from
 * the path, lets Vibe cancel and retry a stalled install, and reports every
 * outcome. Once Vibe has installed itself once, Android 12+ lets later updates
 * go through without asking.
 */
class AppUpdater(private val context: Context) {

    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val installer = context.packageManager.packageInstaller

    /** Android asks once, per app, before it may install updates. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun askInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Downloads [url], then installs it. Returns when Android has the install
     * (success restarts Vibe, so a return with [UpdateState.Installing] still
     * set after [STALL_MS] means it stalled). Failures come back as a reason.
     */
    suspend fun update(url: String) {
        abandonOurSessions() // a stalled attempt from before must not linger
        val apk = download(url) ?: return
        _state.value = UpdateState.Installing
        val sessionId = withContext(Dispatchers.IO) { writeSession(apk) } ?: return
        withContext(Dispatchers.IO) { commit(sessionId) }
        // Success replaces this process, so this only ever finishes on a stall.
        delay(STALL_MS)
        if (_state.value == UpdateState.Installing) {
            abandonOurSessions()
            _state.value = UpdateState.Failed("Android didn't finish installing. Tap Update to try again.")
        }
    }

    private suspend fun download(url: String): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return fail("No storage for the download.")
        val file = File(dir, FILE_NAME).apply { delete() }
        val id = downloads.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle("Vibe update")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME),
        )
        _state.value = UpdateState.Downloading(0)
        while (true) {
            val (status, done, total) = withContext(Dispatchers.IO) { query(id) } ?: return fail("The download disappeared.")
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> return file.takeIf { it.length() > 0 } ?: fail("The download came back empty.")
                DownloadManager.STATUS_FAILED -> return fail("The download failed. Check the connection and try again.")
                else -> if (total > 0) _state.value = UpdateState.Downloading((done * 100 / total).toInt())
            }
            delay(400)
        }
    }

    private fun writeSession(apk: File): Int? = runCatching {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Vibe updating itself, once it is the app that installed it: no prompt needed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("vibe.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
        }
        id
    }.getOrElse {
        _state.value = UpdateState.Failed("Couldn't prepare the install: ${it.message}")
        null
    }

    private fun commit(sessionId: Int) {
        val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
        installer.openSession(sessionId).use { it.commit(pending.intentSender) }
    }

    /** Only sessions Vibe created; Android's other installs are never touched. */
    private fun abandonOurSessions() {
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
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

    private fun <T> fail(reason: String): T? {
        _state.value = UpdateState.Failed(reason)
        return null
    }

    companion object {
        const val ACTION_RESULT = "com.vibe.keyboard.UPDATE_RESULT"
        const val STALL_MS = 90_000L
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val FILE_NAME = "vibe-update.apk"

        private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        /** Shared with the receiver, which hears Android's answer. */
        val state: StateFlow<UpdateState> = _state.asStateFlow()

        internal fun report(state: UpdateState) {
            _state.value = state
        }
    }
}

/**
 * Android's answer about an install. Confirmation needed: show Android's own
 * dialog. Success: nothing to do -- Vibe is replaced and restarts. Anything
 * else: say why, instead of leaving the user staring at a screen.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.report(UpdateState.Idle)
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdater.report(UpdateState.Failed("Update cancelled. Tap Update when you're ready."))
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppUpdater.report(UpdateState.Failed("Android couldn't install it (${message ?: "status $status"}). Tap Update to try again."))
            }
        }
    }
}
