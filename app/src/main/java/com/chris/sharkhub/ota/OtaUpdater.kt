package com.chris.sharkhub.ota

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import com.chris.sharkhub.sideload.ApkInstaller
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Dead-simple self-update. You host two things anywhere reachable over HTTPS (GitHub Releases, a
 * VPS, an S3 bucket, even a Shopify file):
 *
 *   1) a JSON manifest, e.g. https://you.example/sharkhub/latest.json
 *        { "versionCode": 3, "versionName": "0.3.0", "apkUrl": "https://.../sharkhub-0.3.0.apk",
 *          "notes": "seat vent + map probe" }
 *   2) the signed APK it points to.
 *
 * The app checks the manifest, and if versionCode > installed, downloads the APK, checks it really
 * is a build of this app, and installs it through the same PackageInstaller session as Sideload.
 * The APK MUST be signed with the SAME key as the installed build or Android treats it as a
 * different app — so once you ship v1, keep the keystore.
 *
 * With UPDATE_PACKAGES_WITHOUT_USER_ACTION declared, Android 12 applies the self-update without a
 * confirm dialog; the running app is killed as the new version lands, so reopen it afterwards.
 * REQUEST_INSTALL_PACKAGES is declared; on first use the unit may ask you to allow installs from
 * Shark Hub. Approve once.
 */
class OtaUpdater(private val ctx: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val current: Int) : State
        data class Available(val m: Manifest) : State
        data class Downloading(val pct: Int) : State
        data class ReadyToInstall(val file: File) : State
        data class Error(val message: String) : State
    }

    fun currentVersionCode(): Int = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)

    fun currentVersionName(): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"

    fun fetchManifest(url: String): Result<Manifest> = runCatching {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("empty manifest")
            val j = JSONObject(body)
            Manifest(
                versionCode = j.getInt("versionCode"),
                versionName = j.optString("versionName", "?"),
                apkUrl = j.getString("apkUrl"),
                notes = j.optString("notes", "")
            )
        }
    }

    /** Downloads [url] to [dest], calling [onProgress] with 0..100. Also used by Sideload's install-from-URL. */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit): Result<File> = runCatching {
        dest.parentFile?.mkdirs()
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = body.contentLength().coerceAtLeast(1)
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        dest
    }

    /** Downloads the manifest's APK to app storage, clearing out any older OTA download first. */
    fun downloadApk(m: Manifest, onProgress: (Int) -> Unit): Result<File> {
        val dir = File(ctx.filesDir, "ota").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return download(m.apkUrl, File(dir, "sharkhub-${m.versionCode}.apk"), onProgress)
    }

    /** Checks the APK is a build of this app, then hands it to the session installer. */
    fun install(file: File, onStatus: (String) -> Unit = {}): Result<Unit> = runCatching {
        verifyIsUpdateOfSelf(file)
        ApkInstaller(ctx).installFromFile(file, onStatus).getOrThrow()
    }

    /**
     * The manifest is just a URL (and the default one isn't ours yet), so don't let it hand the
     * installer an arbitrary app: the APK must be this package and, when both signatures are
     * readable, signed with the same key. Android enforces the key on update anyway — checking here
     * turns a vague installer failure into a clear message.
     */
    @Suppress("DEPRECATION")
    private fun verifyIsUpdateOfSelf(file: File) {
        val pm = ctx.packageManager
        // GET_SIGNATURES too: some builds leave signingInfo null for archives.
        val flags = PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: error("download isn't a valid APK")
        if (apk.packageName != ctx.packageName)
            error("APK is ${apk.packageName}, not ${ctx.packageName} — refusing to install")
        val theirs = signerDigests(apk)
        val ours = signerDigests(pm.getPackageInfo(ctx.packageName, flags))
        if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours)
            error("APK is signed with a different key than the installed Shark Hub")
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(pi: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = pi.signingInfo?.apkContentsSigners ?: pi.signatures
        return sigs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    /** Whether we're allowed to install packages. Route user to grant if not. */
    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
