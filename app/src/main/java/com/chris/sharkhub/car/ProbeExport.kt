package com.chris.sharkhub.car

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Builds a full probe report (backend, every method signature, native packages, a telemetry
 * snapshot) and gets it off the head unit so it can be pasted/uploaded back for wiring up mappings.
 *
 * Ways out:
 *   1. Wi-Fi export — the Service Probe screen serves it for a phone/PC browser.
 *   2. adb          — `adb shell am broadcast -n com.chris.sharkhub/.car.ProbeReceiver` writes it to
 *                     [adbPullPath] without touching the screen; then `adb pull` it.
 *   3. adb logcat   — "Dump to logcat" (tag "SharkHubProbe"); the Shark 6 build drops app info logs.
 *   4. Share sheet  — helper kept for units with a share target.
 */
object ProbeExport {

    private const val TAG = "SharkHubProbe"

    /** Blocking (waits for discovery, then reads every device) — call off the main thread. */
    fun build(ctx: Context, car: CarManager): String {
        car.awaitDiscovery()
        val root = JSONObject()
        root.put("app", "Shark Hub")
        root.put("generatedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        root.put("androidSdk", android.os.Build.VERSION.SDK_INT)
        root.put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        root.put("fingerprint", android.os.Build.FINGERPRINT)
        root.put("backend", car.backendName)

        // Why each discovery candidate did or didn't connect — the key clue when backend is "none".
        val attempts = JSONArray()
        for (a in car.discoveryAttempts) {
            attempts.put(JSONObject().put("candidate", a.candidate).put("result", a.result))
        }
        root.put("discovery", attempts)

        val methods = car.probeSignatures()
        root.put("methodCount", methods.size)
        root.put("methods", JSONArray(methods))

        // Per device (BYD: ac, seat, statistic…): permissions, constants and current getter values.
        val devices = JSONObject()
        for ((key, obj) in car.devices) devices.put(key, deviceReport(obj))
        root.put("devices", devices)

        val pkgs = JSONObject()
        for (app in NativeApp.entries) pkgs.put(app.pkg, NativeApp.installed(ctx, app))
        root.put("nativePackages", pkgs)

        // Sentry-mode go/no-go: can this app reach Qualcomm's QCarCam client? (docs/CAMERAS_SENTRY.md)
        root.put("cameraNative", JSONObject()
            .put("inProcess", NativeCamProbe.inProcess())
            .put("sidecar", NativeCamProbe.sidecar(ctx)))
        root.put("installedPackages", installedPackages(ctx))

        val t = car.readTelemetry()
        val tj = JSONObject()
        tj.put("socPercent", t.socPercent ?: JSONObject.NULL)
        tj.put("evRangeKm", t.evRangeKm ?: JSONObject.NULL)
        tj.put("fuelPercent", t.fuelPercent ?: JSONObject.NULL)
        tj.put("fuelRangeKm", t.fuelRangeKm ?: JSONObject.NULL)
        tj.put("totalRangeKm", t.totalRangeKm ?: JSONObject.NULL)
        tj.put("odometerKm", t.odometerKm ?: JSONObject.NULL)
        tj.put("speedKph", t.speedKph ?: JSONObject.NULL)
        tj.put("outsideTempC", t.outsideTempC ?: JSONObject.NULL)
        root.put("telemetry", tj)

        return root.toString(2)
    }

    // Small ints to try on one-argument getters (zones, seats, outlets): read-only, and the answers
    // show which argument values mean something on this firmware.
    private val PROBE_ARGS = 0..4
    private const val CALL_TIMEOUT_MS = 1000L
    private const val MAX_TIMEOUTS_PER_DEVICE = 2
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "sharkhub-probe-call").apply { isDaemon = true } }

    /**
     * Everything readable about one device: its public constants and the result of every getter that
     * takes no argument or a single int (tried with [PROBE_ARGS]) — including getGetPermission /
     * getSetPermission, which name the permission each device checks. Getters only: nothing here
     * changes the car. Calls are capped at [CALL_TIMEOUT_MS] and a device that keeps timing out is
     * abandoned, so a stuck binder call can't hang the report.
     */
    private fun deviceReport(obj: Any): JSONObject {
        val cls = obj.javaClass
        val constants = JSONObject()
        cls.fields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) }
            .filter { it.type.isPrimitive || it.type == String::class.java }
            .sortedBy { it.name }
            .forEach { f -> runCatching { constants.put(f.name, jsonValue(f.get(null))) } }

        val values = JSONObject()
        var timeouts = 0
        val getters = cls.methods
            .filter { it.name.startsWith("get") || it.name.startsWith("is") }
            .filter { it.returnType != Void.TYPE && !Modifier.isStatic(it.modifiers) }
            .sortedBy { it.name }
        for (m in getters) {
            if (timeouts >= MAX_TIMEOUTS_PER_DEVICE) {
                values.put("_aborted", "stopped after $timeouts timeouts")
                break
            }
            val arity = m.parameterTypes.size
            if (arity == 0) {
                val v = timed { m.invoke(obj) }
                if (v == "timeout") timeouts++
                values.put(m.name, v)
            } else if (arity == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                val per = JSONObject()
                for (a in PROBE_ARGS) {
                    val v = timed { m.invoke(obj, a) }
                    if (v == "timeout") timeouts++
                    per.put("$a", v)
                }
                values.put("${m.name}(int)", per)
            }
        }
        return JSONObject().put("class", cls.name).put("constants", constants).put("values", values)
    }

    private fun timed(block: () -> Any?): Any = try {
        jsonValue(pool.submit(Callable { block() }).get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS))
    } catch (e: TimeoutException) {
        "timeout"
    } catch (e: ExecutionException) {
        var c: Throwable = e.cause ?: e
        while (c is InvocationTargetException && c.targetException != null) c = c.targetException
        "error: ${c.javaClass.simpleName}: ${c.message}"
    }

    private fun jsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is IntArray -> JSONArray(v.toList())
        is LongArray -> JSONArray(v.toList())
        is FloatArray -> JSONArray(v.map { jsonValue(it) })
        is DoubleArray -> JSONArray(v.map { jsonValue(it) })
        is ByteArray -> v.joinToString("") { "%02x".format(it) }
        is Array<*> -> JSONArray(v.map { jsonValue(it) })
        is Float -> if (v.isNaN() || v.isInfinite()) v.toString() else v.toDouble()
        is Double -> if (v.isNaN() || v.isInfinite()) v.toString() else v
        is Number, is Boolean, is String -> v
        else -> v.toString()
    }

    /**
     * Every installed package with its launcher activity, BYD/DiLink ones first — the raw material
     * for the DeepLinks table. Needs QUERY_ALL_PACKAGES: on API 30+ other apps are otherwise hidden
     * and this (and NativeApp.installed) would report nothing.
     */
    private fun installedPackages(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val all = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        val vendor = Regex("byd|dilink", RegexOption.IGNORE_CASE)
        val out = JSONArray()
        all.sortedWith(compareBy({ !vendor.containsMatchIn(it.packageName) }, { it.packageName }))
            .forEach { p ->
                val launcher = runCatching {
                    pm.getLaunchIntentForPackage(p.packageName)?.component?.flattenToShortString()
                }.getOrNull()
                val system = ((p.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
                out.put(JSONObject()
                    .put("pkg", p.packageName)
                    .put("system", system)
                    .put("launcher", launcher ?: JSONObject.NULL))
            }
        return out
    }

    /** Writes the report to the app's external files dir and returns the file. */
    fun writeFile(ctx: Context, report: String): File {
        val dir = File(ctx.getExternalFilesDir(null), "export").apply { mkdirs() }
        val f = File(dir, "sharkhub-probe.json")
        f.writeText(report)
        return f
    }

    /** The path to show on screen for `adb pull`. */
    fun adbPullPath(ctx: Context): String =
        "/sdcard/Android/data/${ctx.packageName}/files/export/sharkhub-probe.json"

    /** Chunked logcat dump (logcat truncates long lines, so split it). */
    fun toLogcat(report: String) {
        report.chunked(2000).forEachIndexed { i, chunk -> Log.i(TAG, "[$i] $chunk") }
    }

    /** Fire the Android share sheet with the file attached (and text as a fallback). */
    fun share(ctx: Context, file: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Shark Hub probe report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(send, "Send probe report")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
