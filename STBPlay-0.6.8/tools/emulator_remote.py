#!/usr/bin/env python3
"""Local emulator test remote. Run with python3 tools/emulator_remote.py."""
import argparse
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import webbrowser

KEYS = {"up": 19, "down": 20, "left": 21, "right": 22, "ok": 23, "back": 4}

PAGE = r'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>STBPlay emulator remote</title>
<style>
body{background:#0b1220;color:#f8fafc;font:17px system-ui;margin:0;padding:28px;text-align:center}
main{max-width:430px;margin:auto}h1{font-size:25px}p{color:#b8c8db;line-height:1.5}
.pad{display:grid;grid-template-columns:repeat(3,82px);gap:12px;justify-content:center;margin:28px 0}
button{background:#1c2d45;color:white;border:2px solid #58718c;border-radius:18px;font:20px system-ui;height:72px;cursor:pointer}
button:focus-visible,button.active{outline:3px solid #52c7ff;outline-offset:3px}
button[data-key=ok]{background:#0789d8}#back{width:160px}
#status{min-height:52px;font-size:14px}kbd{background:#25364e;padding:3px 7px;border-radius:5px}
</style><main><h1>STBPlay emulator remote</h1>
<p>Keep this panel focused while watching the emulator.</p>
<p><kbd>Arrow keys</kbd> move · <kbd>Enter</kbd> selects<br><kbd>Esc</kbd> goes back</p>
<div class="pad"><span></span><button data-key="up" aria-label="Up">▲</button><span></span>
<button data-key="left" aria-label="Left">◀</button><button data-key="ok">OK</button><button data-key="right" aria-label="Right">▶</button>
<span></span><button data-key="down" aria-label="Down">▼</button><span></span></div>
<button id="back" data-key="back">Back</button>
<p>Select the on-screen keyboard’s ✓ / Done key when you finish typing.</p>
<p id="status" role="status">Checking emulator…</p></main>
<script>
const token = location.pathname.split('/')[1];
const keys = {ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right',Enter:'ok',Escape:'back'};
const status = document.querySelector('#status');
let queue = Promise.resolve(), pending = 0;
function send(key) {
 if (pending >= 8) return;
 pending++;
 const button = document.querySelector('[data-key="'+key+'"]');
 button.classList.add('active');setTimeout(()=>button.classList.remove('active'),150);
 queue=queue.then(async()=>{
  try {
   const response=await fetch('/api/key',{method:'POST',headers:{'Content-Type':'application/json','X-Remote-Token':token},body:JSON.stringify({key})});
   const result=await response.json();status.textContent=result.message;
  } catch(e){status.textContent='Remote stopped. Run the Python command again.';}
  finally {pending--;}
 });
}
document.addEventListener('keydown',event=>{
 const key=keys[event.key];
 if(!key || event.altKey || event.ctrlKey || event.metaKey) return;
 event.preventDefault();
 if(event.repeat && (key==='ok'||key==='back')) return;
 send(key);
});
document.querySelectorAll('[data-key]').forEach(button=>button.addEventListener('click',()=>send(button.dataset.key)));
fetch('/api/status',{headers:{'X-Remote-Token':token}}).then(r=>r.json()).then(r=>status.textContent=r.message).catch(()=>status.textContent='Remote unavailable.');
</script></html>'''


def find_adb():
    found = shutil.which("adb")
    if found:
        return found
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
             str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk")]
    if os.environ.get("LOCALAPPDATA"):
        roots.append(str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk"))
    for root in filter(None, roots):
        candidate = Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError("Android SDK adb not found. Set ANDROID_HOME to your SDK folder.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Target emulator, for example emulator-5554")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        assert KEYS["ok"] == 23 and KEYS["ok"] != 66
        assert all(code in {4, 19, 20, 21, 22, 23} for code in KEYS.values())
        assert "Enter:'ok'" in PAGE
        assert "event.preventDefault()" in PAGE
        print("Passed: computer Enter maps to DPAD_CENTER (23), not ENTER (66).")
        return
    adb = find_adb()
    result = subprocess.run([adb, "devices"], capture_output=True, text=True, check=True, timeout=10)
    devices = re.findall(r"^(emulator-\d+)\s+device\s*$", result.stdout, re.M)
    if args.serial:
        if args.serial not in devices:
            parser.error("Selected emulator is not connected. Start it in Android Studio first.")
        serial = args.serial
    elif len(devices) == 1:
        serial = devices[0]
    else:
        parser.error("Start one emulator, or choose it with --serial emulator-5554.")
    token = secrets.token_urlsafe(24)

    def command(*parts):
        return subprocess.run([adb, "-s", serial, *parts], capture_output=True, text=True,
                              check=True, timeout=10).stdout

    def app_open():
        return bool(re.search(r"(?:mResumedActivity|topResumedActivity).*com\.stbplay\.app/",
                              command("shell", "dumpsys", "activity", "activities")))

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def reply(self, value, code=200):
            body = json.dumps(value).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            return secrets.compare_digest(self.headers.get("X-Remote-Token", ""), token)

        def do_GET(self):
            if self.path == f"/{token}/":
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Referrer-Policy", "no-referrer")
                self.end_headers()
                self.wfile.write(PAGE.encode())
            elif self.path == "/api/status" and self.authorized():
                try:
                    ready = app_open()
                    self.reply({"message": f"Ready · {serial}" if ready else "Open STBPlay in the emulator first."})
                except (OSError, subprocess.SubprocessError):
                    self.reply({"message": "Emulator unavailable. Check Android Studio."}, 503)
            else:
                self.reply({"message": "Not found"}, 404)

        def do_POST(self):
            origin = f"http://127.0.0.1:{self.server.server_port}"
            if self.path != "/api/key" or not self.authorized() or self.headers.get("Origin") != origin:
                self.reply({"message": "Request rejected"}, 403)
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 128:
                    raise ValueError()
                key = json.loads(self.rfile.read(size)).get("key")
                if key not in KEYS:
                    raise ValueError()
                if not app_open():
                    self.reply({"message": "Open STBPlay in the emulator first."}, 409)
                    return
                command("shell", "input", "keyevent", str(KEYS[key]))
                self.reply({"message": f"Sent {key.upper()} · {serial}"})
            except (ValueError, AttributeError):
                self.reply({"message": "Invalid key"}, 400)
            except (OSError, subprocess.SubprocessError):
                self.reply({"message": "Emulator unavailable. Check Android Studio."}, 503)

    server = HTTPServer(("127.0.0.1", 0), Handler)
    url = f"http://127.0.0.1:{server.server_port}/{token}/"
    print(f"STBPlay emulator remote: {url}", flush=True)
    print("Keep the remote page focused. Ctrl+C here stops the remote.", flush=True)
    if not args.no_browser:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
