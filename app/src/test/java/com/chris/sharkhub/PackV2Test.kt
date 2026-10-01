package com.chris.sharkhub

import com.chris.sharkhub.bake.PackV2
import com.chris.sharkhub.imaging.ImageIo
import com.chris.sharkhub.imaging.Png
import com.chris.sharkhub.util.Json
import com.chris.sharkhub.util.Json.asObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The Kotlin packer against pack_v2.py: runs only where the PC renderer's output is
 * (`C:\dev\byd_factory\render`, or `-Dsharkhub.bydFactory`) and a Python-packed reference set
 * (`C:\dev\SharkHub\app\src\main\assets\car_private`, or `-Dsharkhub.refPack`). The JVM has no WebP
 * encoder, so plates compare as PNG against the render's own pixels; everything else — meta numbers
 * and names, re-encoded layers, the greyed and masked driveline, the lamp overlays, the resized views —
 * is compared against the reference pixel for pixel.
 */
class PackV2Test {
    private val factory = File(System.getProperty("sharkhub.bydFactory") ?: System.getenv("SHARKHUB_BYD_FACTORY") ?: "C:\\dev\\byd_factory")
    private val renderDir = File(factory, "render")
    private val refPack = File(System.getProperty("sharkhub.refPack") ?: System.getenv("SHARKHUB_REF_PACK") ?: "C:\\dev\\SharkHub\\app\\src\\main\\assets\\car_private")
    private val v1Meta = File("src/main/assets/car/v1_meta.json").let { if (it.exists()) it else File("app/src/main/assets/car/v1_meta.json") }
    private val out = File("build/tmp/pack-test/car_bake").apply { parentFile.deleteRecursively(); parentFile.mkdirs() }

    private fun pixelDiff(label: String, mine: File, ref: File, alphaOnly: Boolean = false): Pair<Int, Double> {
        val a = Png.read(mine); val b = Png.read(ref)
        if (a.width != b.width || a.height != b.height) { println("$label: size ${a.width}x${a.height} vs ${b.width}x${b.height}"); return 255 to 255.0 }
        var maxDiff = 0; var sum = 0L; var n = 0
        val channels = if (alphaOnly) intArrayOf(3) else intArrayOf(0, 1, 2, 3)
        for (i in 0 until a.width * a.height) for (c in channels) {
            val d = abs((a.data[i * 4 + c].toInt() and 0xFF) - (b.data[i * 4 + c].toInt() and 0xFF))
            maxDiff = max(maxDiff, d); sum += d; n++
        }
        val mean = sum.toDouble() / n
        println("$label: ${a.width}x${a.height}, max difference $maxDiff, mean ${"%.4f".format(mean)}")
        return maxDiff to mean
    }

    /** Numbers within 1e-3 (or 0.15 px for the rounded view/hub figures), names equal; plate names compare with their extension dropped. */
    private fun diffMeta(mine: Any?, ref: Any?, path: String, problems: MutableList<String>) {
        when {
            mine is Map<*, *> && ref is Map<*, *> -> {
                for (k in (mine.keys + ref.keys).toSet()) {
                    if (!mine.containsKey(k)) problems.add("$path/$k only in reference")
                    else if (!ref.containsKey(k)) problems.add("$path/$k only in mine")
                    else diffMeta(mine[k], ref[k], "$path/$k", problems)
                }
            }
            mine is List<*> && ref is List<*> -> {
                if (mine.size != ref.size) problems.add("$path: ${mine.size} vs ${ref.size} items")
                else mine.indices.forEach { diffMeta(mine[it], ref[it], "$path[$it]", problems) }
            }
            mine is Number && ref is Number -> {
                val tol = if (path.contains("/views/") || path.contains("hubs")) 0.15 else 1e-3
                if (abs(mine.toDouble() - ref.toDouble()) > tol) problems.add("$path: $mine vs $ref")
            }
            mine is String && ref is String -> if (mine.substringBeforeLast('.') != ref.substringBeforeLast('.')) problems.add("$path: '$mine' vs '$ref'")
            mine == null && ref == null -> {}
            else -> if (mine != ref) problems.add("$path: $mine vs $ref")
        }
    }

    @Test
    fun packsTheRenderLikeThePythonPacker() {
        assumeTrue("render output not on this machine ($renderDir)", File(renderDir, "v2_meta.json").exists())
        assumeTrue("reference pack not on this machine ($refPack)", File(refPack, "v2_meta.json").exists())
        val t0 = System.currentTimeMillis()
        val result = PackV2(renderDir, out, ImageIo.Pure, v1MetaJson = v1Meta.takeIf { it.exists() }?.readText(), log = { println("  $it") }).run()
        println("packed in ${(System.currentTimeMillis() - t0) / 1000.0} s: ${result.files} files, ${"%.1f".format(result.bytes / 1048576.0)} MB, webp ${result.webp}, problems ${result.problems}")
        val problems = ArrayList<String>(result.problems)

        val mine = Json.parse(File(out, "v2_meta.json").readText()).asObject()!!
        val ref = Json.parse(File(refPack, "v2_meta.json").readText()).asObject()!!
        diffMeta(mine, ref, "", problems)
        println("meta: ${problems.size} differences")

        // layers that are a straight re-encode must match exactly; the greyed driveline within a couple of levels
        val exact = listOf("v2_body.png", "v2_body_solid.png", "v2_paint_base.png", "v2_paint_spec.png", "v2_wheel_FL_00.png", "v2_wheel_RR_11.png",
            "v2_night_body_solid.png", "v2_night_wheel_FR_05.png", "v2_light_head.png", "v2_light_brake.png", "v2_dusk_paint_spec.png")
        for (f in exact) {
            val m = File(out, f); val r = File(refPack, f)
            if (!m.exists() || !r.exists()) { problems.add("$f missing (mine ${m.exists()}, ref ${r.exists()})"); continue }
            if (pixelDiff(f, m, r).first > 0) problems.add("$f is not a pixel-exact re-encode")
        }
        val (driveMax, driveMean) = pixelDiff("v2_drive.png", File(out, "v2_drive.png"), File(refPack, "v2_drive.png"))
        if (driveMax > 3 || driveMean > 0.05) problems.add("v2_drive.png differs from the Python grey/mask (max $driveMax, mean $driveMean)")
        for (v in listOf("v2_view_side.png", "v2_view_front_paint_base.png", "v2_view_rear_paint_spec.png")) {
            val (mx, mean) = pixelDiff(v, File(out, v), File(refPack, v))
            if (mx > 2 || mean > 0.02) problems.add("$v differs from the Python resize (max $mx, mean $mean)")
        }
        // plates: the day plate is PNG in both; WebP plates can't be read here, so check mine against the render's RGB
        if (pixelDiff("v2_day_bg.png", File(out, "v2_day_bg.png"), File(refPack, "v2_day_bg.png")).first > 0) problems.add("v2_day_bg.png differs")
        val duskMine = File(out, "v2_dusk_bg.png").takeIf { it.exists() } ?: File(out, "v2_dusk_bg.webp")
        if (duskMine.name.endsWith(".png")) {
            val a = Png.read(duskMine); val b = Png.read(File(renderDir, "v2_dusk_bg.png"))
            var bad = 0
            for (i in 0 until a.width * a.height) for (c in 0 until 3) if (a.data[i * 4 + c] != b.data[i * 4 + c]) bad++
            println("v2_dusk_bg.png vs the render's RGB: $bad differing samples")
            if (bad > 0) problems.add("dusk plate RGB differs from the render")
        }
        problems.forEach { println("PROBLEM: $it") }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
