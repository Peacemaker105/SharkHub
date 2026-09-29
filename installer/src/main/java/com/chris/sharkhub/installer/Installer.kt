package com.chris.sharkhub.installer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** The GitHub repository ("owner/name") whose latest release carries the Shark Hub APK. */
const val DEFAULT_REPO = "Peacemaker105/SharkHub"
const val SHARK_HUB_PACKAGE = "com.chris.sharkhub"

enum class Mood { Busy, Good, Bad, Info }

/** An address that answered on ADB's port: [adb] when it replied like adbd, not just an open port. */
data class Hit(val ip: String, val adb: Boolean)

/**
 * Finds the car on the phone's own networks — the hotspot it's sharing, or the Wi-Fi it's on. Every
 * address is tried on 5555 and anything that answers gets ADB's opening message (CNXN); a car with
 * ADB on replies AUTH straight away. No key is offered, so a scan never makes the car ask to allow
 * the phone. The same scheme as the Windows installer (tools/installer).
 */
object Finder {
    private const val PORT = 5555
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val STLS = 0x534c5453

    // Mobile data, VPNs and loopback: the car is never there, and a carrier's range is no place to scan.
    private val skip = listOf("rmnet", "ccmni", "pdp", "v4-", "clat", "dummy", "tun", "ppp", "ipsec", "lo", "seth", "radio")

    data class Net(val name: String, val own: Long, val first: Long, val last: Long)

    fun networks(): List<Net> {
        val nets = mutableListOf<Net>()
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (ni in all) {
            if (!runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)) continue
            if (skip.any { ni.name.startsWith(it) }) continue
            for (ia in ni.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                if (!addr.isSiteLocalAddress) continue                   // 10/8, 172.16/12, 192.168/16 only
                val prefix = maxOf(ia.networkPrefixLength.toInt(), 24)   // a wide network: just the 254 around us
                val ip = toLong(addr)
                val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val net = ip and mask
                val bcast = net or (mask.inv() and 0xFFFFFFFFL)
                if (bcast - net >= 2) nets += Net(ni.name, ip, net + 1, bcast - 1)
            }
        }
        return nets
    }

    suspend fun scan(onProgress: (Int, Int) -> Unit): List<Hit> = coroutineScope {
        val seen = HashSet<Long>()
        val hosts = networks().flatMap { n -> (n.first..n.last).filter { it != n.own && seen.add(it) } }
        val done = AtomicInteger()
        val gate = Semaphore(48)
        val hits = hosts.map { a ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val kind = probe(fromLong(a))
                    onProgress(done.incrementAndGet(), hosts.size)
                    if (kind > 0) Hit(fromLong(a).hostAddress ?: "", kind == 2) else null
                }
            }
        }.awaitAll().filterNotNull()
        hits.sortedWith(compareByDescending<Hit> { it.adb }.thenBy { toLong(InetAddress.getByName(it.ip) as Inet4Address) })
    }

    /** 0 = nothing there, 1 = port open but not answering like ADB, 2 = ADB. */
    suspend fun probe(host: InetAddress): Int = withContext(Dispatchers.IO) {
        var connected = false
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, PORT), 700)
                connected = true
                s.soTimeout = 1000
                s.getOutputStream().apply { write(packet(CNXN, 0x01000000, 4096, "host::\u0000".toByteArray())); flush() }
                val header = ByteArray(24)
                var got = 0
                val input = s.getInputStream()
                while (got < 4) {
                    val n = input.read(header, got, header.size - got)
                    if (n < 0) break
                    got += n
                }
                if (got < 4) return@use 1
                val cmd = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8) or
                    ((header[2].toInt() and 0xFF) shl 16) or ((header[3].toInt() and 0xFF) shl 24)
                if (cmd == AUTH || cmd == CNXN || cmd == STLS) 2 else 1
            }
        } catch (e: IOException) {
            if (connected) 1 else 0
        }
    }

    /** An ADB message: 24-byte little-endian header (command, two args, length, byte sum, magic), then the data. */
    private fun packet(cmd: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
        val p = ByteArray(24 + data.size)
        fun put(at: Int, v: Int) { for (i in 0 until 4) p[at + i] = (v ushr (8 * i)).toByte() }
        put(0, cmd); put(4, arg0); put(8, arg1); put(12, data.size); put(16, data.sumOf { it.toInt() and 0xFF }); put(20, cmd.inv())
        data.copyInto(p, 24)
        return p
    }

    private fun toLong(a: Inet4Address): Long = a.address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    private fun fromLong(v: Long): InetAddress =
        InetAddress.getByAddress(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
}

object Releases {
    class Release(val tag: String, val name: String, val url: String, val size: Long)

    suspend fun latest(repo: String): Release = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "SharkHubInstaller")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode == 404) throw IOException("No Shark Hub release has been published at github.com/$repo yet.")
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
        // A release also carries this installer's own APK: take Shark Hub's, by name, else the first APK.
        val a = apks.firstOrNull { val n = it.getString("name").lowercase(); "sharkhub" in n && "installer" !in n } ?: apks.firstOrNull()
            ?: throw IOException("The latest release at github.com/$repo has no APK attached.")
        Release(json.optString("tag_name"), a.getString("name"), a.getString("browser_download_url"), a.getLong("size"))
    }

    suspend fun download(r: Release, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, r.name)
        if (file.exists() && file.length() == r.size) return@withContext file
        val part = File(dir, r.name + ".part")
        val c = URL(r.url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "SharkHubInstaller")
        c.instanceFollowRedirects = true
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
        c.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var sum = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sum += n
                    if (total > 0) onProgress(sum / total.toFloat())
                }
            }
        }
        file.delete()
        if (!part.renameTo(file)) throw IOException("Couldn't save the download.")
        file
    }
}

/**
 * Everything the screen shows and does. A ViewModel, so an install carries on through a rotation;
 * the ADB key lives in the app's files so "Always allow" on the car keeps working.
 */
class InstallerViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("installer", Context.MODE_PRIVATE)

    var ip by mutableStateOf(prefs.getString("ip", "") ?: "")
        private set
    var apk by mutableStateOf<File?>(null)
        private set
    var apkName by mutableStateOf<String?>(null)
        private set
    var apkInfo by mutableStateOf("Download the latest release, or pick an APK on this phone.")
        private set
    var busy by mutableStateOf(false)
        private set
    var installing by mutableStateOf(false)
        private set
    var status by mutableStateOf<Pair<Mood, String>?>(null)
        private set
    /** null = no bar; a value in 0..1 fills it; -1 = sliding (indeterminate). */
    var progress by mutableStateOf<Float?>(null)
        private set
    val found = mutableStateListOf<Hit>()
    val log = mutableStateListOf<String>()
    private var apkIsSharkHub = false

    private val keyPair: AdbKeyPair by lazy {
        val priv = File(app.filesDir, "adbkey")
        val pub = File(app.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        AdbKeyPair.read(priv, pub)
    }

    fun onIp(v: String) { ip = v }

    /** Host and port from the box — 5555 when none is given — or null when it isn't an IPv4 address. */
    fun target(): Pair<String, Int>? {
        val m = Regex("""^\s*(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:(\d{1,5}))?\s*$""").matchEntire(ip) ?: return null
        val parts = (1..4).map { m.groupValues[it].toInt() }
        if (parts.any { it > 255 }) return null
        val port = m.groupValues[6].toIntOrNull() ?: 5555
        if (port !in 1..65535) return null
        return parts.joinToString(".") to port
    }

    val canInstall: Boolean get() = !busy && target() != null && apk?.exists() == true

    // ---- the app --------------------------------------------------------------------------------

    fun latest() {
        val repo = prefs.getString("repo", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_REPO
        if (repo.isBlank()) {
            say(Mood.Info, "Shark Hub's GitHub releases aren't published yet. Choose an APK file for now.")
            return
        }
        work {
            say(Mood.Busy, "Looking for the latest Shark Hub…")
            progress = -1f
            val rel = Releases.latest(repo)
            note("Latest release ${rel.tag}: ${rel.name} (${mb(rel.size)})")
            say(Mood.Busy, "Downloading Shark Hub ${rel.tag}…")
            progress = 0f
            val file = Releases.download(rel, File(getApplication<Application>().filesDir, "downloads")) { progress = it }
            setApk(file, file.name, "${mb(rel.size)} · Shark Hub ${rel.tag} from GitHub", true)
            say(Mood.Good, "Shark Hub ${rel.tag} is ready to install.")
        }
    }

    fun picked(uri: Uri) {
        work {
            val app = getApplication<Application>()
            var name: String? = null
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
            val shown = name ?: "picked.apk"
            if (!shown.endsWith(".apk", ignoreCase = true)) {
                say(Mood.Bad, "That isn't an APK file.")
                return@work
            }
            say(Mood.Busy, "Reading $shown…")
            progress = -1f
            val dest = File(File(app.cacheDir, "picked").apply { mkdirs() }, "chosen.apk")
            withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                    ?: throw IOException("Couldn't open that file.")
            }
            setApk(dest, shown, "${mb(dest.length())} · from this phone", shown.contains("shark", ignoreCase = true))
            status = null
        }
    }

    private fun setApk(file: File, name: String, info: String, sharkHub: Boolean) {
        apk = file
        apkName = name
        apkInfo = info
        apkIsSharkHub = sharkHub
    }

    // ---- finding the car ------------------------------------------------------------------------

    fun find() {
        found.clear()
        work {
            say(Mood.Busy, "Looking for the car on this phone's hotspot and Wi-Fi…")
            Finder.networks().forEach { note("scan ${it.name}: ${longIp(it.first)} – ${longIp(it.last)}") }
            progress = 0f
            val hits = Finder.scan { d, n -> progress = if (n > 0) d / n.toFloat() else 1f }
            hits.forEach { note("found ${it.ip}" + if (it.adb) " (ADB)" else " (port 5555 open, no ADB reply)") }
            val adb = hits.filter { it.adb }
            val list = adb.ifEmpty { hits }
            when (list.size) {
                0 -> say(Mood.Bad, "No car found. Check ADB is switched on (step 3) and the car is on this phone's hotspot or Wi-Fi (step 4).")
                1 -> {
                    ip = list[0].ip
                    say(Mood.Good, if (list[0].adb) "Found the car at ${list[0].ip}." else "Something at ${list[0].ip} has the ADB port open. It's probably the car.")
                }
                else -> {
                    found.addAll(list)
                    say(Mood.Info, "Found ${list.size} devices with ADB switched on. Tap the one that's the car.")
                }
            }
        }
    }

    fun choose(hit: Hit) { ip = hit.ip }

    // ---- install --------------------------------------------------------------------------------

    fun install() {
        val t = target() ?: return
        val file = apk ?: return
        prefs.edit().putString("ip", ip.trim()).apply()
        work {
            installing = true
            val (host, port) = t
            say(Mood.Busy, "Connecting to the car at $host:$port…")
            progress = -1f
            val dadb = withContext(Dispatchers.IO) { Dadb.create(host, port, keyPair, connectTimeout = 6000, socketTimeout = 0) }
            try {
                // The first time, the car asks "Allow USB debugging?" and the reply waits on that tap.
                val nudge = viewModelScope.launch {
                    delay(2500)
                    say(Mood.Info, "Look at the car: tap Allow on \"Allow USB debugging?\" (tick Always allow).")
                }
                val hello = viewModelScope.async(Dispatchers.IO) { dadb.shell("echo ok") }
                val ok = withTimeoutOrNull(120_000) { hello.await() }
                nudge.cancel()
                if (ok == null) {
                    runCatching { dadb.close() }
                    hello.cancel()
                    say(Mood.Bad, "The car didn't allow the connection. Press Install again and tap Allow on the car's screen.")
                    return@work
                }
                note("> echo ok: ${ok.allOutput.trim()}")
                say(Mood.Busy, "Sending $apkName (${mb(file.length())}) to the car. This takes a minute or so over Wi-Fi…")
                withContext(Dispatchers.IO) { dadb.install(file, "-r", "-g") }
                note("installed ${file.name}")
                if (apkIsSharkHub) {
                    val open = withContext(Dispatchers.IO) { dadb.shell("monkey -p $SHARK_HUB_PACKAGE -c android.intent.category.LAUNCHER 1") }
                    note("> open Shark Hub: ${open.allOutput.trim()}")
                    say(Mood.Good, "Installed. Shark Hub is opening on the car.")
                } else {
                    say(Mood.Good, "Installed on the car.")
                }
            } finally {
                runCatching { withContext(Dispatchers.IO) { dadb.close() } }
            }
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------

    /** Runs one job at a time with the inputs locked, and turns failures into a plain sentence. */
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                say(Mood.Bad, friendly(e))
                note(e.stackTraceToString())
            } finally {
                busy = false
                installing = false
                progress = null
            }
        }
    }

    private fun friendly(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        val code = Regex("INSTALL_[A-Z_]+").find(msg)?.value
        val t = target()
        val at = t?.let { "${it.first}:${it.second}" } ?: "that address"
        return when {
            code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -> "The car has a copy of this app signed with a different key. Uninstall it on the car, then install again."
            code == "INSTALL_FAILED_VERSION_DOWNGRADE" -> "The car already has a newer version of this app."
            code == "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "There isn't enough free storage on the car."
            code != null && (code.startsWith("INSTALL_PARSE_FAILED") || code == "INSTALL_FAILED_INVALID_APK") -> "That file isn't a valid Android app."
            code != null -> "The car refused the install ($code)."
            e is ConnectException -> "The car at $at isn't accepting ADB. Check ADB is switched on (step 3)."
            e is SocketTimeoutException || e is NoRouteToHostException -> "Couldn't reach the car at $at. Check it's on this phone's hotspot or the same Wi-Fi."
            e is UnknownHostException -> "This phone seems to be offline."
            else -> msg
        }
    }

    private fun say(mood: Mood, text: String) {
        status = mood to text
        note(text)
    }

    private fun note(line: String) {
        log += SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + line.trim()
        if (log.size > 300) log.removeRange(0, log.size - 300)
    }

    companion object {
        fun mb(bytes: Long) = "%.1f MB".format(Locale.US, bytes / 1048576.0)
        fun longIp(v: Long) = "${v shr 24 and 0xFF}.${v shr 16 and 0xFF}.${v shr 8 and 0xFF}.${v and 0xFF}"
    }
}
