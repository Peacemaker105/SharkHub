package com.chris.sharkhub.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

/**
 * Launch native head-unit apps/activities for functions we don't reimplement (maps, media,
 * 360 camera, settings pages, etc.).
 *
 * Packages confirmed on Chris's Shark 6 (SOC_260811_S, probe/2026-09-28). They launch through each
 * app's launcher entry rather than a hard-coded activity, so a firmware update that renames an
 * activity doesn't break the tile. To find more:
 *     adb shell pm list packages | grep -i byd
 *     adb shell dumpsys package <pkg> | grep -A2 -i activity
 * The UI reads [NativeApp.entries] so new links appear automatically.
 */
enum class NativeApp(
    val label: String,
    val pkg: String,
    val activity: String? = null,
    val action: String? = null,
) {
    SETTINGS("Car Settings", "com.byd.carsettings"),
    SURROUND_CAM("360 Camera", "com.byd.avm"),                 // AVM = around-view monitor
    DASH_CAM("Car Photos", "com.byd.auto_photo"),              // best match for dashcam footage — unverified
    MEDIA("Media", "com.byd.localmusic"),
    PHONE("Phone", "com.byd.bluetoothcall"),
    ENERGY("My Car", "com.byd.mycar"),                         // vehicle info / energy
    CLIMATE("BYD Climate", "com.byd.hvac"),
    FILES("Files", "com.byd.filemanager"),
    // BYD's animated off-road page (climbing / tugging). "drivingmode" is the only package on the
    // unit that fits; launcher com.byd.drivingmode.MainActivity. Unverified until tapped on the car.
    RAGE_MODE("Rage Mode", "com.byd.dlc.drivingmode"),
    SCENE_MODE("Scene Modes", "com.byd.scenemode"),
    ;

    companion object {
        private const val TAG = "SharkHub/DeepLink"

        /** [launch], and say why when it fails — a button that silently does nothing looks broken. */
        fun launchOrToast(ctx: Context, app: NativeApp) {
            launch(ctx, app).onFailure {
                Toast.makeText(ctx, "${app.label}: couldn't open ${app.pkg} — check the Service Probe",
                    Toast.LENGTH_LONG).show()
            }
        }

        fun launch(ctx: Context, app: NativeApp): Result<Unit> = runCatching {
            val intent = when {
                app.action != null -> Intent(app.action)
                app.activity != null -> Intent().apply {
                    component = ComponentName(app.pkg, app.activity)
                }
                else -> ctx.packageManager.getLaunchIntentForPackage(app.pkg)
                    ?: error("no launcher for ${app.pkg}")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.onFailure { Log.w(TAG, "launch ${app.pkg} failed: ${it.message}") }

        /** Which of these are actually installed on this unit — used to grey out dead tiles. */
        fun installed(ctx: Context, app: NativeApp): Boolean = runCatching {
            ctx.packageManager.getPackageInfo(app.pkg, 0); true
        }.getOrDefault(false)
    }
}
