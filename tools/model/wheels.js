const { NodeIO } = require('@gltf-transform/core');
(async () => {
  const io = new NodeIO(); const doc = await io.read(process.argv[2]);
  const prim = doc.getRoot().listMeshes()[0].listPrimitives()[0];
  const pos = prim.getAttribute('POSITION'); const n = pos.getCount();
  const min = pos.getMin([]), max = pos.getMax([]);
  const height = max[1] - min[1];
  const thr = min[1] + height * 0.05;
  const xs = [], zs = []; const el = [0, 0, 0];
  for (let i = 0; i < n; i++) { pos.getElement(i, el); if (el[1] < thr) { xs.push(el[0]); zs.push(el[2]); } }
  const bins = 40; const hist = new Array(bins).fill(0);
  for (const x of xs) { const b = Math.min(bins - 1, Math.floor((x - min[0]) / (max[0] - min[0]) * bins)); hist[b]++; }
  console.log('bounds min', JSON.stringify(min.map(v => +v.toFixed(3))), 'max', JSON.stringify(max.map(v => +v.toFixed(3))));
  console.log('ground-contact verts (y < ' + thr.toFixed(3) + '):', xs.length);
  console.log('x histogram:'); console.log(hist.map((h, i) => (min[0] + (i + 0.5) * (max[0] - min[0]) / bins).toFixed(2) + ':' + h).join('  '));
  const mean = a => a.length ? a.reduce((s, x) => s + x, 0) / a.length : NaN;
  const zl = zs.filter(z => z < 0), zr = zs.filter(z => z >= 0);
  console.log('z mean neg', mean(zl).toFixed(3), '(' + zl.length + ')', 'pos', mean(zr).toFixed(3), '(' + zr.length + ')');
  // where is the roof (tent) vs bonnet along x: mean y of the top 3% of vertices per x-third
  const topThr = max[1] - height * 0.03; const thirds = [[], [], []];
  for (let i = 0; i < n; i++) { pos.getElement(i, el); if (el[1] > topThr) thirds[Math.min(2, Math.floor((el[0] - min[0]) / (max[0] - min[0]) * 3))].push(el[1]); }
  console.log('top-3% vertex counts by x-third (rear?/mid/front?):', thirds.map(a => a.length).join(' / '));
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
