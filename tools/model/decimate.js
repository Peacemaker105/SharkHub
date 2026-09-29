const { NodeIO } = require('@gltf-transform/core');
const { weld, simplify, prune, dedup } = require('@gltf-transform/functions');
const { MeshoptSimplifier } = require('meshoptimizer');
(async () => {
  const [,, input, output, ratioStr] = process.argv;
  const ratio = parseFloat(ratioStr || '0.035');
  const io = new NodeIO();
  const doc = await io.read(input);
  await MeshoptSimplifier.ready;
  const t0 = Date.now();
  await doc.transform(weld(), simplify({ simplifier: MeshoptSimplifier, ratio: ratio, error: 0.01 }), dedup(), prune());
  let tris = 0, verts = 0;
  for (const m of doc.getRoot().listMeshes()) for (const p of m.listPrimitives()) { const i = p.getIndices(); tris += i ? i.getCount() / 3 : p.getAttribute('POSITION').getCount() / 3; verts += p.getAttribute('POSITION').getCount(); }
  await io.write(output, doc);
  console.log('wrote', output, 'tris', tris, 'verts', verts, 'bytes', require('fs').statSync(output).size, 'in', ((Date.now() - t0) / 1000).toFixed(1) + 's');
})().catch(e => { console.error('ERR', e.stack || e.message); process.exit(1); });
