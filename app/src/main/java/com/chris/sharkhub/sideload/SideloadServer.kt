package com.chris.sharkhub.sideload

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny dependency-free HTTP server used for two things over the car's Wi-Fi, from a phone/PC
 * browser — no cable, no ADB, no SD card/USB needed:
 *
 *   • UPLOAD  (Sideload screen): open http://<car-ip>:<port>, drag in an APK, it installs here.
 *   • DOWNLOAD (Probe screen):   open the same address and tap a link to download a file served
 *                                straight from memory (e.g. the probe report) onto your phone.
 *
 * Uploads are enabled only when an [onApkReceived] handler is supplied. Downloads are whatever you
 * register with [addDownload]; each is generated on request by a provider lambda, so nothing has to
 * be written to disk.
 *
 * SECURITY: no auth. Only run it while you're actively transferring, on a network you trust. The
 * screens stop it automatically when you leave.
 */
class SideloadServer(
    private val ctx: Context,
    private val onApkReceived: ((File) -> Unit)? = null,
    private val onLog: (String) -> Unit = {},
) {
    private val TAG = "SharkHub/Server"
    @Volatile private var running = false
    private var server: ServerSocket? = null
    var port: Int = 0; private set

    data class Download(val filename: String, val contentType: String, val provider: () -> ByteArray)

    private val downloads = LinkedHashMap<String, Download>()

    /** Register a downloadable file served at /[path]. Generated fresh on each request. */
    fun addDownload(path: String, filename: String, contentType: String, provider: () -> ByteArray) {
        downloads[path.trimStart('/')] = Download(filename, contentType, provider)
    }

    fun start(preferredPort: Int = 8080): Boolean {
        if (running) return true
        for (p in preferredPort..(preferredPort + 10)) {
            try {
                server = ServerSocket(p); port = p; running = true; break
            } catch (e: Exception) { /* port busy, try next */ }
        }
        if (!running) { onLog("could not bind a port"); return false }
        onLog("listening on port $port")
        thread(name = "sharkhub-http") { acceptLoop() }
        return true
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop() {
        while (running) {
            val sock = runCatching { server?.accept() }.getOrNull() ?: continue
            thread { runCatching { handle(sock) }.onFailure { Log.w(TAG, "handle: ${it.message}") } }
        }
    }

    private fun handle(sock: Socket) = sock.use {
        // A client that connects and then goes quiet would otherwise pin this thread forever.
        sock.soTimeout = 30_000
        val input = sock.getInputStream()
        val (requestLine, headers) = readHeaders(input)
        val out = BufferedOutputStream(sock.getOutputStream())

        val parts = requestLine.split(" ")
        val method = parts.getOrElse(0) { "" }
        val rawPath = parts.getOrElse(1) { "/" }
        val path = rawPath.substringBefore('?')
        val key = path.trimStart('/')

        when {
            method == "GET" && (path == "/" || path.startsWith("/index")) ->
                respond(out, 200, "text/html; charset=utf-8", indexPage().toByteArray())

            method == "GET" && downloads.containsKey(key) -> {
                val d = downloads.getValue(key)
                onLog("serving ${d.filename}")
                val body = runCatching { d.provider() }.getOrElse {
                    respond(out, 500, "text/plain", "generate failed: ${it.message}".toByteArray()); return@use
                }
                respond(out, 200, d.contentType, body,
                    extra = "Content-Disposition: attachment; filename=\"${d.filename}\"\r\n")
            }

            method == "POST" && onApkReceived != null && path.startsWith("/upload") -> {
                val name = Regex("name=([^&]+)").find(rawPath)?.groupValues?.get(1)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "upload.apk"
                val safe = name.substringAfterLast('/').ifBlank { "upload.apk" }
                    .let { if (it.endsWith(".apk", true)) it else "$it.apk" }
                val len = headers["content-length"]?.toLongOrNull() ?: -1L
                val dir = File(ctx.cacheDir, "incoming").apply { mkdirs() }
                val dest = File(dir, safe)
                onLog("receiving $safe (${if (len > 0) "${len / 1024} KB" else "unknown size"})")
                val got = copyExactly(input, dest, len)
                if (len > 0 && got != len) {
                    // Browser closed / Wi-Fi dropped mid-upload: don't hand a truncated APK to the installer.
                    dest.delete()
                    onLog("upload of $safe cut off at ${got / 1024} of ${len / 1024} KB — try again")
                    respond(out, 400, "application/json", """{"ok":false,"error":"incomplete upload"}""".toByteArray())
                    return@use
                }
                onLog("received $safe → installing")
                onApkReceived.invoke(dest)
                respond(out, 200, "application/json", """{"ok":true,"file":"$safe"}""".toByteArray())
            }

            else -> respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun readHeaders(input: InputStream): Pair<String, Map<String, String>> {
        val sb = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            last4 = ((last4 shl 8) or b) and 0xFFFFFFFF.toInt()
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = sb.toString().split("\r\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull() ?: ""
        val headers = lines.drop(1).mapNotNull {
            val i = it.indexOf(':'); if (i < 0) null
            else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
        }.toMap()
        return requestLine to headers
    }

    /** Copies [len] bytes (or to EOF when unknown) into [dest]; returns how many actually arrived. */
    private fun copyExactly(input: InputStream, dest: File, len: Long): Long {
        var total = 0L
        dest.outputStream().use { out ->
            val buf = ByteArray(128 * 1024)
            var remaining = len
            while (remaining != 0L) {
                val toRead = if (len < 0) buf.size else minOf(buf.size.toLong(), remaining).toInt()
                val r = input.read(buf, 0, toRead)
                if (r == -1) break
                out.write(buf, 0, r)
                total += r
                if (len > 0) remaining -= r
            }
        }
        return total
    }

    private fun respond(out: BufferedOutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 500 -> "Server Error"; else -> "OK"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" + extra +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray()); out.write(body); out.flush()
    }

    /** The landing page: upload form when uploads are on, plus any registered download links. */
    private fun indexPage(): String {
        val uploadSection = if (onApkReceived != null) """
            <h2>Install an app</h2>
            <p>Pick an .apk and it installs on the head unit. Keep this tab open until the install
               dialog appears on the car screen.</p>
            <input id=f type=file accept=".apk,application/vnd.android.package-archive">
            <button onclick=up()>Upload &amp; install</button>
            <div class=bar id=bar><div class=fill id=fill></div></div>
        """ else ""

        val downloadSection = if (downloads.isNotEmpty()) {
            val links = downloads.entries.joinToString("") { (path, d) ->
                "<a class=dl href=\"/$path\" download=\"${d.filename}\">⬇ ${d.filename}</a>"
            }
            "<h2>Downloads</h2><p>Tap to save to this device, then send it on.</p>$links"
        } else ""

        return """
            <!doctype html><html><head><meta charset=utf-8>
            <meta name=viewport content="width=device-width,initial-scale=1">
            <title>Shark Hub</title>
            <style>
              body{font-family:system-ui,sans-serif;background:#0B0D10;color:#F2F4F8;margin:0;
                   display:flex;min-height:100vh;align-items:center;justify-content:center}
              .card{background:#1D2128;padding:32px;border-radius:20px;max-width:440px;width:90%}
              h1{color:#3DDC97;margin:0 0 4px;font-size:22px}
              h2{font-size:16px;margin:20px 0 6px}
              p{color:#9AA3B2;font-size:14px;line-height:1.5;margin:.3em 0}
              input[type=file]{width:100%;margin:14px 0;color:#F2F4F8}
              button,.dl{background:#3DDC97;color:#07120C;border:0;border-radius:12px;padding:14px 20px;
                     font-size:16px;font-weight:600;width:100%;cursor:pointer;display:block;
                     text-align:center;text-decoration:none;box-sizing:border-box;margin-top:8px}
              .bar{height:8px;background:#272C35;border-radius:6px;overflow:hidden;margin-top:12px;display:none}
              .fill{height:100%;width:0;background:#3DDC97;transition:width .1s}
              #log{margin-top:14px;font-size:13px;color:#9AA3B2;white-space:pre-wrap}
            </style></head><body>
            <div class=card>
              <h1>Shark Hub</h1>
              $downloadSection
              $uploadSection
              <div id=log></div>
            </div>
            <script>
              function up(){
                var f=document.getElementById('f').files[0];
                if(!f){log('choose a file first');return;}
                var bar=document.getElementById('bar'),fill=document.getElementById('fill');
                bar.style.display='block';
                var x=new XMLHttpRequest();
                x.open('POST','/upload?name='+encodeURIComponent(f.name));
                x.upload.onprogress=function(e){if(e.lengthComputable)fill.style.width=(e.loaded/e.total*100)+'%';};
                x.onload=function(){log('sent — confirm the install on the car screen.');};
                x.onerror=function(){log('upload failed');};
                log('uploading '+f.name+'…'); x.send(f);
              }
              function log(s){document.getElementById('log').textContent=s;}
            </script></body></html>
        """.trimIndent()
    }

    companion object {
        /**
         * Best-effort LAN IPv4 for display ("open this on your phone"). Head units are multi-homed
         * (cellular modem, internal vehicle Ethernet, Wi-Fi client, hotspot), so rank Wi-Fi-looking
         * interfaces first instead of taking whichever enumerates first.
         */
        fun localIpv4(): String? = localIpv4s().firstOrNull()

        /**
         * Every LAN-reachable IPv4, best first. Show them all: the Shark 6 can be on your phone's
         * hotspot *and* running its own, and only one of those is the network you're on.
         */
        fun localIpv4s(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
                .filter { (ifName, _) -> interfaceRank(ifName) < 9 }
                .sortedBy { (ifName, _) -> interfaceRank(ifName) }
                .mapNotNull { it.second.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())

        private fun interfaceRank(name: String): Int = when {
            name.startsWith("wlan") -> 0                                  // joined your Wi-Fi / phone hotspot
            name.startsWith("swlan") || name.startsWith("ap") ||
                name.startsWith("softap") -> 1                            // the car's own hotspot
            name.startsWith("rmnet") || name.startsWith("ccmni") -> 9     // cellular: unreachable from the LAN
            name.startsWith("eth") -> 5                                   // often the internal vehicle network
            else -> 3
        }
    }
}
