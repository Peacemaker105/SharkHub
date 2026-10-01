package com.chris.sharkhub.bake

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.chris.sharkhub.car.kanzi.RigNeeds
import com.chris.sharkhub.car.kanzi.SharkDecoder
import com.chris.sharkhub.data.Prefs
import com.chris.sharkhub.ui.overview.CarArtStore
import com.chris.sharkhub.util.Json
import com.chris.sharkhub.util.Json.asList
import com.chris.sharkhub.util.Json.asObject
import com.chris.sharkhub.util.Json.asString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.URLEncoder

enum class BakePhase(val label: String, val busy: Boolean) {
    IDLE("Not started", false),
    PROBE("Checking the display's WebGL", true),
    DECODE("Reading the car's own model", true),
    RENDER("Rendering the layers", true),
    PACK("Packing the set", true),
    DONE("Done", false),
    FAILED("Failed", false),
    CANCELLED("Cancelled", false),
}

/** What the bake screen shows: the phase, a one-line message, overall progress and the recent log. */
data class BakeState(
    val phase: BakePhase = BakePhase.IDLE,
    val message: String = "",
    /** 0–1 across the whole bake (decode ≈ 15 %, render ≈ 70 %, pack ≈ 15 %). */
    val fraction: Float = 0f,
    val layersDone: Int = 0,
    val layersTotal: Int = 0,
    val probe: String? = null,
    val log: List<String> = emptyList(),
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val error: String? = null,
    val driveline: Boolean = true,
) {
    val busy: Boolean get() = phase.busy
}

/** The render page's parameters for a bake: what the PC pipeline uses, at a canvas the unit can afford. */
data class BakeOptions(
    val width: Int = 1920,
    val height: Int = 1400,
    val shadowMap: Int = 2048,
    val times: List<String> = listOf("day", "dawn", "dusk", "night"),
    val wheelTimes: List<String> = listOf("night"),
    val phases: Int = 12,
    val views: Boolean = true,
    val extras: Boolean = true,
    val tag: String = "v2",
    val renderTimeoutMs: Long = 40 * 60_000L,
) {
    /** How many PNG layers the page will save — body, shells, drive, wheels, plates, views, lamps, blur plates. */
    val expectedLayers: Int get() {
        var n = 1 + 3 + 1 + 4 * phases                                   // ghost body, three shell layers, drive, base wheels
        n += times.size * 2                                               // plate + wide plate per time
        n += (times.size - 1).coerceAtLeast(0) * 3                        // shells for the non-base times
        n += wheelTimes.count { it != "day" } * 4 * phases                // lit wheels
        if (views) n += 9
        if (extras) n += 8 + times.size * 2                               // lamp groups (the reverse one may be empty), blur plates
        return n
    }
}

/**
 * Builds the dashboard truck from the owner's own head-unit files, on the car, in four phases:
 * a WebGL probe of the unit's WebView, the Kanzi decode (BYD's APK → GLBs, textures, HDR), the
 * render of render_v2.html in that WebView (every layer saved through the bridge), and the pack into
 * `filesDir/car_bake`, swapped in whole so the previous set stays until the new one is complete. The
 * public app ships none of BYD's art: it ships the recipe, and each owner's unit runs it on its own
 * files. Progress is W-level logcat (`SharkHubBake`) because this unit keeps no info lines.
 */
object BakeRunner {
    const val TAG = "SharkHubBake"
    /** Bump when render_v2.html or PackV2 change the look: a set baked by an older renderer then asks to be rebuilt. */
    const val RENDERER_VERSION = 1

    private val _state = MutableStateFlow(BakeState())
    val state: StateFlow<BakeState> get() = _state
    private val webView = MutableStateFlow<WebView?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var renderDone: CompletableDeferred<Unit>? = null
    private var probeResult: CompletableDeferred<String>? = null
    private var layerCount = 0

    val running: Boolean get() = _state.value.busy

    /** The screen's WebView, once it exists; the bake waits for one before probing or rendering. */
    fun attach(view: WebView) { webView.value = view }

    /** The screen is going away: a bake in its render phase can't continue without its WebView. */
    fun detach(view: WebView) {
        if (webView.value === view) {
            webView.value = null
            if (running) cancel("the bake screen was closed")
        }
    }

    fun cancel(reason: String = "cancelled") {
        if (!running) return
        log("cancelled: $reason")
        job?.cancel(CancellationException(reason))
        renderDone?.cancel(); probeResult?.cancel()
        scope.launch(Dispatchers.Main) { webView.value?.let { runCatching { it.stopLoading(); it.loadUrl("about:blank") } } }
        _state.value = _state.value.copy(phase = BakePhase.CANCELLED, message = reason, finishedAt = System.currentTimeMillis())
    }

    /** Clears a finished / failed state back to idle (the screen's "Rebuild" starts fresh). */
    fun reset() { if (!running) _state.value = BakeState() }

    // the bridge (a WebView thread) and the bake coroutine both report: updates are compare-and-swap, never lost
    private fun log(line: String) {
        Log.w(TAG, line)
        _state.update { it.copy(log = (it.log + line).takeLast(60)) }
    }

    private fun update(phase: BakePhase? = null, message: String? = null, fraction: Float? = null) {
        _state.update { s -> s.copy(phase = phase ?: s.phase, message = message ?: s.message, fraction = fraction ?: s.fraction) }
    }

    fun start(ctx: Context, options: BakeOptions = BakeOptions()) {
        if (running) return
        val app = ctx.applicationContext
        layerCount = 0
        _state.value = BakeState(phase = BakePhase.PROBE, message = "Waiting for the display", startedAt = System.currentTimeMillis(), layersTotal = options.expectedLayers)
        job = scope.launch {
            val files = BakeFiles(app)
            val t0 = System.currentTimeMillis()
            try {
                // --- 1. the WebView and its WebGL
                val view = webView.filterNotNull().first()
                val probe = probeWebGl(view, files)
                _state.value = _state.value.copy(probe = probe)
                log("webgl probe: $probe")
                val po = runCatching { Json.parse(probe).asObject() }.getOrNull()
                if (po == null || po["webgl"] != true) throw IllegalStateException("This display's WebView has no WebGL — the truck can't be rendered here")
                po["renderer"].asString()?.let { log("gpu: $it, max texture ${po["maxTexture"]}, webgl2 ${po["webgl2"]}, astc ${po["astc"]}") }

                // --- 2. decode the owner's files
                update(BakePhase.DECODE, "Finding the car's own model", 0.02f)
                val sources = CarSources.find(app)
                if (sources.myCarApk == null) throw IllegalStateException("BYD's My Car app (com.byd.mycar) isn't on this unit, so there's no model to build from")
                log("sources: ${sources.myCarApk} ; rage ${sources.rageDir ?: "absent"}")
                val rigText = app.assets.open("bake/rigs/byd_shark6.rig.json").bufferedReader().use { it.readText() }
                val needs = RigNeeds.fromRig(rigText)
                files.src.mkdirs()
                val decoded = withContext(Dispatchers.IO) {
                    SharkDecoder(sources.myCarApk, sources.rageDir, files.src, files.cache, needs,
                        log = ::log, progress = { msg, f -> update(message = msg, fraction = 0.02f + 0.13f * f) }).run()
                }
                decoded.notes.forEach { log(it) }
                if (decoded.missing.isNotEmpty()) log("not produced: ${decoded.missing}")
                _state.value = _state.value.copy(driveline = decoded.rageGlb != null)
                prepareRig(rigText, files.src, decoded.rageGlb != null)

                // --- 3. render in the WebView
                files.render.deleteRecursively(); files.render.mkdirs()
                update(BakePhase.RENDER, "Starting the renderer", 0.15f)
                val done = CompletableDeferred<Unit>(); renderDone = done
                val url = renderUrl(options)
                log("render: $url")
                withContext(Dispatchers.Main) { view.loadUrl(url) }
                withTimeout(options.renderTimeoutMs) { done.await() }
                if (!File(files.render, "${options.tag}_meta.json").exists()) throw IllegalStateException("the renderer finished without writing ${options.tag}_meta.json")
                withContext(Dispatchers.Main) { runCatching { view.loadUrl("about:blank") } }

                // --- 4. pack and swap in
                update(BakePhase.PACK, "Packing the set", 0.86f)
                val v1 = runCatching { app.assets.open("car/v1_meta.json").bufferedReader().use { it.readText() } }.getOrNull()
                val packed = withContext(Dispatchers.IO) {
                    PackV2(files.render, files.pack, AndroidImageIo, options.tag, v1, log = ::log,
                        progress = { msg, f -> update(message = msg, fraction = 0.86f + 0.13f * f) }, checkCancelled = { if (job?.isActive == false) throw CancellationException() }).run()
                }
                if (packed.problems.isNotEmpty()) throw IllegalStateException("the set didn't check out: ${packed.problems.joinToString("; ")}")
                val secs = ((System.currentTimeMillis() - t0) / 1000).toInt()
                BakeInfo(RENDERER_VERSION, appVersionCode(app), System.currentTimeMillis(), intArrayOf(options.width, options.height), secs, packed.files, packed.bytes, packed.webp, decoded.rageGlb != null, probe).write(files.state)
                Prefs(app).bakeVersion = RENDERER_VERSION
                files.render.deleteRecursively()
                log("bake done in $secs s: ${packed.files} files, ${"%.1f".format(packed.bytes / 1048576.0)} MB")
                CarArtStore.reload(app)
                _state.value = _state.value.copy(phase = BakePhase.DONE, message = "The truck is built from this car's own model", fraction = 1f, finishedAt = System.currentTimeMillis())
            } catch (e: CancellationException) {
                if (_state.value.phase != BakePhase.CANCELLED) _state.value = _state.value.copy(phase = BakePhase.CANCELLED, message = e.message ?: "cancelled", finishedAt = System.currentTimeMillis())
            } catch (e: Throwable) {
                Log.w(TAG, "bake failed", e)
                log("failed: $e")
                _state.value = _state.value.copy(phase = BakePhase.FAILED, message = e.message ?: e.toString(), error = e.toString(), finishedAt = System.currentTimeMillis())
            }
        }
    }

    /** Loads the probe page and returns its JSON report; the bridge is the one the render then uses too. */
    private suspend fun probeWebGl(view: WebView, files: BakeFiles): String {
        val result = CompletableDeferred<String>(); probeResult = result
        withContext(Dispatchers.Main) {
            BakeWebView.configure(view, files, BakeBridge(
                renderDir = files.render,
                onProgress = { msg -> update(message = msg) },
                onSaved = { name, size -> onSaved(name, size) },
                onDone = { renderDone?.complete(Unit) },
                onFailed = { msg -> log("renderer failed: $msg"); renderDone?.completeExceptionally(IllegalStateException(msg)) },
                onProbe = { json -> probeResult?.complete(json) },
            ))
            view.loadUrl("${BakeWebView.ORIGIN}/bake/probe.html")
        }
        return withTimeout(30_000) { result.await() }
    }

    private fun onSaved(name: String, size: Long) {
        if (name.endsWith(".png")) {
            val n = ++layerCount
            _state.update { s ->
                val total = maxOf(s.layersTotal, n)
                s.copy(layersDone = n, layersTotal = total, fraction = 0.15f + 0.7f * n / total, message = "Rendered $n of $total layers")
            }
        } else if (name.endsWith("_done.json")) {
            renderDone?.complete(Unit)
        }
    }

    private fun renderUrl(o: BakeOptions): String {
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        return "${BakeWebView.ORIGIN}/bake/render_v2.html?rig=/rig.json&auto=1&fit=1&w=${o.width}&h=${o.height}&shadow=${o.shadowMap}" +
            "&times=${q(o.times.joinToString(","))}&wheelTimes=${q(o.wheelTimes.joinToString(","))}&phases=${o.phases}&tag=${q(o.tag)}" +
            (if (!o.views) "&views=0" else "") + (if (!o.extras) "&extras=0" else "")
    }

    /**
     * The rig the page gets: the asset copy with the parts this unit couldn't supply removed — HDR
     * cubes and panos whose files are missing (the page then keeps its procedural sky) and the chassis
     * when Rage Mode's files aren't on the unit (an empty x-ray driveline).
     */
    private fun prepareRig(rigText: String, src: File, hasDriveline: Boolean) {
        val rig = Json.parse(rigText).asObject() ?: error("rig")
        val tex = rig["textures"].asObject()
        if (tex != null) {
            tex["envCubes"].asObject()?.let { cubes ->
                val drop = cubes.filter { (_, v) ->
                    val c = v.asObject() ?: return@filter true
                    val faces = c["faces"].asList()?.mapNotNull { it.asString() } ?: listOf("posX", "negX", "posY", "negY", "posZ", "negZ")
                    faces.any { f -> !File(src, ((c["prefix"].asString() ?: "") + f + (c["suffix"].asString() ?: "")).trimStart('/')).exists() }
                }.keys
                drop.forEach { cubes.remove(it); log("rig: env cube '$it' dropped (faces missing)") }
            }
            tex["panos"].asObject()?.let { panos ->
                val base = (tex["base"].asString() ?: "/").trim('/')
                val drop = panos.filter { (_, v) -> val f = v.asObject()?.get("file").asString(); f == null || !File(src, "$base/$f").exists() }.keys
                drop.forEach { panos.remove(it); log("rig: pano '$it' dropped (file missing)") }
            }
        }
        if (!hasDriveline) { rig.remove("chassis"); log("rig: no driveline on this unit") }
        File(src, "rig.json").writeText(Json.write(rig, indent = 1))
    }

    private fun appVersionCode(ctx: Context): Int = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt() }.getOrDefault(0)

    /** True when a baked set exists but was made by an older renderer than this build carries. */
    fun needsRebuild(ctx: Context): Boolean = runCatching {
        if (!BakeFiles.hasBake(ctx)) return false
        val info = BakeInfo.read(BakeFiles(ctx).state) ?: return true
        info.rendererVersion < RENDERER_VERSION
    }.getOrDefault(false)

    /** Removes the baked set and the decoded sources; the overview falls back to the bundled art. */
    fun remove(ctx: Context) {
        if (running) return
        val files = BakeFiles(ctx.applicationContext)
        files.pack.deleteRecursively(); files.root.deleteRecursively(); files.cache.deleteRecursively()
        Prefs(ctx).bakeVersion = 0
        CarArtStore.reload(ctx.applicationContext)
        _state.value = BakeState()
    }
}
