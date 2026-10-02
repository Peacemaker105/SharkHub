package com.chris.sharkhub.bake

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64

/**
 * The WebView that renders the truck. Everything it loads comes from inside the app: the page, the
 * probe and the vendored three.js from the APK's `bake/` assets at `https://bake.sharkhub/bake/…`,
 * the model root (GLBs, textures, HDR faces, the trimmed rig) from `filesDir/bake/src` at the
 * origin's root — the same layout serve.js gives the PC. Nothing is fetched from the network. The
 * page talks back through [BakeBridge] (`window.sharkhub`): progress lines, each saved file, done.
 */
object BakeWebView {
    const val ORIGIN = "https://bake.sharkhub"
    private const val TAG = BakeRunner.TAG

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView, files: BakeFiles, bridge: BakeBridge) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = false      // the fake origin is intercepted below; real network loads never happen
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
        }
        view.setBackgroundColor(0xFF0B1118.toInt())
        view.addJavascriptInterface(bridge, "sharkhub")
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                // the head unit's logcat keeps no info-level lines: everything is a warning
                Log.w(TAG, "js ${m.messageLevel()} ${m.sourceId().substringAfterLast('/')}:${m.lineNumber()} ${m.message()}")
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) bridge.log("js error: ${m.message()}")
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != "bake.sharkhub") return notFound("off-limits ${url.host}")
                val path = url.path ?: "/"
                return serve(v.context, files, path)
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                Log.w(TAG, "load error ${error.errorCode} ${error.description} for ${request.url}")
            }
        }
    }

    private fun serve(ctx: Context, files: BakeFiles, path: String): WebResourceResponse {
        val mime = mimeOf(path)
        return if (path.startsWith("/bake/")) {
            runCatching { ctx.assets.open(path.removePrefix("/")) }.getOrNull()?.let { WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json")) "utf-8" else null, it) }
                ?: notFound(path)
        } else {
            // the model root: only files below bake/src, never anything else in filesDir
            val f = File(files.src, path.removePrefix("/")).canonicalFile
            if (!f.path.startsWith(files.src.canonicalPath) || !f.isFile) notFound(path)
            else WebResourceResponse(mime, if (mime.endsWith("json")) "utf-8" else null, f.inputStream().buffered(1 shl 16))
        }
    }

    private fun notFound(what: String): WebResourceResponse {
        Log.w(TAG, "404 $what")
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Content-Type" to "text/plain"), ByteArrayInputStream("not found: $what".toByteArray()))
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"       // module scripts are refused without a JavaScript MIME type
        "json" -> "application/json"
        "glb" -> "model/gltf-binary"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "hdr" -> "application/octet-stream"
        "css" -> "text/css"
        else -> "application/octet-stream"
    }
}

/**
 * `window.sharkhub` as the page sees it. Calls arrive on a WebView background thread, so file writes
 * happen right here and the runner hears about them through callbacks.
 */
class BakeBridge(
    private val renderDir: File,
    private val onProgress: (String) -> Unit,
    private val onSaved: (String, Long) -> Unit,
    private val onDone: () -> Unit,
    private val onFailed: (String) -> Unit,
    private val onProbe: (String) -> Unit,
) {
    @JavascriptInterface
    fun progress(text: String) = onProgress(text)

    @JavascriptInterface
    fun log(text: String) { Log.w(BakeRunner.TAG, "page: $text") }

    /** Writes a file into the render folder: a data URL (PNG/JPEG) or raw text (JSON). Returns serve.js's status line. */
    @JavascriptInterface
    fun save(name: String, body: String): String {
        val safe = File(name).name
        val bytes = if (body.startsWith("data:")) Base64.getDecoder().decode(body.substringAfter(',')) else body.toByteArray(Charsets.UTF_8)
        renderDir.mkdirs()
        val f = File(renderDir, safe)
        f.writeBytes(bytes)
        onSaved(safe, bytes.size.toLong())
        return "ok $safe ${bytes.size}"
    }

    @JavascriptInterface
    fun done() = onDone()

    @JavascriptInterface
    fun failed(message: String) = onFailed(message)

    @JavascriptInterface
    fun probe(json: String) = onProbe(json)
}
