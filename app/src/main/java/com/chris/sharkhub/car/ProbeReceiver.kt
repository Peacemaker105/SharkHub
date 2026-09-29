package com.chris.sharkhub.car

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Builds the probe report and writes it to [ProbeExport.adbPullPath] without touching the car's
 * screen:
 *
 *     adb shell am broadcast -n com.chris.sharkhub/.car.ProbeReceiver
 *     adb pull /sdcard/Android/data/com.chris.sharkhub/files/export/sharkhub-probe.json
 *
 * The manifest guards it with the DUMP permission, which the adb shell holds and ordinary apps
 * can't get. The report makes a few hundred read-only calls, so it runs on its own thread.
 */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "sharkhub-probe") {
            val car = CarManager(ctx.applicationContext)
            try {
                ProbeExport.writeFile(ctx, ProbeExport.build(ctx, car))
                pending.setResult(Activity.RESULT_OK, ProbeExport.adbPullPath(ctx), null)
            } catch (t: Throwable) {
                pending.setResult(Activity.RESULT_CANCELED, "probe failed: $t", null)
            } finally {
                car.close()
                pending.finish()
            }
        }
    }
}
