// Static server for the render page plus POST /save?name=<file> which writes the body (a data URL
// or raw text/JSON) into <root>/render/. Usage: node serve.js <root> [port]
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = path.resolve(process.argv[2] || '.');
const port = parseInt(process.argv[3] || '8766', 10);
const types = {
  '.html': 'text/html', '.js': 'text/javascript', '.glb': 'model/gltf-binary', '.json': 'application/json',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.css': 'text/css',
};

http.createServer((req, res) => {
  const u = new URL(req.url, 'http://127.0.0.1');
  if (req.method === 'POST' && u.pathname === '/save') {
    const name = path.basename(u.searchParams.get('name') || 'out.png');
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString();
      const dir = path.join(root, 'render');
      fs.mkdirSync(dir, { recursive: true });
      const data = name.endsWith('.json') ? body : Buffer.from(body.replace(/^data:[^,]+,/, ''), 'base64');
      fs.writeFileSync(path.join(dir, name), data);
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('ok ' + name + ' ' + data.length);
    });
    return;
  }
  const rel = decodeURIComponent(u.pathname === '/' ? '/render.html' : u.pathname);
  const f = path.join(root, rel);
  if (!f.startsWith(root)) { res.writeHead(403); res.end(); return; }
  fs.readFile(f, (e, d) => {
    if (e) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(f).toLowerCase()] || 'application/octet-stream' });
    res.end(d);
  });
}).listen(port, '127.0.0.1', () => console.log('serving', root, 'on http://127.0.0.1:' + port));
