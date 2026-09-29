// Split a single-mesh car GLB into body + four hub-centred wheel nodes.
// Usage: node split.js in.glb out.glb
// Geometry (model units, from wheels.js on the Meshy output): nose is -X, axles at x = -0.62 (front)
// and +0.53 (rear), ground y = -0.445, track z = +-0.295. Wheel radius 0.39 m at 5.457 m -> 1.895
// units = 0.135. Each wheel is cut with a cylinder coaxial to its axle and a z-band that keeps the
// arch flares out, so spinning the wheel node never opens a gap along the cut.
const { NodeIO } = require('@gltf-transform/core');
const { prune } = require('@gltf-transform/functions');

// Defaults are the first guess from the real tyre size; pass measure.js's JSON as the 3rd argument
// to use what the mesh actually has.
let RADIUS = 0.135;
let GROUND_Y = -0.445;
let AXLE_Y = GROUND_Y + RADIUS;
let FRONT_X = -0.62;
let REAR_X = 0.53;
let TRACK = 0.295;
let Z_INNER = 0.20;
let Z_OUTER = 0.35;
let CUT_RADIUS = RADIUS + 0.012;

(async () => {
  const [, , input, output, paramsPath] = process.argv;
  if (paramsPath) {
    const p = JSON.parse(require('fs').readFileSync(paramsPath, 'utf8'));
    RADIUS = p.radius; GROUND_Y = p.ground; AXLE_Y = p.axleY; FRONT_X = p.frontX; REAR_X = p.rearX;
    TRACK = p.track; Z_INNER = p.zInner; Z_OUTER = p.zOuter; CUT_RADIUS = p.radius + (p.cutMargin || 0.01);
    console.log('using measured params', JSON.stringify(p));
  }
  const io = new NodeIO();
  const doc = await io.read(input);
  const root = doc.getRoot();
  const scene = root.listScenes()[0];
  const srcMesh = root.listMeshes()[0];
  const srcPrim = srcMesh.listPrimitives()[0];
  const material = srcPrim.getMaterial();
  const P = srcPrim.getAttribute('POSITION').getArray();
  const N = srcPrim.getAttribute('NORMAL') ? srcPrim.getAttribute('NORMAL').getArray() : null;
  const T = srcPrim.getAttribute('TEXCOORD_0').getArray();
  const I = srcPrim.getIndices().getArray();

  const wheels = [
    { name: 'wheel_FL', x: FRONT_X, side: +1 },
    { name: 'wheel_FR', x: FRONT_X, side: -1 },
    { name: 'wheel_RL', x: REAR_X, side: +1 },
    { name: 'wheel_RR', x: REAR_X, side: -1 },
  ];
  const buckets = [[], [], [], [], []]; // 0 = body
  for (let t = 0; t < I.length / 3; t++) {
    const a = I[3 * t], b = I[3 * t + 1], c = I[3 * t + 2];
    const cx = (P[3 * a] + P[3 * b] + P[3 * c]) / 3;
    const cy = (P[3 * a + 1] + P[3 * b + 1] + P[3 * c + 1]) / 3;
    const cz = (P[3 * a + 2] + P[3 * b + 2] + P[3 * c + 2]) / 3;
    let k = 0;
    for (let w = 0; w < 4; w++) {
      const wh = wheels[w];
      const dz = cz * wh.side;
      if (dz < Z_INNER || dz > Z_OUTER) continue;
      const dx = cx - wh.x, dy = cy - AXLE_Y;
      if (dx * dx + dy * dy <= CUT_RADIUS * CUT_RADIUS) { k = w + 1; break; }
    }
    buckets[k].push(a, b, c);
  }

  const buffer = root.listBuffers()[0];
  function makePrim(indices, origin) {
    const map = new Map();
    const pos = [], nor = [], uv = [], idx = [];
    for (const vi of indices) {
      let ni = map.get(vi);
      if (ni === undefined) {
        ni = map.size;
        map.set(vi, ni);
        pos.push(P[3 * vi] - origin[0], P[3 * vi + 1] - origin[1], P[3 * vi + 2] - origin[2]);
        if (N) nor.push(N[3 * vi], N[3 * vi + 1], N[3 * vi + 2]);
        uv.push(T[2 * vi], T[2 * vi + 1]);
      }
      idx.push(ni);
    }
    const prim = doc.createPrimitive().setMaterial(material);
    prim.setAttribute('POSITION', doc.createAccessor().setType('VEC3').setArray(new Float32Array(pos)).setBuffer(buffer));
    if (N) prim.setAttribute('NORMAL', doc.createAccessor().setType('VEC3').setArray(new Float32Array(nor)).setBuffer(buffer));
    prim.setAttribute('TEXCOORD_0', doc.createAccessor().setType('VEC2').setArray(new Float32Array(uv)).setBuffer(buffer));
    prim.setIndices(doc.createAccessor().setType('SCALAR')
      .setArray(map.size > 65535 ? new Uint32Array(idx) : new Uint16Array(idx)).setBuffer(buffer));
    return prim;
  }

  for (const n of root.listNodes()) n.dispose();
  srcMesh.dispose();
  const parts = [{ name: 'body', origin: [0, 0, 0] }]
    .concat(wheels.map((w) => ({ name: w.name, origin: [w.x, AXLE_Y, w.side * TRACK] })));
  parts.forEach((part, k) => {
    if (!buckets[k].length) { console.log(part.name, 'EMPTY'); return; }
    const mesh = doc.createMesh(part.name).addPrimitive(makePrim(buckets[k], part.origin));
    scene.addChild(doc.createNode(part.name).setMesh(mesh).setTranslation(part.origin));
    console.log(part.name, 'tris', buckets[k].length / 3, 'origin', JSON.stringify(part.origin));
  });
  await doc.transform(prune());
  await io.write(output, doc);
  console.log('wrote', output, require('fs').statSync(output).size, 'bytes');
})().catch((e) => { console.error('ERR', e.stack || e.message); process.exit(1); });
