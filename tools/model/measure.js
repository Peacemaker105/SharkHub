// Measure the wheels straight from the mesh so split.js cuts the right cylinder.
// Usage: node measure.js in.glb out.json
// Assumes nose = -X, Y up, track along Z (see wheels.js). Finds the axle x from the ground-contact
// clusters, the tyre top from the first empty band above the ground in a slice through the tread,
// and the sidewall extents from the |z| range of that slice.
const { NodeIO } = require('@gltf-transform/core');
const fs = require('fs');

(async () => {
  const [, , input, outJson] = process.argv;
  const io = new NodeIO();
  const doc = await io.read(input);
  const prim = doc.getRoot().listMeshes()[0].listPrimitives()[0];
  const pos = prim.getAttribute('POSITION');
  const n = pos.getCount();
  const el = [0, 0, 0];
  const min = pos.getMin([]), max = pos.getMax([]);
  const ground = min[1];
  const height = max[1] - ground;

  // axle x: mean of the ground-contact vertices on each side of x = 0
  const contact = { neg: [], pos: [] };
  const thr = ground + height * 0.05;
  for (let i = 0; i < n; i++) { pos.getElement(i, el); if (el[1] < thr) (el[0] < 0 ? contact.neg : contact.pos).push(el[0]); }
  const mean = (a) => a.reduce((s, x) => s + x, 0) / a.length;
  const frontX = mean(contact.neg), rearX = mean(contact.pos);

  function measureAxle(ax, label) {
    // Tyre radius from the contact chord: at height h above the ground the footprint's half-width c
    // satisfies c^2 = h(2r - h). Two heights, 5th/95th percentiles against outliers, averaged.
    const radii = [];
    for (const frac of [0.03, 0.06]) {
      const h = height * frac; const xs = [];
      for (let i = 0; i < n; i++) { pos.getElement(i, el); if (el[1] - ground < h && Math.abs(el[0] - ax) < 0.3 && Math.abs(el[2]) > 0.15) xs.push(el[0]); }
      xs.sort((a, b) => a - b);
      const c = (xs[Math.floor(xs.length * 0.95)] - xs[Math.floor(xs.length * 0.05)]) / 2;
      radii.push((c * c + h * h) / (2 * h));
    }
    const radius = (radii[0] + radii[1]) / 2, axleY = ground + radius, top = ground + 2 * radius;
    const hist = []; // kept for the log line below
    let zmin = Infinity, zmax = 0, count = 0;
    for (let i = 0; i < n; i++) {
      pos.getElement(i, el);
      const dx = el[0] - ax, dy = el[1] - axleY, az = Math.abs(el[2]);
      if (az > 0.15 && dx * dx + dy * dy < (radius * 0.9) * (radius * 0.9)) { count++; if (az < zmin) zmin = az; if (az > zmax) zmax = az; }
    }
    console.log(label, 'axleX', ax.toFixed(3), 'tyreTop', top.toFixed(3), 'radius', radius.toFixed(3), 'axleY', axleY.toFixed(3),
      'sidewalls |z|', zmin.toFixed(3), '..', zmax.toFixed(3), '(' + count + ' verts)');
    console.log('  y-hist:', hist.slice(0, 40).map((h, b) => (ground + b * span / bins).toFixed(2) + ':' + h).join(' '));
    return { axleX: ax, radius, axleY, zInner: zmin, zOuter: zmax };
  }
  const f = measureAxle(frontX, 'front'), r = measureAxle(rearX, 'rear');
  const params = {
    ground, frontX: f.axleX, rearX: r.axleX,
    radius: (f.radius + r.radius) / 2, axleY: (f.axleY + r.axleY) / 2,
    track: (f.zInner + f.zOuter + r.zInner + r.zOuter) / 4,
    zInner: Math.max(0.15, Math.min(f.zInner, r.zInner) - 0.01),
    zOuter: Math.max(f.zOuter, r.zOuter) + 0.01,
    cutMargin: 0.008,
  };
  fs.writeFileSync(outJson, JSON.stringify(params, null, 2));
  console.log('params', JSON.stringify(params));
})().catch((e) => { console.error('ERR', e.stack || e.message); process.exit(1); });
