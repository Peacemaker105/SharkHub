package com.chris.sharkhub.bake

import com.chris.sharkhub.imaging.BytePlane
import com.chris.sharkhub.imaging.ImageIo
import com.chris.sharkhub.imaging.PilOps
import com.chris.sharkhub.imaging.RgbaImage
import com.chris.sharkhub.util.Json
import com.chris.sharkhub.util.Json.asDouble
import com.chris.sharkhub.util.Json.asInt
import com.chris.sharkhub.util.Json.asList
import com.chris.sharkhub.util.Json.asObject
import com.chris.sharkhub.util.Json.asString
import java.io.File

/**
 * Assembles the app's car art set from a render_v2.html run — a port of tools/model/pack_v2.py, step
 * for step, so the set the car bakes is the set the PC pack produced (the JVM test diffs them):
 *  - ghost body / shells / wheel frames re-encoded as PNG with their names kept; the shell is three
 *    layers when the render is tintable (body_solid, paint_base on the neutral grey, paint_spec);
 *  - drive greyed (autocontrast · contrast · brightness · unsharp) and masked to the shell's dilated
 *    silhouette so nothing of the chassis pokes outside the truck;
 *  - plates as WebP (PNG for the day plate, which the screenshot tests must still see; PNG for all of
 *    them where the platform has no WebP), the ×2 field-of-view twins and the blurred road plates too;
 *  - the inclinometer views framed together, resized (Lanczos) to 1400 / 900 / 700 px, ground row,
 *    pivot and hubs recomputed;
 *  - `<tag>_meta.json` with the v1 keys plus times / views / paint / files / lights, checked against
 *    the v1 meta's key set, every referenced file verified, then the folder swapped into place.
 * No previews: the app composes the look itself.
 */
class PackV2(
    private val renderDir: File,
    private val outDir: File,
    private val io: ImageIo = ImageIo.Pure,
    private val tag: String = "v2",
    /** The public set's meta, whose key set the new meta is validated against (null skips the check). */
    private val v1MetaJson: String? = null,
    private val bgQuality: Int = 92,
    private val log: (String) -> Unit = {},
    private val progress: (String, Float) -> Unit = { _, _ -> },
    /** Called between files; throws to stop a bake that was cancelled. */
    private val checkCancelled: () -> Unit = {},
) {
    class Result(val dir: File, val files: Int, val bytes: Long, val problems: List<String>, val webp: Boolean)

    private val WHEELS = listOf("FL", "FR", "RL", "RR")
    private val VIEW_WIDTHS = mapOf("side" to 1400, "front" to 900, "rear" to 700)
    private val PAINT_LAYERS = linkedMapOf("paintBase" to "paint_base", "paintSpec" to "paint_spec")

    private val sizes = LinkedHashMap<String, Long>()
    private var webpOk = true

    private fun put(name: String, size: Long) { checkCancelled(); sizes[name] = size; log("  %-40s %8.0f KB".format(name, size / 1024.0)) }
    private fun load(name: String): RgbaImage = io.readPng(File(renderDir, name))
    private fun savePng(img: RgbaImage, name: String, od: File): Long = io.writePng(File(od, name), img)

    /** A plate: WebP when asked for and available, else PNG; returns the file name actually written. */
    private fun saveBg(img: RgbaImage, base: String, fmt: String, od: File): String {
        if (fmt == "webp" && webpOk) {
            val name = "$base.webp"
            val n = io.writeWebp(File(od, name), img, bgQuality)
            if (n != null) { put(name, n); return name }
            webpOk = false
            log("  (no WebP encoder on this platform: plates stay PNG)")
        }
        val name = "$base.png"
        put(name, io.writePng(File(od, name), img, dropAlpha = true))
        return name
    }

    private fun crop(o: Map<String, Any?>): IntArray = intArrayOf(o["x"].asDouble()!!.toInt(), o["y"].asDouble()!!.toInt(), o["w"].asDouble()!!.toInt(), o["h"].asDouble()!!.toInt())
    private fun cropObj(file: String, c: IntArray) = Json.obj("file" to file, "x" to c[0].toLong(), "y" to c[1].toLong(), "w" to c[2].toLong(), "h" to c[3].toLong())

    /**
     * prep_layers.py's look, pushed harder: bright greyscale with real contrast and crisp edges so the
     * chassis reads at panel size (the app multiplies it by the theme colour), alpha kept.
     */
    private fun greyDrive(im: RgbaImage): RgbaImage {
        var lum = PilOps.toL(im)
        lum = PilOps.autocontrast(lum, 1)
        lum = PilOps.contrast(lum, 1.45f)
        lum = PilOps.brightness(lum, 1.25f)
        lum = PilOps.unsharpMask(lum, 2f, 120, 2)
        return lum.toRgba().withChannel(3, im.channel(3))
    }

    /** Multiply the drive's alpha by the (dilated) silhouette of the painted shell, aligned on the canvas. */
    private fun maskToShell(drive: RgbaImage, driveCrop: IntArray, silhouette: RgbaImage, dilate: Int = 7): RgbaImage {
        val lut = IntArray(256) { if (it > 4) 255 else 0 }
        var sil = silhouette.channel(3).point(lut)
        sil = PilOps.maxFilter(sil, dilate)
        val m = BytePlane(drive.width, drive.height)
        for (y in 0 until drive.height) for (x in 0 until drive.width) {
            val sx = x + driveCrop[0]; val sy = y + driveCrop[1]
            m.data[y * drive.width + x] = if (sx in 0 until sil.width && sy in 0 until sil.height) sil.data[sy * sil.width + sx] else 0
        }
        return drive.withChannel(3, PilOps.multiply(drive.channel(3), m))
    }

    fun run(): Result {
        val od = File(outDir.path + "__packing")
        if (od.isDirectory) od.deleteRecursively()
        od.mkdirs()
        val meta = Json.parse(File(renderDir, "${tag}_meta.json").readText()).asObject() ?: error("${tag}_meta.json is not an object")
        val canvas = meta["canvas"].asList()!!
        val W = canvas[0].asInt()!!; val H = canvas[1].asInt()!!
        val layers = meta["layers"].asObject()!!

        // ---- neutral layers
        log("layers"); progress("Packing the body layers", 0f)
        val body = load("${tag}_body.png")
        put("${tag}_body.png", savePng(body, "${tag}_body.png", od))
        val shell = load("${tag}_body_solid.png")
        put("${tag}_body_solid.png", savePng(shell, "${tag}_body_solid.png", od))
        layers["body"].asObject()!!["file"] = "${tag}_body.png"
        val solidJ = layers["bodySolid"].asObject()!!
        solidJ["file"] = "${tag}_body_solid.png"
        // the tintable paint layers (older renders have none: there body_solid is the whole painted shell)
        val silhouette = RgbaImage(W, H)
        silhouette.alphaComposite(shell, solidJ["x"].asDouble()!!.toInt(), solidJ["y"].asDouble()!!.toInt())
        for ((key, suffix) in PAINT_LAYERS) {
            val name = "${tag}_$suffix.png"
            val lj = layers[key].asObject()
            if (lj == null || !File(renderDir, name).exists()) { layers.remove(key); continue }
            val im = load(name)
            put(name, savePng(im, name, od))
            lj["file"] = name
            if (key == "paintBase") silhouette.alphaComposite(im, lj["x"].asDouble()!!.toInt(), lj["y"].asDouble()!!.toInt())
        }
        progress("Greying the driveline", 0.08f)
        val driveJ = layers["drive"].asObject()
        if (driveJ != null && File(renderDir, "${tag}_drive.png").exists()) {
            var drive = greyDrive(load("${tag}_drive.png"))
            drive = maskToShell(drive, crop(driveJ), silhouette)
            put("${tag}_drive.png", savePng(drive, "${tag}_drive.png", od))
            driveJ["file"] = "${tag}_drive.png"
        } else {
            // a unit without Rage Mode's files has no driveline: the x-ray shows the ghost shell alone
            log("  no driveline layer: writing an empty one")
            put("${tag}_drive.png", savePng(RgbaImage(1, 1), "${tag}_drive.png", od))
            layers["drive"] = Json.obj("file" to "${tag}_drive.png", "x" to 0L, "y" to 0L, "w" to 1L, "h" to 1L)
            meta["driveline"] = "none"
        }
        val wheelsJ = meta["wheels"].asObject()!!
        for ((wi, n) in WHEELS.withIndex()) {
            progress("Packing the wheels ($n)", 0.1f + 0.15f * wi / 4)
            val w = wheelsJ[n].asObject()!!
            val files = ArrayList<String>()
            for ((p, fr) in w["frames"].asList()!!.withIndex()) {
                val name = "${tag}_wheel_${n}_${"%02d".format(p)}.png"
                put(name, savePng(load(name), name, od))
                fr.asObject()!!["file"] = name; files.add(name)
            }
            w["files"] = files
        }

        // ---- per time of day
        log("times")
        val times = meta["times"].asObject() ?: Json.obj().also { meta["times"] = it }
        val base = meta["defaultTime"].asString() ?: "day"
        for ((ti, entry) in times.entries.withIndex()) {
            val tn = entry.key; val t = entry.value.asObject()!!
            progress("Packing the $tn plates", 0.25f + 0.35f * ti / maxOf(1, times.size))
            val fmt = if (tn == base) "png" else "webp"
            val bgName = saveBg(load("${tag}_${tn}_bg.png"), "${tag}_${tn}_bg", fmt, od)
            t["bg"] = cropObj(bgName, intArrayOf(0, 0, W, H))
            if (t["bgWide"] != null) {   // the ×2 field-of-view twin, for zooming out (same format as the plate it doubles)
                val wname = saveBg(load("${tag}_${tn}_bg_wide.png"), "${tag}_${tn}_bg_wide", fmt, od)
                t["bgWide"] = cropObj(wname, intArrayOf(0, 0, W, H)).also { it["scale"] = 2L; it["centre"] = listOf(W / 2.0, H / 2.0) }
            }
            val shells = listOf("bodySolid" to "body_solid") + PAINT_LAYERS.filter { layers.containsKey(it.key) }.map { it.key to it.value }
            for ((key, suffix) in shells) {
                if (tn == base) t[key] = LinkedHashMap(layers[key].asObject()!!)
                else {
                    val name = "${tag}_${tn}_$suffix.png"
                    put(name, savePng(load(name), name, od))
                    val c = crop((t[key + "Crop"].asObject() ?: layers[key].asObject()!!))
                    t[key] = cropObj(name, c)
                    t.remove(key + "Crop")
                }
            }
            for (key in PAINT_LAYERS.keys) if (!layers.containsKey(key)) { t.remove(key); t.remove(key + "Crop") }
            t["wheels"].asObject()?.let { tw ->
                for (n in WHEELS) {
                    val wt = tw[n].asObject()!!
                    val files = ArrayList<String>()
                    for ((p, fr) in wt["frames"].asList()!!.withIndex()) {
                        val name = "${tag}_${tn}_wheel_${n}_${"%02d".format(p)}.png"
                        put(name, savePng(load(name), name, od))
                        fr.asObject()!!["file"] = name; files.add(name)
                    }
                    wt["files"] = files
                }
            }
        }
        layers["bg"] = times[base].asObject()?.get("bg").asObject()?.let { LinkedHashMap(it) } ?: layers["bg"]
        val files = Json.obj(
            "body" to "${tag}_body.png", "bodySolid" to "${tag}_body_solid.png", "drive" to "${tag}_drive.png",
            "bg" to layers["bg"].asObject()?.get("file"),
            "wheels" to Json.obj(*WHEELS.map { n -> n to wheelsJ[n].asObject()!!["files"] }.toTypedArray()),
            "times" to Json.obj(*times.entries.map { (tn, tv) ->
                val t = tv.asObject()!!
                tn to Json.obj("bg" to t["bg"].asObject()!!["file"], "bodySolid" to t["bodySolid"].asObject()!!["file"],
                    "wheels" to t["wheels"].asObject()?.let { tw -> Json.obj(*WHEELS.map { n -> n to tw[n].asObject()!!["files"] }.toTypedArray()) })
            }.toTypedArray()),
        )
        meta["files"] = files
        val filesTimes = files["times"].asObject()!!
        for (key in PAINT_LAYERS.keys) if (layers.containsKey(key)) {
            files[key] = layers[key].asObject()!!["file"]
            for ((tn, tv) in times) filesTimes[tn].asObject()!![key] = tv.asObject()!![key].asObject()!!["file"]
        }
        for ((tn, tv) in times) tv.asObject()!!["bgWide"].asObject()?.let { filesTimes[tn].asObject()!!["bgWide"] = it["file"] }
        meta["gradeFormula"] = "per channel, 0-1: out = ((px * tint[c] * brightness) - 0.5) * contrast + 0.5; apply to layers a time does not supply (the base wheels); never to the theme-tinted ghost body or driveline"

        // ---- extras from renderExtras(): lit-lamp overlays + motion-blurred road plates (optional)
        val extrasFile = File(renderDir, "${tag}_extras.json")
        if (extrasFile.exists()) {
            log("extras"); progress("Packing the lamp overlays", 0.62f)
            val ex = Json.parse(extrasFile.readText()).asObject()!!
            ex["lights"].asObject()?.let { lights ->
                val ml = Json.obj()
                for ((name, ev) in lights) {
                    val e = ev.asObject()!!
                    val f = e["file"].asString()!!
                    put(f, savePng(load(f), f, od))
                    ml[name] = e
                }
                meta["lights"] = ml
                files["lights"] = Json.obj(*ml.entries.map { (n, e) -> n to e.asObject()!!["file"] }.toTypedArray())
            }
            ex["times"].asObject()?.let { exTimes ->
                for ((tn, tev) in exTimes) {
                    val t = times[tn].asObject() ?: continue
                    val te = tev.asObject()!!
                    for ((key, suffix) in listOf("bgBlur" to "bg_blur", "bgBlurWide" to "bg_blur_wide")) {
                        val src = te[key].asObject() ?: continue
                        progress("Packing the blurred $tn plates", 0.65f)
                        val name = saveBg(load(src["file"].asString()!!), "${tag}_${tn}_$suffix", "webp", od)   // always the compact format: only shown at speed
                        t[key] = cropObj(name, intArrayOf(0, 0, W, H)).also { if (key == "bgBlurWide") { it["scale"] = 2L; it["centre"] = listOf(W / 2.0, H / 2.0) } }
                        filesTimes[tn].asObject()!![key] = name
                    }
                }
            }
            ex["blur"]?.let { meta["blur"] = it }
        }

        // ---- inclinometer views
        meta["views"].asObject()?.let { views ->
            log("views"); progress("Resizing the tilt views", 0.75f)
            for ((vn, vv) in views) {
                val v = vv.asObject()!!
                // the view and its paint layers share one frame — the union of their crops — so the app draws all three at the same size and place
                val parts = ArrayList<Triple<String, String, IntArray>>()
                parts.add(Triple("file", "", crop(v["canvasCrop"].asObject()!!)))
                for ((k, s) in PAINT_LAYERS) v[k + "Crop"].asObject()?.let { parts.add(Triple(k, "_$s", crop(it))) }
                val cx = parts.minOf { it.third[0] }; val cy = parts.minOf { it.third[1] }
                val cw = parts.maxOf { it.third[0] + it.third[2] } - cx; val chh = parts.maxOf { it.third[1] + it.third[3] } - cy
                val target = VIEW_WIDTHS[vn] ?: 1000
                val s = target.toDouble() / cw
                val sizeW = target; val sizeH = PilOps.pyRound(chh * s, 0).toInt()
                for ((key, suffix, c) in parts) {
                    val framed = RgbaImage(cw, chh)
                    framed.alphaComposite(load("${tag}_view_$vn$suffix.png"), c[0] - cx, c[1] - cy)
                    val name = "${tag}_view_$vn$suffix.png"
                    put(name, savePng(PilOps.resizeLanczos(framed, sizeW, sizeH), name, od))
                    v[key] = name
                    v.remove(key + "Crop")
                }
                val ground = (v["ground"].asDouble()!! - cy) * s
                val pv = v["pivot"].asList()!!
                val pivot = doubleArrayOf((pv[0].asDouble()!! - cx) * s, (pv[1].asDouble()!! - cy) * s)
                val hubs = Json.obj()
                v["hubs"].asObject()?.forEach { (k, p) -> val pl = p.asList()!!; hubs[k] = listOf(PilOps.pyRound((pl[0].asDouble()!! - cx) * s, 1), PilOps.pyRound((pl[1].asDouble()!! - cy) * s, 1)) }
                v["w"] = sizeW.toLong(); v["h"] = sizeH.toLong()
                v["ground"] = PilOps.pyRound(ground, 1); v["groundFrac"] = PilOps.pyRound(ground / sizeH, 4)
                v["pivot"] = listOf(PilOps.pyRound(pivot[0], 1), PilOps.pyRound(pivot[1], 1))
                v["pivotFrac"] = listOf(PilOps.pyRound(pivot[0] / sizeW, 4), PilOps.pyRound(pivot[1] / sizeH, 4))
                v["hubs"] = hubs
                v["pxPerM"] = PilOps.pyRound((v["pxPerM"].asDouble() ?: 0.0) * s, 2)
                v.remove("canvasCrop")
            }
            files["views"] = Json.obj(*views.entries.map { (vn, v) -> vn to v.asObject()!!["file"] }.toTypedArray())
        }

        // ---- meta + validation
        progress("Checking the set", 0.9f)
        val metaFile = File(od, "${tag}_meta.json")
        metaFile.writeText(Json.write(meta, indent = 1))
        sizes["${tag}_meta.json"] = metaFile.length()
        val problems = ArrayList<String>()
        v1MetaJson?.let { v1Text ->
            val v1 = Json.parse(v1Text).asObject()!!
            val missing = v1.keys.filter { !meta.containsKey(it) }
            if (missing.isNotEmpty()) problems.add("missing v1 keys: $missing")
            for (k in listOf("body", "bodySolid", "drive", "bg")) if (v1["layers"].asObject()!!.containsKey(k)) {
                val lj = layers[k].asObject()
                for (ck in listOf("x", "y", "w", "h")) if (lj == null || !lj.containsKey(ck)) problems.add("layers.$k.$ck missing")
            }
            for (n in v1["wheels"].asObject()!!.keys) {
                val w = wheelsJ[n].asObject()
                if (w == null || !w.containsKey("hub") || w["frames"].asList().isNullOrEmpty()) problems.add("wheels.$n incomplete")
            }
            val anchors = meta["anchors"].asObject() ?: Json.obj()
            for (a in v1["anchors"].asObject()!!.keys) if (!anchors.containsKey(a)) problems.add("anchors.$a missing")
            val road = meta["road"].asObject() ?: Json.obj()
            for (rk in v1["road"].asObject()!!.keys) if (!road.containsKey(rk)) problems.add("road.$rk missing")
            if ((meta["groundH"].asList()?.size ?: 0) != 9) problems.add("groundH must have 9 numbers")
            log("v1 key check: " + if (problems.isEmpty()) "OK" else problems.toString())
        }
        // every referenced file must exist and decode
        val refs = ArrayList<String>()
        refs += listOf(files["body"], files["bodySolid"], files["drive"]).map { it.toString() }
        WHEELS.forEach { n -> files["wheels"].asObject()!![n].asList()!!.forEach { refs.add(it.toString()) } }
        for (k in PAINT_LAYERS.keys) files[k]?.let { refs.add(it.toString()) }
        for ((_, tv) in filesTimes) {
            val t = tv.asObject()!!
            refs.add(t["bg"].toString()); refs.add(t["bodySolid"].toString())
            for (k in listOf("paintBase", "paintSpec", "bgWide", "bgBlur", "bgBlurWide")) t[k]?.let { refs.add(it.toString()) }
            t["wheels"].asObject()?.let { tw -> WHEELS.forEach { n -> tw[n].asList()!!.forEach { refs.add(it.toString()) } } }
        }
        files["views"].asObject()?.values?.forEach { refs.add(it.toString()) }
        meta["views"].asObject()?.values?.forEach { v -> for (k in PAINT_LAYERS.keys) v.asObject()!![k]?.let { refs.add(it.toString()) } }
        for (f in refs.distinct()) if (!io.verify(File(od, f))) problems.add("$f: missing or won't decode")
        val all = od.listFiles()?.filter { it.isFile } ?: emptyList()
        val total = all.sumOf { it.length() }
        log("total in ${od.name}: %.1f MB (%d files)".format(total / 1048576.0, all.size))
        if (problems.isNotEmpty()) {
            log("PROBLEMS: " + problems.joinToString("; "))
            log("NOT swapped in — the build is in $od")
            return Result(od, all.size, total, problems, webpOk)
        }
        // ---- swap the finished folder into place (one quick rename, nothing half-written)
        val old = File(outDir.path + "__old")
        if (old.isDirectory) old.deleteRecursively()
        if (outDir.isDirectory && !outDir.renameTo(old)) error("could not move the old set aside")
        if (!od.renameTo(outDir)) { old.renameTo(outDir); error("could not swap the new set in") }
        if (old.isDirectory) old.deleteRecursively()
        log("swapped into $outDir")
        progress("Done", 1f)
        return Result(outDir, all.size, total, emptyList(), webpOk)
    }
}
