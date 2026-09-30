// Static server for the render pages plus POST /save?name=<file> which writes the body (a data URL
// or raw text/JSON) into <root>/render/.
// Usage: node serve.js <root> [port] [extraRoot ...]
// Files are looked up in <root> first, then in each extra root in order, so a generic page in the
// repo (tools/model) can be served next to a private model folder without copying either.
const http = require('http');
const fs = require('fs');
const path = require('path');

const root = path.resolve(process.argv[2] || '.');
const port = parseInt(process.argv[3] || '8766', 10);
const roots = [root].concat(process.argv.slice(4).map((r) => path.resolve(r)));
const types = {
  '.html': 'text/html', '.js': 'text/javascript', '.glb': 'model/gltf-binary', '.json': 'application/json',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.css': 'text/css', '.hdr': 'application/octet-stream',
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
  const candidates = roots.map((r) => path.join(r, rel)).filter((f, i) => f.startsWith(roots[i]));
  const f = candidates.find((c) => fs.existsSync(c) && fs.statSync(c).isFile());
  if (!f) { res.writeHead(404); res.end('not found'); return; }
  fs.readFile(f, (e, d) => {
    if (e) { res.writeHead(404); res.end('not found'); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(f).toLowerCase()] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(d);
  });
}).listen(port, '127.0.0.1', () => console.log('serving', roots.join(' + '), 'on http://127.0.0.1:' + port));
