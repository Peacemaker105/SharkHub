package com.chris.sharkhub.ui.overview.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import com.chris.sharkhub.ui.overview.TimeOfDay
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.utils.HDRLoader
import com.google.android.filament.utils.IBLPrefilterContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Material instances for our own geometry, from gltfio's ubershader so the scene needs no compiled
 * materials of its own. The provider sets the texture-index parameters from the key; every factor
 * is set here because the provider leaves them at the glTF loader's job.
 */
internal class LiveMaterials(private val engine: Engine, private val provider: MaterialProvider) {
    // glTF texcoord set n → Filament UV set: 0 → UV0, 1 → UV1 (1-based in the map, 0 = unused)
    private val uvmap = intArrayOf(1, 2, 0, 0, 0, 0, 0, 0)

    fun create(label: String, unlit: Boolean = false, textured: Boolean = false, blend: Boolean = false, doubleSided: Boolean = false,
               uvTransform: Boolean = false, clearCoat: Boolean = false, vertexColors: Boolean = false): MaterialInstance {
        val key = MaterialProvider.MaterialKey().apply {
            this.unlit = unlit; this.doubleSided = doubleSided; hasVertexColors = vertexColors
            hasBaseColorTexture = textured; baseColorUV = 0; alphaMode = if (blend) 2 else 0
            hasTextureTransforms = uvTransform; hasClearCoat = clearCoat
        }
        val mi = provider.createMaterialInstance(key, uvmap, label, null) ?: error("ubershader has no material for $label")
        setDefaults(mi)
        if (doubleSided) mi.setCullingMode(Material.CullingMode.NONE)
        return mi
    }

    /** Every factor the ubershader reads, at glTF defaults (the provider only sets the index / UV params). */
    fun setDefaults(mi: MaterialInstance) {
        val m = mi.material
        fun f(name: String, v: Float) { if (m.hasParameter(name)) mi.setParameter(name, v) }
        if (m.hasParameter("baseColorFactor")) mi.setParameter("baseColorFactor", 1f, 1f, 1f, 1f)
        f("metallicFactor", 0f); f("roughnessFactor", 1f); f("reflectance", 0.5f)
        if (m.hasParameter("emissiveFactor")) mi.setParameter("emissiveFactor", 0f, 0f, 0f)
        f("emissiveStrength", 1f); f("clearCoatFactor", 0f); f("clearCoatRoughnessFactor", 0f)
        f("normalScale", 1f); f("aoStrength", 1f); f("clearCoatNormalScale", 1f)
        if (m.hasParameter("baseColorUvMatrix")) mi.setParameter("baseColorUvMatrix", MaterialInstance.FloatElement.MAT3, IDENTITY3, 0, 1)
    }

    companion object {
        val IDENTITY3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        /** `baseColorUvMatrix` that slides the texture by (du, dv): the shader does (u, v, 1) · M. */
        fun uvShift(du: Float, dv: Float) = floatArrayOf(1f, 0f, du, 0f, 1f, dv, 0f, 0f, 1f)
    }
}

/** A mesh as the CPU builds it; everything the ubershader requires (tangents are made from the normals). */
internal class MeshData(val positions: FloatArray, val normals: FloatArray, val uvs: FloatArray, val colors: FloatArray?, val indices: IntArray) {
    val vertexCount get() = positions.size / 3
}

internal class GpuMesh(val vb: VertexBuffer, val ib: IndexBuffer, val box: Box)

internal object LiveGeometry {
    private fun direct(floats: Int): ByteBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    private fun floats(data: FloatArray): java.nio.FloatBuffer {
        val fb = direct(data.size).asFloatBuffer()
        fb.put(data); fb.flip()
        return fb
    }

    fun upload(engine: Engine, d: MeshData): GpuMesh {
        val n = d.vertexCount
        val pos = floats(d.positions)
        val tangents = FloatArray(n * 4)
        val q = FloatArray(4)
        for (i in 0 until n) {
            val nx = d.normals[i * 3]; val ny = d.normals[i * 3 + 1]; val nz = d.normals[i * 3 + 2]
            // any tangent perpendicular to the normal will do for untextured-normal surfaces
            val hx = if (kotlin.math.abs(ny) < 0.99f) 0f else 1f; val hy = if (kotlin.math.abs(ny) < 0.99f) 1f else 0f
            var tx = hy * nz - 0f * ny; var ty = 0f * nx - hx * nz; var tz = hx * ny - hy * nx
            val l = sqrt(tx * tx + ty * ty + tz * tz); tx /= l; ty /= l; tz /= l
            val bx = ny * tz - nz * ty; val by = nz * tx - nx * tz; val bz = nx * ty - ny * tx
            MathUtils.packTangentFrame(tx, ty, tz, bx, by, bz, nx, ny, nz, q)
            System.arraycopy(q, 0, tangents, i * 4, 4)
        }
        val tan = floats(tangents)
        val uv = floats(d.uvs)
        val col = floats(d.colors ?: FloatArray(n * 4) { 1f })
        val vb = VertexBuffer.Builder().bufferCount(4).vertexCount(n)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.UV1, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 3, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        vb.setBufferAt(engine, 0, pos); vb.setBufferAt(engine, 1, tan); vb.setBufferAt(engine, 2, uv); vb.setBufferAt(engine, 3, col)
        val ib = IndexBuffer.Builder().indexCount(d.indices.size).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        val idx = ByteBuffer.allocateDirect(d.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        idx.put(d.indices); idx.flip()
        ib.setBuffer(engine, idx)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = d.positions[i * 3]; val y = d.positions[i * 3 + 1]; val z = d.positions[i * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
        val box = Box((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, max(0.01f, (maxX - minX) / 2), max(0.01f, (maxY - minY) / 2), max(0.01f, (maxZ - minZ) / 2))
        return GpuMesh(vb, ib, box)
    }

    /** A flat quad at height [y] facing up, u along X over [uLen] metres (repeating), v across Z from [z0] (v = 0) to [z1] (v = 1) × [vRepeat]. */
    fun quadXZ(x0: Float, x1: Float, z0: Float, z1: Float, y: Float, uLen: Float, vRepeat: Float = 1f): MeshData {
        val pos = floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1)
        val nrm = FloatArray(12) { if (it % 3 == 1) 1f else 0f }
        val uv = floatArrayOf(x0 / uLen, 0f, x1 / uLen, 0f, x1 / uLen, vRepeat, x0 / uLen, vRepeat)
        return MeshData(pos, nrm, uv, null, intArrayOf(0, 2, 1, 0, 3, 2))
    }

    /** The inside of a cylinder about the origin (radius [r], height [h], centred on y = 0); u runs round it, v = 0 at the top. */
    fun cylinderInside(r: Float, h: Float, segments: Int): MeshData {
        val n = (segments + 1) * 2
        val pos = FloatArray(n * 3); val nrm = FloatArray(n * 3); val uv = FloatArray(n * 2)
        for (i in 0..segments) {
            val a = i.toFloat() / segments * 2f * PI.toFloat()
            val x = cos(a); val z = sin(a)
            for (k in 0..1) {
                val v = i * 2 + k
                pos[v * 3] = r * x; pos[v * 3 + 1] = if (k == 0) h / 2 else -h / 2; pos[v * 3 + 2] = r * z
                nrm[v * 3] = -x; nrm[v * 3 + 1] = 0f; nrm[v * 3 + 2] = -z
                uv[v * 2] = i.toFloat() / segments; uv[v * 2 + 1] = k.toFloat()
            }
        }
        val idx = IntArray(segments * 6)
        for (i in 0 until segments) {
            val a = i * 2; val b = a + 1; val c = a + 2; val d = a + 3
            idx[i * 6] = a; idx[i * 6 + 1] = b; idx[i * 6 + 2] = c; idx[i * 6 + 3] = b; idx[i * 6 + 4] = d; idx[i * 6 + 5] = c
        }
        return MeshData(pos, nrm, uv, null, idx)
    }

    /** A box standing on y = 0, [w] × [h] × [d], with the top [topFrac] of it in [topColor] (linear rgb) and the rest [color]. */
    fun post(w: Float, h: Float, d: Float, color: FloatArray, topColor: FloatArray, topFrac: Float): MeshData {
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f))
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val col = ArrayList<Float>(); val idx = ArrayList<Int>()
        val ySplit = h * (1f - topFrac)
        for (f in faces) {
            // each face as two bands (body / top) so the colour steps cleanly
            val bands = if (f[1] != 0f) listOf(0f to h) else listOf(0f to ySplit, ySplit to h)
            for ((y0, y1) in bands) {
                val base = pos.size / 3
                val corners = when {
                    f[0] != 0f -> listOf(floatArrayOf(f[0] * w / 2, y0, -d / 2), floatArrayOf(f[0] * w / 2, y0, d / 2), floatArrayOf(f[0] * w / 2, y1, d / 2), floatArrayOf(f[0] * w / 2, y1, -d / 2))
                    f[1] != 0f -> { val y = if (f[1] > 0) h else 0f; listOf(floatArrayOf(-w / 2, y, -d / 2), floatArrayOf(w / 2, y, -d / 2), floatArrayOf(w / 2, y, d / 2), floatArrayOf(-w / 2, y, d / 2)) }
                    else -> listOf(floatArrayOf(-w / 2, y0, f[2] * d / 2), floatArrayOf(w / 2, y0, f[2] * d / 2), floatArrayOf(w / 2, y1, f[2] * d / 2), floatArrayOf(-w / 2, y1, f[2] * d / 2))
                }
                val c = if (y0 >= ySplit - 1e-4f || (f[1] > 0f)) topColor else color
                for (p in corners) { pos.addAll(p.toList()); nrm.addAll(f.toList()); uv.add(0f); uv.add(0f); col.addAll(listOf(c[0], c[1], c[2], 1f)) }
                idx.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
            }
        }
        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), uv.toFloatArray(), col.toFloatArray(), idx.toIntArray())
    }
}

/** Textures made in code (the road, earth and gravel tiles, the contact shadow) and the bitmap → Filament upload. */
internal object LiveTextures {
    fun levelsFor(w: Int, h: Int): Int = 1 + floor(ln(max(w, h).toDouble()) / ln(2.0)).toInt()

    /** An sRGB (colour) or linear (data) texture from a bitmap, with a full mip chain unless [mipmaps] is false. */
    fun upload(engine: Engine, bmp: Bitmap, srgb: Boolean, mipmaps: Boolean = true): Texture {
        // GEN_MIPMAPPABLE is required for generateMipmaps(): without it Filament refused on the head unit and the whole load fell back
        val usage = if (mipmaps) Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE else Texture.Usage.DEFAULT
        val tex = Texture.Builder().width(bmp.width).height(bmp.height).levels(if (mipmaps) levelsFor(bmp.width, bmp.height) else 1).usage(usage)
            .sampler(Texture.Sampler.SAMPLER_2D).format(if (srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8).build(engine)
        TextureHelper.setBitmap(engine, tex, 0, bmp)
        if (mipmaps) tex.generateMipmaps(engine)
        return tex
    }

    fun repeatSampler(): TextureSampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT).apply { anisotropy = 8f }
    fun clampSampler(mip: Boolean = true): TextureSampler =
        TextureSampler(if (mip) TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR else TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    /** The backdrop strip repeats round the cylinder (u) but must not wrap top to bottom (v); mipmapped + anisotropic like render_v2's. */
    fun panoSampler(): TextureSampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)
        .apply { setWrapModeT(TextureSampler.WrapMode.CLAMP_TO_EDGE); anisotropy = 8f }

    private fun grain(bmp: Bitmap, base: IntArray, spread: Int, seed: Int) {
        val r = Random(seed)
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        for (i in px.indices) {
            val n = (r.nextFloat() - 0.5f) * spread; val n2 = (r.nextFloat() - 0.5f) * spread * 0.4f
            fun c(v: Float) = v.toInt().coerceIn(0, 255)
            px[i] = Color.rgb(c(base[0] + n + n2), c(base[1] + n + n2 * 0.6f), c(base[2] + n))
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /**
     * The sealed road tile (render_v2's road canvas): grain, polished wheel tracks in both lanes,
     * white edge lines and a double yellow centre line. u runs along the road over [tileLen] metres,
     * v across it from the far (left, +Z) sealed edge at the top row to the near edge at the bottom.
     */
    fun roadTile(w: Int = 1024, h: Int = 512, tileLen: Float = RoadLayout.TILE_LEN): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        grain(bmp, intArrayOf(60, 61, 64), 22, 5)
        val c = Canvas(bmp); val p = Paint()
        val r = Random(17)
        for (i in 0 until 4000) { p.color = Color.argb((80 + r.nextFloat() * 100).toInt(), 30 + r.nextInt(30), 30 + r.nextInt(30), 32 + r.nextInt(30)); c.drawRect(r.nextFloat() * w, r.nextFloat() * h, r.nextFloat() * w + 3f, r.nextFloat() * h + 2f, p) }
        val pxPerM = h / RoadLayout.ROAD_W
        fun row(z: Float) = (RoadLayout.SEALED_LEFT - z) * pxPerM
        for (zc in listOf(0f, -RoadLayout.LANE_W)) for (off in listOf(-0.8f, 0.8f)) {
            val y = row(zc + off)
            p.shader = LinearGradient(0f, y - 18f, 0f, y + 18f, intArrayOf(Color.argb(0, 90, 90, 92), Color.argb(90, 90, 90, 92), Color.argb(0, 90, 90, 92)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, y - 18f, w.toFloat(), y + 18f, p); p.shader = null
        }
        fun line(z: Float, widthM: Float, color: Int) { val y = row(z); val hw = max(1.5f, widthM * pxPerM / 2); p.color = color; c.drawRect(0f, y - hw, w.toFloat(), y + hw, p) }
        line(RoadLayout.EDGE_LEFT, RoadLayout.LINE_W, Color.argb(230, 232, 232, 226))
        line(RoadLayout.EDGE_RIGHT, RoadLayout.LINE_W, Color.argb(230, 232, 232, 226))
        line(RoadLayout.CENTRE + 0.1f, RoadLayout.LINE_W, Color.argb(235, 226, 186, 52))
        line(RoadLayout.CENTRE - 0.1f, RoadLayout.LINE_W, Color.argb(235, 226, 186, 52))
        // a few dark cracks
        p.strokeWidth = 1f; p.style = Paint.Style.STROKE
        for (i in 0 until 10) { p.color = Color.argb((45 + r.nextFloat() * 50).toInt(), 24, 24, 26); var x = r.nextFloat() * w; var y = r.nextFloat() * h; for (k in 0 until 5) { val nx = x + (r.nextFloat() - 0.5f) * 30; val ny = y + (r.nextFloat() - 0.5f) * 30; c.drawLine(x, y, nx, ny, p); x = nx; y = ny } }
        return bmp
    }

    /** Dry grass and gravel with low scrub, tiled every [RoadLayout.EARTH_TILE] metres. */
    fun earthTile(w: Int = 512, h: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        grain(bmp, intArrayOf(108, 100, 84), 24, 3)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG); val r = Random(11)
        for (i in 0 until 80) { val rr = 15 + r.nextFloat() * 45; p.color = Color.argb(46, 96 + r.nextInt(30), 92 + r.nextInt(22), 72 + r.nextInt(16)); c.drawOval(r.nextFloat() * w - rr * 1.6f, r.nextFloat() * h - rr, r.nextFloat() * w + rr * 1.6f, r.nextFloat() * h + rr, p) }
        for (i in 0 until 550) {
            val rr = 3 + r.nextFloat() * 11; val k = r.nextFloat(); val x = r.nextFloat() * w; val y = r.nextFloat() * h
            p.color = Color.argb((100 + r.nextFloat() * 100).toInt(), (60 + k * 30).toInt(), (68 + k * 28).toInt(), (50 + k * 20).toInt())
            c.drawOval(x - rr * 1.6f, y - rr, x + rr * 1.6f, y + rr, p)
        }
        for (i in 0 until 2000) { p.color = Color.argb((60 + r.nextFloat() * 80).toInt(), 130 + r.nextInt(40), 120 + r.nextInt(34), 96 + r.nextInt(28)); c.drawRect(r.nextFloat() * w, r.nextFloat() * h, r.nextFloat() * w + 2f, r.nextFloat() * h + 2f, p) }
        return bmp
    }

    fun gravelTile(w: Int = 256, h: Int = 128): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        grain(bmp, intArrayOf(138, 128, 112), 40, 9)
        val c = Canvas(bmp); val p = Paint(); val r = Random(21)
        for (i in 0 until 800) { p.color = Color.argb(180, 104 + r.nextInt(70), 98 + r.nextInt(60), 84 + r.nextInt(50)); c.drawRect(r.nextFloat() * w, r.nextFloat() * h, r.nextFloat() * w + 2f, r.nextFloat() * h + 2f, p) }
        return bmp
    }

    /** A soft dark disc, alpha only, for the contact shadow under the truck. */
    fun contactShadow(size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val half = size / 2f
        p.shader = RadialGradient(half, half, half, intArrayOf(Color.argb(255, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        return bmp
    }
}

/** The modelled highway in metres, after render_v2's ROAD table (the truck sits in the left lane, centre line on its right / camera side). */
internal object RoadLayout {
    const val LANE_W = 3.5f
    const val CENTRE = -1.75f
    const val EDGE_LEFT = 1.75f + 0.15f
    const val EDGE_RIGHT = -(1.75f * 3 + 0.15f)
    const val SEALED_LEFT = 1.75f + 0.9f
    const val SEALED_RIGHT = -(1.75f * 3 + 0.9f)
    const val ROAD_W = SEALED_LEFT - SEALED_RIGHT
    const val LINE_W = 0.1f
    const val GRAVEL_W = 1.2f
    const val LAKE_Z = SEALED_LEFT + 3.4f
    const val POST_SPACING = 100f
    const val POST_LEFT_Z = 1.75f + 1.6f
    const val POST_RIGHT_Z = -(1.75f * 3 + 1.6f)
    const val POST_H = 1.2f
    /** How far the road and plain reach either way along X (the backdrop cylinder hides their ends). */
    const val REACH = 600f
    const val TILE_LEN = ROAD_W * 2f
    const val EARTH_TILE = 25f
}

/**
 * Everything around the truck: the sun, the environment light, the backdrop cylinder wearing the
 * head unit's own horizon strip, the road with its verges and guide posts, the contact shadow, and
 * the fog that ties the plain into the strip. [applyTime] re-lights all of it for a [LivePreset].
 */
internal class LiveWorld(private val host: FilamentHost, private val materials: LiveMaterials, private val meta: LiveMeta) {
    private val engine = host.engine
    private val scene = host.scene
    private val tm = engine.transformManager
    private val rm = engine.renderableManager
    private val em = EntityManager.get()

    private val sun = em.create()
    private val prefilter = IBLPrefilterContext(engine)
    private val equirectToCube = IBLPrefilterContext.EquirectangularToCubemap(prefilter)
    private val specularFilter = IBLPrefilterContext.SpecularFilter(prefilter)
    private val ibls = HashMap<String, IndirectLight>()

    private val panoEntity = em.create()
    private val panoMi = materials.create("pano", unlit = true, textured = true, doubleSided = true)
    private var panoTexture: Texture? = null
    private var panoLoaded: TimeOfDay? = null
    private val panoRadius = meta.pano.radius
    private val panoHeight: Float
    private var panoRotation = M4.rotY(meta.pano.heading)

    private val roadMi = materials.create("road", textured = true, uvTransform = true)
    private val earthMi = materials.create("earth", textured = true, uvTransform = true)
    private val gravelMi = materials.create("gravel", textured = true, uvTransform = true)
    private val postMi = materials.create("post", unlit = true, vertexColors = true)
    private val shadowMi = materials.create("contact", unlit = true, textured = true, blend = true)
    private val posts = ArrayList<Int>()
    private val shadowEntity = em.create()
    private var contactShadow = 0.55f
    var preset: LivePreset = LivePreset.of(TimeOfDay.DAY); private set

    init {
        // the strip is as tall as its aspect × the cylinder's circumference, stretched by vscale (render_v2's setBackdrop)
        val stripAspect = 1410f / 6144f
        panoHeight = meta.pano.vscale * stripAspect * 2f * PI.toFloat() * panoRadius
        LightManager.Builder(LightManager.Type.SUN).color(1f, 1f, 1f).intensity(100_000f).direction(0f, -1f, 0f)
            .castShadows(true).sunAngularRadius(1.5f)
            // the truck sits 8–20 m from the camera (zoom 1.6 … 0.5), so the one cascade reaches 40 m
            .shadowOptions(LightManager.ShadowOptions().apply { mapSize = 2048; shadowCascades = 1; shadowFar = 40f; constantBias = 0.001f; normalBias = 1.2f })
            .build(engine, sun)
        scene.addEntity(sun)
        buildPano()
        buildGround()
        buildPosts()
        buildContactShadow()
    }

    private fun addRenderable(entity: Int, mesh: GpuMesh, mi: MaterialInstance, cast: Boolean, receive: Boolean, fog: Boolean) {
        RenderableManager.Builder(1).boundingBox(mesh.box).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib)
            .material(0, mi).castShadows(cast).receiveShadows(receive).culling(false).fog(fog).build(engine, entity)
        tm.create(entity)
        scene.addEntity(entity)
    }

    private fun buildPano() {
        val mesh = LiveGeometry.upload(engine, LiveGeometry.cylinderInside(panoRadius, panoHeight, 160))
        panoMi.setCullingMode(Material.CullingMode.NONE)
        addRenderable(panoEntity, mesh, panoMi, cast = false, receive = false, fog = false)
    }

    private fun buildGround() {
        val r = RoadLayout.REACH
        val road = LiveGeometry.upload(engine, LiveGeometry.quadXZ(-r, r, RoadLayout.SEALED_LEFT, RoadLayout.SEALED_RIGHT, 0f, RoadLayout.TILE_LEN))
        addRenderable(em.create(), road, roadMi, cast = false, receive = true, fog = true)
        // gravel shoulders, then the far verge to the water and the near plain out to the cylinder
        for ((z0, z1) in listOf(RoadLayout.SEALED_LEFT to RoadLayout.SEALED_LEFT + RoadLayout.GRAVEL_W, RoadLayout.SEALED_RIGHT - RoadLayout.GRAVEL_W to RoadLayout.SEALED_RIGHT)) {
            val m = LiveGeometry.upload(engine, LiveGeometry.quadXZ(-r, r, z0, z1, -0.002f, RoadLayout.EARTH_TILE / 2, 1f))
            addRenderable(em.create(), m, gravelMi, cast = false, receive = true, fog = true)
        }
        val verge = LiveGeometry.upload(engine, LiveGeometry.quadXZ(-r, r, RoadLayout.SEALED_LEFT + RoadLayout.GRAVEL_W, RoadLayout.LAKE_Z, -0.004f, RoadLayout.EARTH_TILE, (RoadLayout.LAKE_Z - RoadLayout.SEALED_LEFT - RoadLayout.GRAVEL_W) / RoadLayout.EARTH_TILE))
        addRenderable(em.create(), verge, earthMi, cast = false, receive = true, fog = true)
        val nearEdge = RoadLayout.SEALED_RIGHT - RoadLayout.GRAVEL_W
        val plain = LiveGeometry.upload(engine, LiveGeometry.quadXZ(-r, r, nearEdge, -r, -0.006f, RoadLayout.EARTH_TILE, (r + nearEdge) / RoadLayout.EARTH_TILE))
        addRenderable(em.create(), plain, earthMi, cast = false, receive = true, fog = true)
        roadMi.setParameter("roughnessFactor", 0.9f); roadMi.setParameter("metallicFactor", 0f)
        earthMi.setParameter("roughnessFactor", 1f); gravelMi.setParameter("roughnessFactor", 1f)
    }

    private fun buildPosts() {
        val white = floatArrayOf(0.85f, 0.85f, 0.85f); val red = hexToLinear(0xE62828)
        val mesh = LiveGeometry.upload(engine, LiveGeometry.post(0.1f, RoadLayout.POST_H, 0.14f, white, red, 0.2f))
        val count = (2 * RoadLayout.REACH / RoadLayout.POST_SPACING).toInt() + 1
        for (side in 0..1) for (i in 0 until count) {
            val e = em.create()
            RenderableManager.Builder(1).boundingBox(mesh.box).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib)
                .material(0, postMi).castShadows(true).receiveShadows(false).culling(true).fog(true).build(engine, e)
            tm.create(e)
            scene.addEntity(e)
            posts.add(e)
        }
        scroll(0f)
    }

    private fun buildContactShadow() {
        val wb = (meta.wheels["RR"]!!.hub[0] - meta.wheels["FR"]!!.hub[0]) * meta.toMetres
        val track = meta.wheels["FR"]!!.hub[1] * meta.toMetres
        val midX = (meta.wheels["RR"]!!.hub[0] + meta.wheels["FR"]!!.hub[0]) / 2 * meta.toMetres
        val halfL = wb * 1.25f / 2; val halfW = track * 1.55f
        val mesh = LiveGeometry.upload(engine, MeshData(
            floatArrayOf(midX - halfL, 0.004f, -halfW, midX + halfL, 0.004f, -halfW, midX + halfL, 0.004f, halfW, midX - halfL, 0.004f, halfW),
            FloatArray(12) { if (it % 3 == 1) 1f else 0f }, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), null, intArrayOf(0, 2, 1, 0, 3, 2)))
        RenderableManager.Builder(1).boundingBox(mesh.box).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib)
            .material(0, shadowMi).castShadows(false).receiveShadows(false).culling(false).fog(true).priority(5).build(engine, shadowEntity)
        tm.create(shadowEntity)
        scene.addEntity(shadowEntity)
    }

    /** Textures that take a moment to paint — called off the main thread, then [installTextures] on it. */
    class GroundBitmaps(val road: Bitmap, val earth: Bitmap, val gravel: Bitmap, val shadow: Bitmap)

    fun paintGround(): GroundBitmaps = GroundBitmaps(LiveTextures.roadTile(), LiveTextures.earthTile(), LiveTextures.gravelTile(), LiveTextures.contactShadow())

    fun installTextures(b: GroundBitmaps) {
        val rep = LiveTextures.repeatSampler()
        roadMi.setParameter("baseColorMap", LiveTextures.upload(engine, b.road, srgb = true), rep)
        earthMi.setParameter("baseColorMap", LiveTextures.upload(engine, b.earth, srgb = true), rep)
        gravelMi.setParameter("baseColorMap", LiveTextures.upload(engine, b.gravel, srgb = true), rep)
        shadowMi.setParameter("baseColorMap", LiveTextures.upload(engine, b.shadow, srgb = false), LiveTextures.clampSampler())
        shadowMi.setParameter("baseColorFactor", 0f, 0f, 0f, contactShadow)
        Log.w(LiveSupport.TAG, "ground textures: road ${b.road.width}x${b.road.height}, earth ${b.earth.width}, gravel ${b.gravel.width}, shadow ${b.shadow.width}; " +
            "baseColorIndex road=${if (roadMi.material.hasParameter("baseColorIndex")) "param" else "none"}")
        b.road.recycle(); b.earth.recycle(); b.gravel.recycle(); b.shadow.recycle()
    }

    /** The scene has rolled [metres] under the truck: the road paint and posts slide toward +X (the nose points to −X). */
    fun scroll(metres: Float) {
        val shift = metres % RoadLayout.TILE_LEN / RoadLayout.TILE_LEN
        // the texture's u = x / tileLen; the world moving +X under a fixed camera means sampling further back along u
        roadMi.setParameter("baseColorUvMatrix", MaterialInstance.FloatElement.MAT3, LiveMaterials.uvShift(-shift, 0f), 0, 1)
        val es = metres % RoadLayout.EARTH_TILE / RoadLayout.EARTH_TILE
        earthMi.setParameter("baseColorUvMatrix", MaterialInstance.FloatElement.MAT3, LiveMaterials.uvShift(-es, 0f), 0, 1)
        gravelMi.setParameter("baseColorUvMatrix", MaterialInstance.FloatElement.MAT3, LiveMaterials.uvShift(-(metres % (RoadLayout.EARTH_TILE / 2) / (RoadLayout.EARTH_TILE / 2)), 0f), 0, 1)
        val phase = metres % RoadLayout.POST_SPACING
        val count = posts.size / 2
        for (side in 0..1) for (i in 0 until count) {
            val x = -RoadLayout.REACH + i * RoadLayout.POST_SPACING + phase + 1.3f
            val z = if (side == 0) RoadLayout.POST_LEFT_Z else RoadLayout.POST_RIGHT_Z
            tm.setTransform(tm.getInstance(posts[side * count + i]), M4.translate(x, 0f, z))
        }
    }

    /** Keeps the strip's horizon at the camera's height, so it reads as infinitely far. */
    fun placePano(eyeY: Float) {
        val y = eyeY + panoHeight * (0.5f - meta.pano.horizon)
        tm.setTransform(tm.getInstance(panoEntity), M4.mul(M4.translate(0f, y, 0f), panoRotation))
    }

    /** Which strip (decoded on IO by the caller) the cylinder wears; swaps the texture on the engine thread. */
    fun setPano(time: TimeOfDay, bitmap: Bitmap) {
        // mipmapped: the strip is minified below the horizon (its water rows) and sparkles without them
        val tex = LiveTextures.upload(engine, bitmap, srgb = true, mipmaps = true)
        Log.w(LiveSupport.TAG, "pano ${time.id}: ${bitmap.width}x${bitmap.height} on a cylinder r %.0f m, h %.0f m (horizon row at eye height)".format(panoRadius, panoHeight))
        bitmap.recycle()
        panoMi.setParameter("baseColorMap", tex, LiveTextures.panoSampler())
        panoTexture?.let { engine.destroyTexture(it) }
        panoTexture = tex
        panoLoaded = time
    }

    fun panoShown(): TimeOfDay? = panoLoaded

    /** Reads the strip for [time] from the assets; null when the meta lists none. Call off the main thread. */
    fun decodePano(ctx: Context, time: TimeOfDay): Bitmap? {
        val file = meta.pano.files[time] ?: return null
        return runCatching { ctx.assets.open("${LiveSupport.DIR}/$file").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) } }
            .onFailure { Log.w(LiveSupport.TAG, "pano $file", it) }.getOrNull()
    }

    /** The environment for an id in the meta, prefiltered on the GPU once and kept; null (logged at W) when any step fails. */
    private fun ibl(ctx: Context, id: String): IndirectLight? {
        ibls[id]?.let { return it }
        val file = meta.env[id] ?: run { Log.w(LiveSupport.TAG, "ibl $id: no file in the meta"); return null }
        val bytes = runCatching { ctx.assets.open("${LiveSupport.DIR}/$file").use { it.readBytes() } }.getOrNull()
            ?: run { Log.w(LiveSupport.TAG, "ibl $id: $file missing from the assets"); return null }
        val buf = ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.flip() }
        val equirect = HDRLoader.createTexture(engine, buf) ?: run { Log.w(LiveSupport.TAG, "ibl $id: $file (${bytes.size} B) won't decode"); return null }
        return runCatching {
            val cube = equirectToCube.run(equirect)
            val reflections = specularFilter.run(cube)
            engine.destroyTexture(equirect)
            engine.destroyTexture(cube)
            val light = IndirectLight.Builder().reflections(reflections).intensity(preset.envLux).build(engine)
            ibls[id] = light
            Log.w(LiveSupport.TAG, "ibl $id: $file ${bytes.size} B → equirect ${equirect.getWidth(0)}x${equirect.getHeight(0)} → cube ${cube.getWidth(0)} → reflections ${reflections.getWidth(0)} / ${reflections.levels} levels, ${preset.envLux} lux")
            light
        }.onFailure { Log.w(LiveSupport.TAG, "ibl $id: prefilter failed", it) }.getOrNull()
    }

    /**
     * What lights the scene when the environment can't: a flat ambient (one-band spherical
     * harmonics, no reflections) so the truck is never a black silhouette.
     */
    private fun ambientFallback(): IndirectLight = ibls.getOrPut("__ambient") {
        IndirectLight.Builder().irradiance(1, floatArrayOf(1f, 1f, 1f)).intensity(preset.envLux).build(engine)
    }

    /** Light the world for [p]: sun, environment, fog, bloom, exposure, the strip's tint and the clear colour. The strip itself is swapped by the caller. */
    fun applyTime(ctx: Context, p: LivePreset) {
        preset = p
        val lm = engine.lightManager
        val li = lm.getInstance(sun)
        val d = dirFrom(p.sunAz, p.sunEl)
        val col = hexToLinear(p.sunHex)
        lm.setColor(li, col[0], col[1], col[2])
        lm.setIntensity(li, p.sunLux)
        lm.setDirection(li, -d[0], -d[1], -d[2])
        val light = ibl(ctx, p.env) ?: ambientFallback().also { Log.w(LiveSupport.TAG, "ibl ${p.env} unavailable: flat ambient fallback") }
        light.intensity = p.envLux
        scene.indirectLight = light
        host.camera.setExposure(p.aperture, p.shutter, p.iso)
        Log.w(LiveSupport.TAG, "time ${p.pano.id}: sun %.0f lux az %.0f el %.0f dir (%.2f %.2f %.2f) · ibl %s %.0f lux · exposure f/%.0f 1/%.0f ISO %.0f · fog start %.0f density %.3f · bloom %.2f".format(
            p.sunLux, p.sunAz, p.sunEl, -d[0], -d[1], -d[2], p.env, p.envLux, p.aperture, 1f / p.shutter, p.iso, p.fogStart, p.fogDensity, p.bloom))
        host.view.fogOptions = View.FogOptions().apply {
            enabled = true; distance = p.fogStart; density = p.fogDensity; maximumOpacity = 1f; height = 0f; heightFalloff = 0.02f
            fogColorFromIbl = true; color = floatArrayOf(1f, 1f, 1f); inScatteringStart = 60f; inScatteringSize = 90f
        }
        host.view.bloomOptions = View.BloomOptions().apply { enabled = p.bloom > 0f; strength = p.bloom; resolution = 360; levels = 6; quality = View.QualityLevel.LOW }
        panoMi.setParameter("baseColorFactor", p.panoTint[0], p.panoTint[1], p.panoTint[2], 1f)
        contactShadow = p.contactShadow
        shadowMi.setParameter("baseColorFactor", 0f, 0f, 0f, contactShadow)
        val c = hexToLinear(p.clearHex)
        host.renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true; discard = true; clearColor = doubleArrayOf(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), 0.0)
        }
    }

    fun destroy() {
        ibls.values.forEach { engine.destroyIndirectLight(it) }
        panoTexture?.let { engine.destroyTexture(it) }
        specularFilter.destroy(); equirectToCube.destroy(); prefilter.destroy()
    }
}
