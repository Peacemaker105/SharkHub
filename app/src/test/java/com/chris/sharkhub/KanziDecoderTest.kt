package com.chris.sharkhub

import com.chris.sharkhub.car.kanzi.Bytes
import com.chris.sharkhub.car.kanzi.RigNeeds
import com.chris.sharkhub.car.kanzi.SharkDecoder
import com.chris.sharkhub.imaging.Png
import com.chris.sharkhub.util.Json
import com.chris.sharkhub.util.Json.asDouble
import com.chris.sharkhub.util.Json.asDoubleList
import com.chris.sharkhub.util.Json.asInt
import com.chris.sharkhub.util.Json.asList
import com.chris.sharkhub.util.Json.asObject
import com.chris.sharkhub.util.Json.asString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The Kotlin Kanzi decoder against the Python pipeline's output. Runs only on a machine that holds
 * BYD's files (never a committed fixture): `C:\dev\byd_factory\BydMyCar.apk`, or the folder named by
 * `-Dsharkhub.bydFactory=…` / `SHARKHUB_BYD_FACTORY`. Compares the body GLB with
 * `byd_car_pa_rtl.glb` (part names, triangle counts, materials, bounding boxes within 1 mm, lamp
 * states), the driveline with `byd_car_rage_drive_yup.glb`, the decoded textures pixel for pixel and
 * the showroom HDR faces byte for byte.
 */
class KanziDecoderTest {
    private val factory: File = File(System.getProperty("sharkhub.bydFactory") ?: System.getenv("SHARKHUB_BYD_FACTORY") ?: "C:\\dev\\byd_factory")
    private val apk = File(factory, "BydMyCar.apk")
    private val rageDir = File(factory, "ragemode/files/kanzi")
    private val rig = File("src/main/assets/bake/rigs/byd_shark6.rig.json").let { if (it.exists()) it else File("app/src/main/assets/bake/rigs/byd_shark6.rig.json") }

    private val out = File("build/tmp/kanzi-test").apply { deleteRecursively(); mkdirs() }

    /** A node of a GLB as the comparison sees it. */
    private class GlbNode(val name: String, val materials: List<String>, val indexCounts: List<Int>, val min: DoubleArray, val max: DoubleArray, val translation: DoubleArray, val state: String?, val node: String?)

    private fun readGlb(file: File): Map<String, GlbNode> {
        val b = file.readBytes()
        require(String(b, 0, 4, Charsets.US_ASCII) == "glTF") { "${file.name} is not a GLB" }
        val jsonLen = Bytes.u32(b, 12).toInt()
        val js = Json.parse(String(b, 20, jsonLen, Charsets.UTF_8)).asObject()!!
        val accessors = js["accessors"].asList()!!
        val meshes = js["meshes"].asList()!!
        val materials = js["materials"].asList()!!
        val out = LinkedHashMap<String, GlbNode>()
        for (n in js["nodes"].asList()!!) {
            val node = n.asObject()!!
            val mesh = meshes[node["mesh"].asInt()!!].asObject()!!
            val prims = mesh["primitives"].asList()!!.map { it.asObject()!! }
            val pos = accessors[prims[0]["attributes"].asObject()!!["POSITION"].asInt()!!].asObject()!!
            val extras = node["extras"].asObject()
            out[node["name"].asString()!!] = GlbNode(
                node["name"].asString()!!,
                prims.map { materials[it["material"].asInt()!!].asObject()!!["name"].asString()!! },
                prims.map { accessors[it["indices"].asInt()!!].asObject()!!["count"].asInt()!! },
                pos["min"].asDoubleList()!!, pos["max"].asDoubleList()!!,
                node["translation"].asDoubleList() ?: doubleArrayOf(0.0, 0.0, 0.0),
                extras?.get("state").asString(), extras?.get("node").asString(),
            )
        }
        return out
    }

    /** Compares two GLBs node by node; returns a list of differences (empty = same) and prints the numbers. */
    private fun diffGlb(label: String, mine: Map<String, GlbNode>, ref: Map<String, GlbNode>, tolM: Double = 0.001): List<String> {
        val problems = ArrayList<String>()
        if (mine.keys != ref.keys) problems.add("$label: part names differ — only mine ${mine.keys - ref.keys}, only reference ${ref.keys - mine.keys}")
        var maxBox = 0.0; var parts = 0; var tris = 0
        for ((name, r) in ref) {
            val m = mine[name] ?: continue
            parts++
            tris += m.indexCounts.sum() / 3
            if (m.indexCounts != r.indexCounts) problems.add("$label $name: index counts ${m.indexCounts} vs ${r.indexCounts}")
            if (m.materials != r.materials) problems.add("$label $name: materials ${m.materials} vs ${r.materials}")
            if ((m.state ?: "") != (r.state ?: "")) problems.add("$label $name: state '${m.state}' vs '${r.state}'")
            // The reference GLB placed the four glass-line outlines (`*_windowK`) from their RenderTransformation
            // alone; Kanzi (and this decoder) also applies their LayoutTransformation, a deliberate 1 cm outboard
            // offset that keeps the outline off the glass. Those four may differ by up to 11 mm on y.
            val tol = if (name.endsWith("_windowK")) 0.011 else tolM
            for (k in 0 until 3) {
                val dMin = abs((m.min[k] + m.translation[k]) - (r.min[k] + r.translation[k]))
                val dMax = abs((m.max[k] + m.translation[k]) - (r.max[k] + r.translation[k]))
                maxBox = max(maxBox, max(dMin, dMax))
                if (dMin > tol || dMax > tol) problems.add("$label $name: bbox axis $k differs by ${"%.4f".format(max(dMin, dMax))} m")
            }
        }
        println("$label: $parts parts compared, $tris triangles, largest bbox difference ${"%.5f".format(maxBox)} m, ${problems.size} problems")
        return problems
    }

    private fun pixelDiff(label: String, mine: File, ref: File, channels: IntArray = intArrayOf(0, 1, 2, 3)): Int {
        val a = Png.read(mine); val b = Png.read(ref)
        assertEquals("$label size", b.width to b.height, a.width to a.height)
        var maxDiff = 0; var differing = 0
        for (i in 0 until a.width * a.height) for (c in channels) {
            val d = abs((a.data[i * 4 + c].toInt() and 0xFF) - (b.data[i * 4 + c].toInt() and 0xFF))
            if (d > 0) differing++
            if (d > maxDiff) maxDiff = d
        }
        println("$label: ${a.width}x${a.height}, $differing differing samples, max difference $maxDiff")
        return maxDiff
    }

    @Test
    fun decodesTheOwnersFilesLikeThePythonPipeline() {
        assumeTrue("BYD's files are not on this machine ($apk)", apk.exists())
        assumeTrue("rig JSON missing: $rig", rig.exists())
        val needs = RigNeeds.fromRig(rig.readText())
        println("rig needs: textures ${needs.textures}, panos used ${needs.panosUsed} of ${needs.panosListed}, cubes ${needs.envCubes.keys}, chassis ${needs.chassisModel}")
        val t0 = System.currentTimeMillis()
        val result = SharkDecoder(apk, rageDir.takeIf { it.exists() }, out, File(out, "cache"), needs, log = { println("  $it") }).run()
        println("decoded in ${(System.currentTimeMillis() - t0) / 1000.0} s: ${result.paParts} parts / ${result.paTriangles} triangles; missing ${result.missing}; notes ${result.notes}")

        // --- the body
        val problems = ArrayList<String>()
        val refPa = File(factory, "byd_car_pa_rtl.glb")
        if (refPa.exists()) problems += diffGlb("PA_RTL", readGlb(result.paGlb), readGlb(refPa)) else println("no reference ${refPa.name}")
        // --- the driveline
        val refRage = File(factory, "byd_car_rage_drive_yup.glb")
        if (result.rageGlb != null && refRage.exists()) {
            problems += diffGlb("Rage drive", readGlb(result.rageGlb!!), readGlb(refRage))
            val refParams = Json.parse(File(factory, "wheel_params_rage_drive.json").readText()).asObject()!!
            val myParams = Json.parse(result.wheelParams!!.readText()).asObject()!!
            for (k in listOf("frontX", "rearX", "axleY", "ground", "radius", "track")) {
                val d = abs(myParams[k].asDouble()!! - refParams[k].asDouble()!!)
                println("wheel param $k: mine ${myParams[k]} ref ${refParams[k]}")
                if (d > 0.0015) problems.add("wheel param $k differs by $d")
            }
        } else println("Rage reference or output missing (${result.rageGlb}, ${refRage.exists()})")
        // --- textures: the decoded atlases must match the Python decoder pixel for pixel
        val tex = File(out, needs.textureBase)
        for ((name, ch) in listOf("PA_tire1.png" to intArrayOf(0, 1, 2, 3), "PA_ao_gray.png" to intArrayOf(0), "PA_suliao_ao_gray.png" to intArrayOf(0), "PA_dou_ao_gray.png" to intArrayOf(0))) {
            val mine = File(tex, name); val ref = File(factory, "pa_rtl_textures_png/$name")
            if (mine.exists() && ref.exists()) { if (pixelDiff(name, mine, ref, ch) > 0) problems.add("$name differs from the Python decode") }
            else println("skipping $name (mine ${mine.exists()}, ref ${ref.exists()})")
        }
        for (name in listOf("EnergyFlowPipeline_T.png", "Suspension_T.png", "Cell_Tank_T.png")) {
            val mine = File(out, "rage_textures_png/$name"); val ref = File(factory, "rage_textures_png/$name")
            if (mine.exists() && ref.exists()) { if (pixelDiff(name, mine, ref) > 0) problems.add("$name differs from the Python decode") }
            else println("skipping $name (mine ${mine.exists()}, ref ${ref.exists()})")
        }
        // --- HDR faces byte for byte
        for (cube in needs.envCubes.values) for (rel in cube.files()) {
            val mine = File(out, rel); val ref = File(factory, rel)
            if (mine.exists() && ref.exists()) {
                val same = mine.readBytes().contentEquals(ref.readBytes())
                println("${File(rel).name}: ${mine.length()} B, byte-identical $same")
                if (!same) problems.add("$rel differs from the Python conversion")
            } else println("skipping $rel (mine ${mine.exists()}, ref ${ref.exists()})")
        }
        problems.forEach { println("PROBLEM: $it") }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
