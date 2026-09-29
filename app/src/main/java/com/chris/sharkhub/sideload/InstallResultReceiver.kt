package com.chris.sharkhub.sideload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Receives the PackageInstaller callback. When the system needs the user to confirm the install it
 * hands us a follow-up Intent (STATUS_PENDING_USER_ACTION) which we launch — that's the standard
 * "Do you want to install this app?" dialog.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS ->
                toast(ctx, "Installed ✓")
            else ->
                toast(ctx, "Install failed ($status) ${msg ?: ""}")
        }
    }

    private fun toast(ctx: Context, s: String) =
        Toast.makeText(ctx.applicationContext, s, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION = "com.chris.sharkhub.INSTALL_RESULT"
    }
}
