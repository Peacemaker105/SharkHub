package com.chris.sharkhub.sideload

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.InputStream

/**
 * Installs an APK using the platform PackageInstaller session API — no ADB, no root.
 *
 * This is the reliable "sideload through our app" path: Shark Hub asks the system installer to add
 * the package, the user confirms once, done. It works for normal user installs. It can NOT silently
 * install a new app without the user's confirmation dialog unless Shark Hub is a device owner / holds
 * privileged install permission (a root/OEM-only scenario). The exception Android 12 allows: updates
 * to apps Shark Hub installed itself (including its own OTA), thanks to USER_ACTION_NOT_REQUIRED plus
 * the UPDATE_PACKAGES_WITHOUT_USER_ACTION permission.
 *
 * The result confirmation comes back to [InstallResultReceiver] via a broadcast.
 * Blocking (copies the whole APK) — call off the main thread.
 */
class ApkInstaller(private val ctx: Context) {

    fun installFromFile(apk: File, onStatus: (String) -> Unit): Result<Unit> = runCatching {
        apk.inputStream().use { installFromStream(it, apk.length(), apk.name, onStatus).getOrThrow() }
    }

    fun installFromStream(
        input: InputStream,
        sizeBytes: Long,
        label: String,
        onStatus: (String) -> Unit,
    ): Result<Unit> = runCatching {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { s ->
            try {
                onStatus("writing $label…")
                s.openWrite(label, 0, if (sizeBytes > 0) sizeBytes else -1).use { out ->
                    input.copyTo(out, 128 * 1024)
                    s.fsync(out)
                }
                // Broadcast target that the system will ping with the install outcome / user prompt.
                val intent = Intent(ctx, InstallResultReceiver::class.java).apply {
                    action = InstallResultReceiver.ACTION
                }
                val flags = if (Build.VERSION.SDK_INT >= 31)
                    android.app.PendingIntent.FLAG_MUTABLE else 0
                val pending = android.app.PendingIntent.getBroadcast(
                    ctx, sessionId, intent, flags or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                onStatus("committing…")
                s.commit(pending.intentSender)
            } catch (e: Throwable) {
                // Otherwise the half-written session (and its staged copy of the APK) lingers.
                runCatching { s.abandon() }
                throw e
            }
        }
        onStatus("sent to the installer — confirm on screen if asked")
    }
}
