package com.chris.sharkhub.car

import android.content.Context
import android.util.Log
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * A discovered way to reach the vehicle, plus reflective invoke helpers.
 *
 * Holds one or more target objects — a system service or manager, or on BYD firmware one instance per
 * `android.hardware.bydauto` device (ac, seat, statistic…) — and calls methods on them by name,
 * matching argument types loosely. This lets the same UI work across firmwares where the exact method
 * names differ — you only update the candidate lists in [CarManager].
 */
class CarBackend private constructor(
    val name: String,
    targets: Map<String, Any>,
    private val cleanup: (() -> Unit)? = null,
) {
    // getMethods() copies the whole array on every call and vendor devices expose hundreds of
    // methods, so index once per target — telemetry probes a dozen names every second. Immutable, so
    // it's safe to share between the UI, the telemetry poller and the probe's threads.
    private class Target(val key: String, val obj: Any) {
        val methodsByName: Map<String, List<Method>> =
            obj.javaClass.methods
                .filter { it.declaringClass != Any::class.java }
                .groupBy { it.name }
    }

    private val targets: List<Target> = targets.map { (k, v) -> Target(k, v) }

    /** The target objects by key, for the probe report. */
    val devices: Map<String, Any> get() = targets.associate { it.key to it.obj }

    /** All public methods (prefixed with their device when there are several), for the probe screen. */
    fun listMethods(): List<MethodInfo> =
        targets.flatMap { t ->
            t.methodsByName.values.flatten().map { m ->
                MethodInfo(
                    name = if (targets.size > 1) "${t.key}.${m.name}" else m.name,
                    params = m.parameterTypes.map { it.simpleName },
                    returns = m.returnType.simpleName
                )
            }
        }.sortedBy { it.name }

    /** Whether a target (the call's device, if it names one) has the method with that many arguments. */
    fun has(call: Call): Boolean = find(call) != null

    /**
     * Invoke [call] and return its result — null for a void method, which is why "does it exist?" is a
     * separate question ([has]). Throws [NoSuchMethodException] if nothing matches. If the call itself
     * blows up, the method's own exception is rethrown unwrapped, so e.g. a SecurityException from a
     * missing BYD permission reaches the UI instead of a bare "null".
     */
    fun invoke(call: Call): Any? {
        val (target, m) = find(call) ?: throw NoSuchMethodException("${call.describe()} on $name")
        return try {
            m.invoke(target.obj, *boxArgs(m, call.args))
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        } catch (e: IllegalArgumentException) {
            // Arity matched but the types didn't (e.g. Boolean into an int on/off flag).
            throw IllegalArgumentException(
                "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}) can't take " +
                    "(${call.args.joinToString { it?.javaClass?.simpleName ?: "null" }}) — fix the mapping in CarManager",
                e
            )
        }
    }

    private fun find(call: Call): Pair<Target, Method>? {
        for (t in targets) {
            if (call.device != null && t.key != call.device) continue
            val candidates = t.methodsByName[call.method].orEmpty().filter { it.parameterTypes.size == call.args.size }
            if (candidates.isEmpty()) continue
            // Prefer an assignable match; fall back to first with right arity.
            val m = candidates.firstOrNull { m ->
                m.parameterTypes.withIndex().all { (i, pt) -> assignable(pt, call.args[i]) }
            } ?: candidates.first()
            return t to m
        }
        return null
    }

    private fun assignable(paramType: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !paramType.isPrimitive
        val boxed = box(paramType)
        return boxed.isInstance(arg) ||
                (Number::class.java.isAssignableFrom(boxed) && arg is Number)
    }

    private fun boxArgs(m: Method, args: Array<out Any?>): Array<Any?> {
        // Coerce Number args to the exact primitive the method wants.
        return m.parameterTypes.mapIndexed { i, pt ->
            val a = args[i]
            when {
                a is Number && (pt == Int::class.javaPrimitiveType || pt == Integer::class.java) -> a.toInt()
                a is Number && (pt == Float::class.javaPrimitiveType || pt == java.lang.Float::class.java) -> a.toFloat()
                a is Number && (pt == Double::class.javaPrimitiveType || pt == java.lang.Double::class.java) -> a.toDouble()
                a is Number && (pt == Long::class.javaPrimitiveType || pt == java.lang.Long::class.java) -> a.toLong()
                else -> a
            }
        }.toTypedArray()
    }

    private fun box(c: Class<*>): Class<*> = when (c) {
        Int::class.javaPrimitiveType -> Integer::class.java
        Float::class.javaPrimitiveType -> java.lang.Float::class.java
        Double::class.javaPrimitiveType -> java.lang.Double::class.java
        Long::class.javaPrimitiveType -> java.lang.Long::class.java
        Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
        else -> c
    }

    fun close() { runCatching { cleanup?.invoke() } }

    companion object {
        private const val TAG = "SharkHub/Discover"

        // BYD firmware: the android.hardware.bydauto client library ships inside BYD's own apps rather
        // than the framework (Shark 6, SOC_260811_S: /system/app/BydHvac/BydHvac.apk), so we load it
        // from the first of these packages that's installed. Access is then gated by the
        // android.permission.BYDAUTO_* permissions declared in the manifest.
        private val BYD_CLIENT_PACKAGES = listOf("com.byd.hvac", "com.byd.autoservice")

        // Devices to open, keyed by the name CarManager's calls use.
        val BYD_DEVICES = linkedMapOf(
            "ac" to "android.hardware.bydauto.ac.BYDAutoAcDevice",
            "seat" to "android.hardware.bydauto.seat.BYDAutoSeatDevice",
            "statistic" to "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "energy" to "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "speed" to "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "instrument" to "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "bodywork" to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "charging" to "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            "sensor" to "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
            "tyre" to "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
            "setting" to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            "pm2p5" to "android.hardware.bydauto.pm2p5.BYDAutoPM2p5Device",
            "power" to "android.hardware.bydauto.power.BYDAutoPowerDevice",
            "gearbox" to "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "engine" to "android.hardware.bydauto.engine.BYDAutoEngineDevice",
            "motor" to "android.hardware.bydauto.motor.BYDAutoMotorDevice",
            "light" to "android.hardware.bydauto.light.BYDAutoLightDevice",
            // ADAS/cruise/high-beam/safety alerts, and camera-adjacent devices — opened so the probe
            // captures their live constants + values (see docs/VEHICLE_CONTROLS.md, docs/CAMERAS_SENTRY.md).
            "dipilot" to "android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice",
            "radar" to "android.hardware.bydauto.radar.BYDAutoRadarDevice",
            "collision" to "android.hardware.bydauto.collision.BYDAutoCollisionDevice",
            "dms" to "android.hardware.bydauto.dms.BYDAutoDmsDevice",
            "panorama" to "android.hardware.bydauto.panorama.BYDAutoPanoramaDevice",
        )

        // Candidate system-service names to probe via Context.getSystemService(String).
        private val SERVICE_NAMES = listOf(
            "byd_auto", "autoservice", "auto_service", "byd_car", "car_service", "BYDAutoService"
        )

        // Candidate manager classes, reached through a static factory (see FACTORY_NAMES, with or
        // without a Context argument) or else a (Context) constructor.
        private val MANAGER_CLASSES = listOf(
            "android.car.BYDAutoManager",
            "com.byd.auto.BYDAutoManager",
            "com.byd.autolink.AutoManager",
            "com.byd.dilink.car.CarManager",
        )

        private val FACTORY_NAMES = setOf("getInstance", "get", "getDefault", "create")

        /**
         * Try each strategy; the first that yields a usable object becomes the backend. Every
         * candidate's outcome is recorded so a "none" in the probe report says *why* (class missing,
         * no factory, SecurityException…). Everything is caught, so a locked-down unit simply gets
         * no backend (no crash). Blocking — loads classes and makes binder calls; keep it off the
         * main thread.
         */
        fun discover(ctx: Context): Discovery {
            val attempts = mutableListOf<DiscoveryAttempt>()
            // 0) BYD device API
            discoverByd(ctx, attempts)?.let { return Discovery(it, attempts) }
            // 1) getSystemService by name
            for (svc in SERVICE_NAMES) {
                val r = runCatching { ctx.getSystemService(svc) }
                attempts += DiscoveryAttempt("service:$svc", outcome(r))
                val obj = r.getOrNull()
                if (obj != null) {
                    Log.i(TAG, "Found via getSystemService(\"$svc\")")
                    return Discovery(CarBackend("systemService:$svc", mapOf("main" to obj)), attempts)
                }
            }
            // 2) manager classes
            for (cls in MANAGER_CLASSES) {
                val r = runCatching { instantiate(Class.forName(cls), ctx) }
                attempts += DiscoveryAttempt("class:$cls", outcome(r))
                val obj = r.getOrNull()
                if (obj != null) {
                    Log.i(TAG, "Found via class $cls")
                    return Discovery(CarBackend("class:$cls", mapOf("main" to obj)), attempts)
                }
            }
            return Discovery(null, attempts)
        }

        private fun discoverByd(ctx: Context, attempts: MutableList<DiscoveryAttempt>): CarBackend? {
            val apk = BYD_CLIENT_PACKAGES.firstNotNullOfOrNull { pkg ->
                runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()
            }
            if (apk == null) {
                attempts += DiscoveryAttempt("bydauto", "no BYD client package installed")
                return null
            }
            val loader = PathClassLoader(apk, ctx.classLoader)
            val devices = LinkedHashMap<String, Any>()
            for ((key, cls) in BYD_DEVICES) {
                val r = runCatching { instantiate(loader.loadClass(cls), ctx) }
                attempts += DiscoveryAttempt("bydauto:$key", outcome(r))
                r.getOrNull()?.let { devices[key] = it }
            }
            if (devices.isEmpty()) return null
            Log.i(TAG, "BYD devices from $apk: ${devices.keys}")
            return CarBackend("bydauto (${File(apk).name})", devices)
        }

        private fun instantiate(c: Class<*>, ctx: Context): Any? {
            // Only static factories: invoking an instance method with a null receiver just NPEs,
            // which used to hide a perfectly good (Context) constructor.
            val factories = c.methods.filter { Modifier.isStatic(it.modifiers) && it.name in FACTORY_NAMES }
            factories.firstOrNull { it.parameterTypes.isEmpty() }
                ?.let { return it.invoke(null) }
            // The usual Android manager shape: getInstance(Context).
            factories.firstOrNull { it.parameterTypes.singleOrNull()?.isAssignableFrom(Context::class.java) == true }
                ?.let { return it.invoke(null, ctx) }
            return c.getConstructor(Context::class.java).newInstance(ctx)
        }

        private fun outcome(r: Result<Any?>): String {
            r.getOrNull()?.let { return "found ${it.javaClass.name}" }
            val e = r.exceptionOrNull() ?: return "null"
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            return "${cause.javaClass.simpleName}: ${cause.message}"
        }

        /** Used by the probe to expose ANY class on the classpath for inspection. */
        fun wrapClassInstance(name: String, obj: Any): CarBackend = CarBackend("probe:$name", mapOf("main" to obj))
    }
}

/**
 * One candidate call: a method name, the arguments in the encoding that method expects, and — on
 * firmware that splits its API across devices (BYD) — which device it lives on.
 */
class Call(val method: String, vararg val args: Any?, val device: String? = null) {
    fun describe(): String = (device?.let { "$it." } ?: "") + "$method/${args.size}"
}

/** One discovery candidate and what happened when we tried it (for the probe report). */
data class DiscoveryAttempt(val candidate: String, val result: String)

class Discovery(val backend: CarBackend?, val attempts: List<DiscoveryAttempt>)

data class MethodInfo(val name: String, val params: List<String>, val returns: String) {
    fun signature(): String = "$returns $name(${params.joinToString(", ")})"
}
