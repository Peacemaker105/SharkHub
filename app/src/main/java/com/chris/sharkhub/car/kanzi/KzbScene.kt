package com.chris.sharkhub.car.kanzi

/**
 * The scene-graph side of a kzb (a port of kzb_place.py): the prefab node trees are parsed, prefab
 * instances (PrefabView3D / DynamicPrefabView3D / placeholders) are expanded, bound render
 * transforms are resolved from BYD's pose helper vectors and every mesh gets its world transform.
 *
 * Record layouts (verified on both of BYD's files): an object is `u32 name string-id, u32 ''-id,
 * u32 metaclass, u32 nprops, {prop id, value}…`; a node continues `u32 0, u32 nchildren, children…,
 * u32 nbindings, bindings…, u32 ncomponents, components…, u32 resource-dictionary id`. A binding is
 * `target prop, field, 1, K, nsources, nsources × (path id, prop, field), X, nconst, consts, nops,
 * nops × 40 B`. Node3D final transform = LayoutTransformation × RenderTransformation.
 */
class KNode(
    val name: String,
    val cls: String,
    val props: LinkedHashMap<String, Any?>,
    val off: Int,
    val placeholderOf: String? = null,
) {
    val children = ArrayList<KNode>()
    /** Attached records: components in strict mode, stray objects in scan mode. */
    val attached = ArrayList<Any>()
    val bindings = ArrayList<Binding>()
    var templateExtra: String? = null
    var mode: String? = null
    var resdict: String? = null

    // runtime state set by the walk (never copied into an instance)
    var parent: KNode? = null
    var path: String = ""
    var world: DoubleArray = Mat4.IDENT
    var posed = false
    var dupOfView = false

    fun copyTree(): KNode {
        val n = KNode(name, cls, LinkedHashMap(props), off, placeholderOf)
        n.templateExtra = templateExtra; n.resdict = resdict
        children.forEach { n.children.add(it.copyTree()) }
        n.attached.addAll(attached)
        n.bindings.addAll(bindings)
        return n
    }

    val isNode: Boolean get() = cls in KanziTypes.NODE_CLASSES
    val shortCls: String get() = cls.removePrefix("Kanzi.")
}

class Binding(val target: String, val field: Int, val sources: List<Triple<String, String, Int>>, val constants: List<Double>, val ops: Int)
class KComponent(val name: String, val cls: String, val props: Map<String, Any?>, val sub: List<KComponent>)

class KzbParser(private val k: KzbFile) {
    val unknown = LinkedHashMap<String, Int>()
    val warnings = ArrayList<String>()

    fun dtype(pid: Int): Int? = k.propertyTypes[k.props[pid]] ?: KanziTypes.BUILTIN[k.props[pid]]

    fun readValue(b: ByteArray, p0: Int, dt: Int): Pair<Any?, Int> {
        var p = p0
        if (dt == 9 || dt == 11) {
            val ln = Bytes.u32(b, p).toInt()
            if (ln > 4096 || ln < 0 || p + 4 + ln > b.size) throw ParseError("bad string")
            return String(b, p + 4, ln, Charsets.UTF_8) to p + 4 + ln
        }
        val sz = KanziTypes.SIZE[dt] ?: throw ParseError("no size for type $dt")
        if (p + sz > b.size) throw ParseError("eob")
        val v: Any = when (dt) {
            0 -> Bytes.f32(b, p).toDouble()
            1, 10 -> Bytes.i32(b, p)
            2 -> Bytes.u32(b, p) != 0L
            else -> DoubleArray(sz / 4) { Bytes.f32(b, p + it * 4).toDouble() }
        }
        p += sz
        return v to p
    }

    fun isHeader(b: ByteArray, p: Int): Boolean {
        if (p + 16 > b.size) return false
        val nm = Bytes.u32(b, p); val em = Bytes.u32(b, p + 4); val mc = Bytes.u32(b, p + 8); val npr = Bytes.u32(b, p + 12)
        if (nm >= k.strings.size || npr >= 64) return false
        if (em == k.emptyId.toLong()) return mc < k.metas.size
        // prefab placeholder node (a template dropped into another template): name, template path, 0xFFFFFFFF
        return mc == 0xFFFFFFFFL && em < k.strings.size && k.strings[em.toInt()].startsWith("kzb://")
    }

    fun parseObject(b: ByteArray, p0: Int): Pair<KNode, Int> {
        var p = p0
        val nm = Bytes.u32(b, p); val em = Bytes.u32(b, p + 4); val mc = Bytes.u32(b, p + 8); val npr = Bytes.u32(b, p + 12)
        p += 16
        if (nm >= k.strings.size) throw ParseError("name id")
        val obj = if (mc == 0xFFFFFFFFL) {
            if (em >= k.strings.size) throw ParseError("placeholder id")
            KNode(k.strings[nm.toInt()], "PrefabPlaceholder", LinkedHashMap(), p0, k.strings[em.toInt()])
        } else {
            if (mc >= k.metas.size) throw ParseError("metaclass id")
            KNode(k.strings[nm.toInt()], k.metas[mc.toInt()], LinkedHashMap(), p0)
        }
        for (i in 0 until npr) {
            if (p + 4 > b.size) throw ParseError("eob")
            val pid = Bytes.u32(b, p).toInt(); p += 4
            if (pid < 0 || pid >= k.props.size) throw ParseError("bad pid")
            val dt = dtype(pid)
            if (dt == null) {
                unknown[k.props[pid]] = (unknown[k.props[pid]] ?: 0) + 1
                throw ParseError("unknown type for '${k.props[pid]}'")
            }
            val (v, next) = readValue(b, p, dt)
            obj.props[k.props[pid]] = v
            p = next
        }
        return obj to p
    }

    /** The next parseable object header at or after [p] (byte-wise scan), or null at [limit]. */
    fun scan(b: ByteArray, p0: Int, limit: Int? = null): Pair<KNode?, Int> {
        val end = limit ?: b.size
        var p = p0
        while (p + 16 <= end) {
            if (isHeader(b, p)) {
                try { return parseObject(b, p) } catch (_: ParseError) { }
            }
            p++
        }
        return null to end
    }

    fun parseBinding(b: ByteArray, p0: Int): Pair<Binding, Int> {
        var p = p0
        val t = Bytes.u32(b, p); val f = Bytes.u32(b, p + 4); val one = Bytes.u32(b, p + 8); val kk = Bytes.u32(b, p + 12); val ns = Bytes.u32(b, p + 16)
        if (!(t < k.props.size && f < 32 && one == 1L && kk < 1024 && ns < 1024)) throw ParseError("binding header")
        p += 20
        val srcs = ArrayList<Triple<String, String, Int>>()
        for (i in 0 until ns) {
            val a = Bytes.u32(b, p); val bb = Bytes.u32(b, p + 4); val c = Bytes.u32(b, p + 8)
            if (!(a < k.strings.size && bb < k.props.size && c < 32)) throw ParseError("binding source")
            srcs.add(Triple(k.strings[a.toInt()], k.props[bb.toInt()], c.toInt()))
            p += 12
        }
        val nconst = Bytes.u32(b, p + 4); p += 8
        if (nconst > 1024) throw ParseError("binding constants")
        val consts = ArrayList<Double>()
        for (i in 0 until nconst) {
            val ct = Bytes.u32(b, p).toInt(); p += 4
            val sz = KanziTypes.SIZE[ct]
            if (ct == 0) consts.add(Bytes.f32(b, p).toDouble())
            else if (!KanziTypes.SIZE.containsKey(ct) || sz == null) throw ParseError("binding constant type $ct")
            p += sz ?: 4
        }
        val nops = Bytes.u32(b, p); p += 4
        if (nops > 4096 || p + 40L * nops > b.size) throw ParseError("binding ops")
        p += (40 * nops).toInt()
        return Binding(k.props[t.toInt()], f.toInt(), srcs, consts, nops.toInt()) to p
    }

    /** Node component (animation players, triggers, actions): u32 metaclass, u32 name, u32 nprops, props, u32 nsub, sub-records. */
    fun parseComponent(b: ByteArray, p0: Int, depth: Int = 0): Pair<KComponent, Int> {
        var p = p0
        val mc = Bytes.u32(b, p); val nm = Bytes.u32(b, p + 4); val npr = Bytes.u32(b, p + 8)
        if (mc >= k.metas.size || nm >= k.strings.size || npr > 64) throw ParseError("component header")
        p += 12
        val props = LinkedHashMap<String, Any?>()
        for (i in 0 until npr) {
            val pid = Bytes.u32(b, p).toInt(); p += 4
            if (pid < 0 || pid >= k.props.size) throw ParseError("bad pid")
            val dt = dtype(pid)
            if (dt == null) {
                unknown[k.props[pid]] = (unknown[k.props[pid]] ?: 0) + 1
                throw ParseError("unknown type for '${k.props[pid]}'")
            }
            val (v, next) = readValue(b, p, dt); props[k.props[pid]] = v; p = next
        }
        val nsub = Bytes.u32(b, p); p += 4
        if (nsub > 64 || depth > 4) throw ParseError("component sub-record count")
        val sub = ArrayList<KComponent>()
        for (i in 0 until nsub) {
            val (c, next) = parseComponent(b, p, depth + 1); sub.add(c); p = next
        }
        return KComponent(k.strings[nm.toInt()], k.metas[mc.toInt()], props, sub) to p
    }

    /** Strict: u32 nbindings, bindings, u32 ncomponents, components, u32 resource-dictionary string id. */
    fun parseTrailer(b: ByteArray, p0: Int, node: KNode): Int {
        var p = p0
        val n = Bytes.u32(b, p); p += 4
        if (n > 256) throw ParseError("nbindings")
        for (i in 0 until n) { val (bd, next) = parseBinding(b, p); node.bindings.add(bd); p = next }
        val nc = Bytes.u32(b, p); p += 4
        if (nc > 64) throw ParseError("ncomponents")
        for (i in 0 until nc) { val (c, next) = parseComponent(b, p); node.attached.add(c); p = next }
        val rd = Bytes.u32(b, p); p += 4
        if (rd >= k.strings.size) throw ParseError("resource dictionary id")
        node.resdict = k.strings[rd.toInt()]
        return p
    }

    fun parseNodeStrict(b: ByteArray, p0: Int, depth: Int = 0): Pair<KNode, Int> {
        if (!isHeader(b, p0)) throw ParseError("node header expected at 0x${Integer.toHexString(p0)}")
        val (obj, p1) = parseObject(b, p0)
        var p = p1
        if (!obj.isNode) throw ParseError("${obj.cls} is not a node class")
        val a = Bytes.u32(b, p); val nch = Bytes.u32(b, p + 4); p += 8
        if (a != 0L) throw ParseError("leading count $a")
        if (nch > 500) throw ParseError("child count $nch")
        for (i in 0 until nch) { val (child, next) = parseNodeStrict(b, p, depth + 1); obj.children.add(child); p = next }
        p = parseTrailer(b, p, obj)
        return obj to p
    }

    /** Fallback: siblings located by scanning for the next object header; bindings summarised best-effort. */
    fun parseNodeScan(b: ByteArray, p0: Int, depth: Int = 0): Pair<KNode, Int> {
        val (obj, p1) = parseObject(b, p0)
        var p = p1
        if (obj.isNode) {
            val a = Bytes.u32(b, p); val nch = Bytes.u32(b, p + 4); p += 8
            if (a != 0L || nch > 500) throw ParseError("not a node record")
            var got = 0
            while (got < nch) {
                val (o2, e) = scan(b, p)
                if (o2 == null) throw ParseError("ran out of data looking for child ${got + 1}/$nch of ${obj.name}")
                if (o2.isNode) {
                    val child: KNode
                    try {
                        val r = parseNodeScan(b, o2.off, depth + 1); child = r.first; p = r.second
                    } catch (_: ParseError) {
                        p = o2.off + 1; continue          // a false header inside binding data
                    }
                    obj.children.add(child); got++
                } else {
                    (obj.children.lastOrNull() ?: obj).attached.add(o2); p = e
                }
            }
            // bindings that follow (for a leaf these are its own; for a parent they may be the last child's)
            try {
                val n = Bytes.u32(b, p); var q = p + 4
                for (i in 0 until minOf(n, 64L)) { val (bd, next) = parseBinding(b, q); obj.bindings.add(bd); q = next }
            } catch (_: ParseError) { }
        }
        return obj to p
    }

    fun parsePrefab(entry: String): KNode {
        val b = k.blob(entry)
        try {
            var (root, p) = parseNodeStrict(b, 4)
            if (p == b.size - 4) {                       // some files end a template with one more string id
                val id = Bytes.u32(b, p)
                root.templateExtra = if (id < k.strings.size) k.strings[id.toInt()] else null
                p = b.size
            }
            if (p != b.size) throw ParseError("${b.size - p} trailing bytes")
            root.mode = "strict"
            return root
        } catch (e: ParseError) {
            warnings.add("$entry: strict parse failed (${e.message}), used scanning parse")
            unknown.clear()
            var (root, p) = parseNodeScan(b, 4)
            while (true) {
                val (o2, e2) = scan(b, p)
                if (o2 == null) break
                root.attached.add(o2); p = e2
            }
            root.mode = "scan"
            return root
        }
    }
}

/** Where a mesh sits in the scene, as kzb_place.py writes it into `<var>_placement.json`. */
class Placement(
    var source: String,                 // "node" | "orphan" | "orphan+rule"
    var node: String?,
    var world: Mat4.Srt?,
    var matrix: DoubleArray?,           // 4×4 row-major, rounded like the Python (5 dp) when it came from a node
    var material: String?,
    var textures: Map<String, String>,
    var note: String?,
    var state: String? = null,
    var visible: Boolean? = null,
    var otherInstances: List<String> = emptyList(),
)

/** An explicit pose for a node path (rage_pose_overrides.json): scale, quaternion (w, x, y, z), translation and the reason. */
class PoseOverride(val scale: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0), val quaternion: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0), val translation: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0), val why: String = "")

/** A placement rule for a mesh no node references (rage_orphans.json). */
class OrphanRule(val scale: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0), val quaternion: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0), val translation: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0), val material: String? = null, val textures: Map<String, String> = emptyMap(), val why: String = "")

class SceneEntry(val path: String, val cls: String, val depth: Int, val local: Mat4.Srt, var world: Mat4.Srt, val note: String?, var instanceOf: String? = null)

class KzbScene(
    private val k: KzbFile,
    private val pose: String = "Space",
    private val overrides: Map<String, PoseOverride> = emptyMap(),
    private val ignoreRotations: Boolean = false,
    private val log: (String) -> Unit = {},
) {
    val parser = KzbParser(k)
    private val templates = LinkedHashMap<String, KNode>()
    val nodes = ArrayList<SceneEntry>()
    /** Mesh short name → every node instance that draws it (first = the one the placement uses). */
    val meshes = LinkedHashMap<String, MutableList<Placement>>()
    lateinit var root: KNode
    private var byPath: HashMap<String, KNode>? = null
    val warnings: List<String> get() = parser.warnings

    private fun template(entry: String): KNode = templates.getOrPut(entry) { parser.parsePrefab(entry) }

    /** Top-level prefab entries (the scene roots and templates). */
    fun prefabEntries(): List<String> = k.names.filter { it.startsWith("/Prefabs/") && it.count { c -> c == '/' } == 2 && (k.dir[it]?.size ?: 0) > 16 }

    /** The prefab the screen shows: a `RootNode*` when there is one, else the first prefab. */
    fun defaultRoot(): String {
        val prefabs = prefabEntries()
        return prefabs.firstOrNull { it.substringAfterLast('/').startsWith("RootNode") } ?: prefabs.first()
    }

    fun walk(rootEntry: String = defaultRoot()): KzbScene {
        val tmpl = template(rootEntry)
        walkNode(tmpl, null, Mat4.IDENT, "/" + tmpl.name, 0)
        return this
    }

    /** Kanzi relative path: '.', '..', '#Name' (search upwards for an ancestor's descendant), 'A/B'. */
    private fun resolvePath(node: KNode, path: String): KNode? {
        var parts = path.split("/")
        var cur: KNode? = node
        if (parts.isNotEmpty() && parts[0].startsWith("#")) {
            val target = parts[0].substring(1)
            var anc: KNode? = node
            var found: KNode? = null
            while (anc != null && found == null) { found = findNamed(anc, target); anc = anc.parent }
            if (found == null) return null
            cur = found; parts = parts.drop(1)
        }
        for (seg in parts) {
            if (seg == "." || seg.isEmpty()) continue
            cur = if (seg == "..") cur?.parent else cur?.children?.firstOrNull { it.name == seg }
            if (cur == null) return null
        }
        return cur
    }

    private fun findNamed(node: KNode, name: String): KNode? {
        if (node.name == name) return node
        for (c in node.children) findNamed(c, name)?.let { return it }
        return null
    }

    /** Node by its walked path (first match). */
    fun findNode(path: String): KNode? {
        if (byPath == null) {
            val m = HashMap<String, KNode>()
            fun rec(n: KNode) { if (!m.containsKey(n.path)) m[n.path] = n; n.children.forEach { rec(it) } }
            rec(root)
            byPath = m
        }
        return byPath!![path]
    }

    /** LayoutTransformation × RenderTransformation, with bound render transforms resolved from pose helpers. */
    private fun localMatrix(node: KNode): Pair<DoubleArray, String?> {
        val lt = node.props["Node3D.LayoutTransformation"] as? DoubleArray
        var rt = node.props["Node3D.RenderTransformation"] as? DoubleArray
        var bound = node.bindings.firstOrNull { it.target == "Node3D.RenderTransformation" }
        var note: String? = null
        var rtFromBinding = false
        val ov = overrides[node.path]
        if (ov != null) {
            rt = ov.scale + ov.quaternion + ov.translation
            note = "override: " + ov.why
            node.posed = true
            rtFromBinding = true
        } else if (bound != null && node.dupOfView) {
            // the template root repeats the view node's helper binding (an older copy without the steering inputs):
            // Kanzi would not stack them either, the view node already carries the pose
            bound = null
            note = "RenderTransformation binding duplicates the instantiating view node's - applied once (on the view node)"
        } else if (bound != null) {
            // BYD's expression builds the SRT from helper vectors on pivot nodes; the position and the rotation
            // sources can live on different pivots (the LF/BL wheels take their rotation from FR/BR)
            var posSrc: Pair<String, KNode>? = null
            var rotSrc: Pair<String, KNode>? = null
            for ((path, prop, _) in bound.sources) {
                if (prop.startsWith("VehicleNodePosHelper_") && posSrc == null) resolvePath(node, path)?.let { posSrc = path to it }
                if (prop.startsWith("VehicleNodeRotateHelper_") && rotSrc == null) resolvePath(node, path)?.let { rotSrc = path to it }
            }
            if (posSrc != null || rotSrc != null) {
                val pos = (posSrc?.second?.props?.get("VehicleNodePosHelper_$pose") as? DoubleArray) ?: doubleArrayOf(0.0, 0.0, 0.0)
                val rot = (rotSrc?.second?.props?.get("VehicleNodeRotateHelper_$pose") as? DoubleArray) ?: doubleArrayOf(0.0, 0.0, 0.0)
                rt = doubleArrayOf(1.0, 1.0, 1.0) + Mat4.eulerDegToQuat(rot) + pos
                rtFromBinding = true
                node.posed = true
                note = "RenderTransformation bound: pos from ${posSrc?.first ?: "-"}, rot from ${rotSrc?.first ?: "-"}, pose $pose -> pos ${Mat4.round(pos, 4).toList()} rot(deg) ${Mat4.round(rot, 3).toList()}"
            } else {
                note = "RenderTransformation bound (sources ${bound.sources.take(4)}) - unresolved, identity used"
            }
        }
        var m = Mat4.IDENT
        if (lt != null) m = Mat4.mul(m, Mat4.fromSrt10(lt, ignoreRotation = ignoreRotations && ov == null))
        if (rt != null) {
            // rest pose: stored rotations are the artist's snapshot (PA's doors are saved open); bound and overridden ones stay
            m = Mat4.mul(m, Mat4.fromSrt10(rt, ignoreRotation = ignoreRotations && ov == null && !rtFromBinding))
        }
        return m to note
    }

    private fun walkNode(node: KNode, parent: KNode?, parentWorld: DoubleArray, path: String, depth: Int) {
        if (parent == null) root = node
        node.parent = parent; node.path = path
        val (local, note) = localMatrix(node)
        val world = Mat4.mul(parentWorld, local)
        node.world = world
        val entry = SceneEntry(path, node.shortCls, depth, Mat4.decompose(local), roundSrt(Mat4.decompose(world)), note)
        nodes.add(entry)
        val mesh = node.props["Model3D.Mesh"] as? String
        if (mesh != null) {
            val short = mesh.substringAfterLast('/')
            val tex = LinkedHashMap<String, String>()
            for (key in listOf("Texture", "NormalTexture", "BaseColorTexture", "OcclusionTexture")) {
                (node.props[key] as? String)?.takeIf { it.isNotEmpty() }?.let { tex[key] = it.substringAfterLast('/') }
            }
            meshes.getOrPut(short) { ArrayList() }.add(Placement(
                source = "node", node = path, world = entry.world, matrix = Mat4.round(world, 5),
                material = (node.props["Model3D.Material"] as? String ?: "").substringAfterLast('/'),
                textures = tex, note = note,
                state = (node.props["Node.StateManager"] as? String)?.substringAfterLast('/')?.takeIf { it.isNotEmpty() },
                visible = node.props["Node.Visible"] as? Boolean ?: true,
            ))
        }
        // prefab instance: the template root becomes the child of the view / placeholder node
        val refs = KanziTypes.PREFAB_PROPS.map { node.props[it] as? String } + node.placeholderOf
        for (ref in refs) {
            if (ref.isNullOrEmpty()) continue
            val ent = if ("://" in ref) "/" + ref.substringAfter("://").substringAfter("/") else ref
            if (k.has(ent)) {
                val inst = template(ent).copyTree()
                if (node.posed) inst.dupOfView = true
                node.children.add(inst)
                entry.instanceOf = ent
            } else {
                log("  ! $path references missing prefab $ref")
            }
        }
        for (c in ArrayList(node.children)) walkNode(c, node, world, path + "/" + c.name, depth + 1)
    }

    private fun roundSrt(s: Mat4.Srt) = Mat4.Srt(Mat4.round(s.scale), Mat4.round(s.quaternion), Mat4.round(s.translation))

    /** Express every world transform in the frame of the node at [path] (drops the scene placement above it). */
    fun relativeTo(path: String) {
        val ref = findNode(path) ?: throw ParseError("--relative-to: no node $path")
        val inv = Mat4.inverse(ref.world)
        for (e in nodes) {
            val nd = findNode(e.path) ?: continue
            nd.world = Mat4.mul(inv, nd.world)
            e.world = roundSrt(Mat4.decompose(nd.world))
        }
        for ((_, insts) in meshes) for (inst in insts) {
            val nd = findNode(inst.node ?: continue) ?: continue
            inst.world = roundSrt(Mat4.decompose(nd.world))
            inst.matrix = Mat4.round(nd.world, 5)
        }
        log("world transforms expressed relative to $path")
    }

    /** The placement table for every mesh in the file, with [orphanRules] applied to the unreferenced ones. */
    fun placements(orphanRules: Map<String, OrphanRule> = emptyMap()): LinkedHashMap<String, Placement> {
        val out = LinkedHashMap<String, Placement>()
        for (name in k.meshNames) {
            val short = name.substringAfterLast('/')
            val inst = meshes[short]
            if (inst != null && inst.isNotEmpty()) {
                val p = inst[0]
                out[short] = Placement(p.source, p.node, p.world, p.matrix, p.material, p.textures, p.note, p.state, p.visible,
                    otherInstances = inst.drop(1).mapNotNull { it.node })
            } else {
                out[short] = Placement("orphan", null, null, null, null, emptyMap(), "no node in any prefab references this mesh")
            }
        }
        for ((m, rule) in orphanRules) {
            val p = out[m] ?: continue
            if (p.source != "orphan") continue
            p.source = "orphan+rule"
            p.world = Mat4.Srt(rule.scale, rule.quaternion, rule.translation)
            p.matrix = Mat4.fromSrt(rule.scale, rule.quaternion, rule.translation)
            p.material = rule.material
            p.textures = rule.textures
            p.note = rule.why
        }
        return out
    }
}
