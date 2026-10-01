package com.chris.sharkhub.bake

import android.content.Context
import android.content.pm.PackageManager
import com.chris.sharkhub.util.Json
import com.chris.sharkhub.util.Json.asInt
import com.chris.sharkhub.util.Json.asObject
import com.chris.sharkhub.util.Json.asString
import java.io.File

/**
 * Where the bake keeps things, all under the app's own storage so BYD's files never leave the car:
 * `bake/src` is the model root the render page is served from (the decoded GLBs, textures, HDR faces
 * and the trimmed rig), `bake/render` the page's PNG layers, `car_bake` the finished set the overview
 * loads (built next to it and swapped in whole), `bake_state.json` the stamp of the last bake.
 */
class BakeFiles(ctx: Context) {
    val root = File(ctx.filesDir, "bake")
    val src = File(root, "src")
    val render = File(root, "render")
    val pack = File(ctx.filesDir, PACK_DIR)
    val cache = File(ctx.cacheDir, "bake")
    val state = File(root, "bake_state.json")

    companion object {
        const val PACK_DIR = "car_bake"
        fun packDir(ctx: Context) = File(ctx.filesDir, PACK_DIR)
        /** True when a complete baked set is in place (the loader still validates it); false wherever there is no files dir (the screenshot tests). */
        fun hasBake(ctx: Context) = runCatching { File(packDir(ctx), "v2_meta.json").exists() }.getOrDefault(false)
    }
}

/** The owner's own files on this unit: BYD's My Car APK (the Shark model) and Rage Mode's scene folder, when present. */
class CarSources(val myCarApk: File?, val rageDir: File?) {
    val hasModel: Boolean get() = myCarApk != null

    companion object {
        const val MY_CAR = "com.byd.mycar"
        const val DRIVING_MODE = "com.byd.dlc.drivingmode"

        fun find(ctx: Context): CarSources {
            val pm = ctx.packageManager
            fun sourceDir(pkg: String): File? = runCatching { File(pm.getApplicationInfo(pkg, 0).sourceDir) }.getOrNull()?.takeIf { it.canRead() }
            val apk = sourceDir(MY_CAR) ?: File("/system/app/BydMyCar/BydMyCar.apk").takeIf { it.canRead() }
            // DrivingMode keeps its Kanzi scenes next to its APK (/system/app/DrivingMode/files/kanzi); other firmware may not ship them
            val rage = listOfNotNull(
                sourceDir(DRIVING_MODE)?.parentFile?.let { File(it, "files/kanzi") },
                File("/system/app/DrivingMode/files/kanzi"),
            ).firstOrNull { File(it, "vehicle.kzb").canRead() }
            return CarSources(apk, rage)
        }

        fun isMyCarInstalled(ctx: Context): Boolean = runCatching { ctx.packageManager.getPackageInfo(MY_CAR, 0); true }.getOrDefault(false)
            || File("/system/app/BydMyCar/BydMyCar.apk").exists()

        @Suppress("unused")
        private fun PackageManager.exists(pkg: String) = runCatching { getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }
}

/** The stamp of the last successful bake, so a new app version with a changed renderer can ask for a rebuild. */
class BakeInfo(
    val rendererVersion: Int,
    val appVersionCode: Int,
    val finishedAt: Long,
    val canvas: IntArray,
    val seconds: Int,
    val files: Int,
    val bytes: Long,
    val webp: Boolean,
    val driveline: Boolean,
    val probe: String?,
) {
    fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(Json.write(Json.obj(
            "rendererVersion" to rendererVersion.toLong(), "appVersionCode" to appVersionCode.toLong(), "finishedAt" to finishedAt,
            "canvas" to canvas.toList(), "seconds" to seconds.toLong(), "files" to files.toLong(), "bytes" to bytes, "webp" to webp,
            "driveline" to driveline, "probe" to probe,
        ), indent = 1))
    }

    companion object {
        fun read(file: File): BakeInfo? = runCatching {
            val o = Json.parse(file.readText()).asObject() ?: return null
            BakeInfo(
                o["rendererVersion"].asInt() ?: 0, o["appVersionCode"].asInt() ?: 0, (o["finishedAt"] as? Number)?.toLong() ?: 0L,
                (o["canvas"] as? List<*>)?.map { (it as Number).toInt() }?.toIntArray() ?: intArrayOf(0, 0),
                o["seconds"].asInt() ?: 0, o["files"].asInt() ?: 0, (o["bytes"] as? Number)?.toLong() ?: 0L,
                o["webp"] as? Boolean ?: false, o["driveline"] as? Boolean ?: false, o["probe"].asString(),
            )
        }.getOrNull()
    }
}
