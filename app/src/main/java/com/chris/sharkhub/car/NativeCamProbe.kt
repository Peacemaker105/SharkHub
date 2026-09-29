package com.chris.sharkhub.car

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The camera go/no-go for sentry mode on this head unit. OverDrive reaches the surround cameras
 * through Qualcomm's QCarCam client (`/vendor/lib64/libais_client.so`) from an ordinary app; this
 * checks the same two doors on the Shark 6, read-only:
 *  - [inProcess]: dlopen from our own process (the app's restricted linker namespace);
 *  - [sidecar]: dlopen from a standalone executable shipped in nativeLibraryDir and launched with
 *    ProcessBuilder (default namespace — the way OverDrive does its capture).
 * Both report which `qcarcam_*` symbols resolve. Either can pass `init = true` to also call
 * `qcarcam_initialize(null)` + `uninitialize` — the next gate (the AIS server socket); leave it off
 * until the dlopen result is known. Everything degrades to a message: the JVM (Paparazzi) and units
 * without the lib simply get "unavailable".
 */
object NativeCamProbe {
    private val loaded: Boolean = runCatching { System.loadLibrary("sharkcam") }.isSuccess

    private external fun nativeProbe(init: Boolean): String

    fun inProcess(init: Boolean = false): String =
        if (!loaded) "unavailable (libsharkcam not loaded)"
        else runCatching { nativeProbe(init) }.getOrElse { "error: $it" }

    fun sidecar(ctx: Context, init: Boolean = false): String {
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libsharkcam_sidecar.so")
        if (!exe.isFile) return "unavailable (no sidecar binary at ${exe.path})"
        return runCatching {
            val p = ProcessBuilder(listOfNotNull(exe.path, if (init) "--init" else null))
                .redirectErrorStream(true).start()
            val finished = p.waitFor(10, TimeUnit.SECONDS)
            val text = p.inputStream.bufferedReader().readText().trim()
            if (!finished) { p.destroyForcibly(); "timeout; output so far: $text" }
            else "exit ${p.exitValue()}: $text"
        }.getOrElse { "error: $it" }
    }
}
