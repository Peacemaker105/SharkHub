"""One-command re-render of the v2 layer set (e.g. after a paint colour change).

Usage (from the repo root, PowerShell):
  python tools\\model\\render_v2.py --root C:\\dev\\byd_factory --rig render_v2/byd_shark6.rig.json --paint 1c3f6e ^
                                   --out app\\src\\main\\assets\\car_private
Options: --times day,dawn,dusk,night  --wheel-times night  --phases 12  --no-views  --port 8766
         --no-open (drive the page yourself)  --no-serve (a server is already running)  --no-pack  --timeout 1800

What happens:
  1. tools/model/render_v2.html is copied to <root>/render_v2/ (the working page next to the rig),
  2. serve.js serves <root> + tools/model + design/model on the port (the page, the private model,
     the chassis GLB and its params),
  3. the default browser opens the page with ?auto=1 and it renders every layer, POSTing PNGs into
     <root>/render/ and finishing with <tag>_done.json,
  4. pack_v2.py assembles <out> (optimised layers, WebP plates, meta, previews).
The rig JSON holds everything model-specific (GLB path, hubs, textures); --paint is a sRGB hex.
"""
import argparse, json, os, shutil, socket, subprocess, sys, time, urllib.parse, webbrowser

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))


def port_open(port):
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="private model folder (served as /)")
    ap.add_argument("--rig", required=True, help="rig JSON path relative to --root, e.g. render_v2/byd_shark6.rig.json")
    ap.add_argument("--out", default=os.path.join("app", "src", "main", "assets", "car_private"))
    ap.add_argument("--paint", default=None, help="sRGB hex, e.g. 1c3f6e (omit for the rig's default)")
    ap.add_argument("--tag", default="v2")
    ap.add_argument("--times", default="day,dawn,dusk,night")
    ap.add_argument("--wheel-times", default="night")
    ap.add_argument("--phases", type=int, default=12)
    ap.add_argument("--no-views", action="store_true")
    ap.add_argument("--no-extras", action="store_true", help="skip the lit-lamp overlays and blurred road plates")
    ap.add_argument("--port", type=int, default=8766)
    ap.add_argument("--no-open", action="store_true")
    ap.add_argument("--no-serve", action="store_true")
    ap.add_argument("--no-pack", action="store_true")
    ap.add_argument("--timeout", type=int, default=1800)
    ap.add_argument("--pack-args", default="", help="extra arguments for pack_v2.py, e.g. \"--bg png\"")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    work = os.path.join(root, "render_v2")
    os.makedirs(work, exist_ok=True)
    shutil.copy2(os.path.join(HERE, "render_v2.html"), os.path.join(work, "render_v2.html"))
    render_dir = os.path.join(root, "render")
    os.makedirs(render_dir, exist_ok=True)
    done = os.path.join(render_dir, f"{args.tag}_done.json")
    if os.path.exists(done):
        os.remove(done)

    server = None
    if not args.no_serve and not port_open(args.port):
        server = subprocess.Popen(["node", os.path.join(HERE, "serve.js"), root, str(args.port), HERE, os.path.join(REPO, "design", "model")])
        for _ in range(50):
            if port_open(args.port):
                break
            time.sleep(0.1)
    q = {"rig": "/" + args.rig.replace("\\", "/"), "auto": "1", "times": args.times, "wheelTimes": args.wheel_times, "phases": str(args.phases), "tag": args.tag}
    if args.paint:
        q["paint"] = args.paint.lstrip("#")
    if args.no_views:
        q["views"] = "0"
    if args.no_extras:
        q["extras"] = "0"
    for stale in (f"{args.tag}_extras.json", f"{args.tag}_meta.json"):
        if os.path.exists(os.path.join(render_dir, stale)):
            os.remove(os.path.join(render_dir, stale))
    url = f"http://127.0.0.1:{args.port}/render_v2/render_v2.html?" + urllib.parse.urlencode(q)
    print("page:", url, flush=True)
    if not args.no_open:
        webbrowser.open(url)
    print(f"waiting for {done} (up to {args.timeout}s)…", flush=True)
    t0 = time.time()
    while not os.path.exists(done):
        if time.time() - t0 > args.timeout:
            print("timed out — is the page open and rendering?", file=sys.stderr)
            if server: server.terminate()
            return 2
        time.sleep(2)
    time.sleep(1)
    print("render done:", json.load(open(done)), flush=True)
    rc = 0
    if not args.no_pack:
        cmd = [sys.executable, os.path.join(HERE, "pack_v2.py"), render_dir, os.path.abspath(args.out), "--tag", args.tag] + args.pack_args.split()
        print(" ".join(cmd), flush=True)
        rc = subprocess.call(cmd)
    if server:
        server.terminate()
    return rc


if __name__ == "__main__":
    sys.exit(main())
