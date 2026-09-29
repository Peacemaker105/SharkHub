const { NodeIO } = require('@gltf-transform/core');
(async () => {
  const io = new NodeIO();
  const doc = await io.read(process.argv[2]);
  const root = doc.getRoot();
  console.log('scenes', root.listScenes().length, 'nodes', root.listNodes().length, 'meshes', root.listMeshes().length,
    'materials', root.listMaterials().length, 'textures', root.listTextures().length, 'extensions', root.listExtensionsUsed().map(e => e.extensionName).join(','));
  let tris = 0, verts = 0;
  for (const mesh of root.listMeshes()) for (const prim of mesh.listPrimitives()) {
    const pos = prim.getAttribute('POSITION'); const idx = prim.getIndices();
    const t = idx ? idx.getCount() / 3 : pos.getCount() / 3; tris += t; verts += pos.getCount();
    console.log('prim:', mesh.getName() || '(mesh)', 'verts', pos.getCount(), 'tris', t, 'attrs', prim.listSemantics().join(','),
      'min', JSON.stringify(pos.getMin([]).map(v => +v.toFixed(3))), 'max', JSON.stringify(pos.getMax([]).map(v => +v.toFixed(3))));
  }
  console.log('TOTAL verts', verts, 'tris', tris);
  for (const tex of root.listTextures()) console.log('texture', JSON.stringify(tex.getName()), tex.getMimeType(), (tex.getImage().byteLength / 1048576).toFixed(1), 'MB', JSON.stringify(tex.getSize()));
  for (const n of root.listNodes()) console.log('node', JSON.stringify(n.getName()), 'T', JSON.stringify(n.getTranslation()), 'R', JSON.stringify(n.getRotation().map(v => +v.toFixed(3))), 'S', JSON.stringify(n.getScale()), 'mesh', n.getMesh() ? 'yes' : '-');
  for (const m of root.listMaterials()) console.log('material', JSON.stringify(m.getName()), 'baseColorTex', !!m.getBaseColorTexture(), 'metallic', m.getMetallicFactor(), 'rough', m.getRoughnessFactor(), 'normalTex', !!m.getNormalTexture(), 'mrTex', !!m.getMetallicRoughnessTexture());
})().catch(e => { console.error('ERR', e.message); process.exit(1); });
