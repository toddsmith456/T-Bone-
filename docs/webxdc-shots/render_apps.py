#!/usr/bin/env python3
"""Serve the exported webxdc tree the way XdcWebViewClient does, and screenshot it.

Not part of the Android build: this is the desktop compatibility harness used to
verify the exported mini apps actually run against the real `webxdc.js` shim.
"""
import json
import mimetypes
import os
import sys
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from functools import partial

HERE = pathlib.Path(__file__).resolve().parent
ROOT = sys.argv[1] if len(sys.argv) > 1 else str(HERE / "served")
SHOTS = sys.argv[2] if len(sys.argv) > 2 else str(HERE / "rendered-apps")
MODE = sys.argv[3] if len(sys.argv) > 3 else "both"  # both | shot | test

CSP = ("default-src 'self' 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval' data: blob:; "
       "base-uri 'self'; form-action 'self'")
COOP = os.environ.get("XDC_COOP") == "1"

MOCK = r"""
(function () {
  var updates = [];
  window.__xdcLog = [];
  window.__xdcErrors = [];
  window.TBoneXdc = {
    call: function (method, id, paramsJson) {
      var p = {};
      try { p = JSON.parse(paramsJson || '{}'); } catch (e) {}
      window.__xdcLog.push(method);
      function resolve(v) {
        setTimeout(function () {
          window.__tboneXdcResolve(id, true, JSON.stringify(v === undefined ? null : v), null);
        }, 0);
      }
      function reject(e) {
        setTimeout(function () { window.__tboneXdcResolve(id, false, null, String(e)); }, 0);
      }
      if (method === 'sendUpdate') {
        var serial = updates.length + 1;
        var u = { payload: p.payload === undefined ? null : p.payload, serial: serial, max_serial: serial };
        ['info', 'document', 'summary', 'href'].forEach(function (k) { if (p[k]) u[k] = p[k]; });
        updates.push(u);
        setTimeout(function () { window.__tboneXdcDeliver(u); }, 0);
        resolve(null);
      } else if (method === 'setUpdateListener') {
        var after = p.serial || 0;
        updates.filter(function (u) { return u.serial > after; }).forEach(function (u) {
          setTimeout(function () { window.__tboneXdcDeliver(u); }, 0);
        });
        setTimeout(function () { window.__tboneXdcResolve(id, true, 'null', null); }, 0);
      } else if (method === 'getAllUpdates') {
        resolve(updates);
      } else if (method === 'importFiles') {
        resolve([]);
      } else if (method === 'sendToChat') {
        resolve(null);
      } else {
        resolve(null);
      }
    }
  };
  window.onerror = function (m) { window.__xdcErrors.push(String(m)); };
})();
"""


class Handler(SimpleHTTPRequestHandler):
    def end_headers(self):
        path = self.path.split("?")[0]
        if path.endswith(".html") or path.endswith("/"):
            self.send_header("Content-Security-Policy", CSP)
            self.send_header("Cache-Control", "no-store")
            if COOP:
                self.send_header("Cross-Origin-Opener-Policy", "same-origin")
                self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
                self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        super().end_headers()

    def log_message(self, *args):
        pass


def start_server(directory):
    handler = partial(Handler, directory=directory)
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, httpd.server_address[1]


def main():
    from playwright.sync_api import sync_playwright

    apps = json.load(open(os.path.join(ROOT, "apps.json")))
    os.makedirs(SHOTS, exist_ok=True)
    report = []

    with sync_playwright() as pw:
        browser = pw.chromium.launch(args=["--enable-unsafe-swiftshader", "--autoplay-policy=no-user-gesture-required"])
        for app in apps:
            # Each app gets its own server root: mini apps may use absolute
            # paths (/assets/...) and must resolve them inside their archive.
            httpd, port = start_server(os.path.join(ROOT, app["id"]))
            base = f"http://127.0.0.1:{port}"
            ctx = browser.new_context(
                viewport={"width": 412, "height": 915},
                device_scale_factor=2.5,
                is_mobile=True,
                has_touch=True,
            )
            ctx.add_init_script(MOCK)
            page = ctx.new_page()
            errors, logs = [], []
            page.on("console", lambda m: logs.append(f"{m.type}: {m.text}"))
            page.on("pageerror", lambda e: errors.append(str(e)))
            url = f"{base}/index.html"
            try:
                page.goto(url, wait_until="load", timeout=30000)
                page.wait_for_timeout(2600)
                slug = "".join(c if c.isalnum() or c in "-_" else "-" for c in app["title"]).strip("-").lower()
                shot = os.path.join(SHOTS, f"app-{slug}.png")
                page.screenshot(path=shot)
                # Exercise the real shim from inside the running app page.
                probe = page.evaluate(r"""
                  (async () => {
                    const out = { hasWebxdc: typeof window.webxdc === 'object', selfAddr: window.webxdc && window.webxdc.selfAddr };
                    try {
                      const seen = [];
                      await window.webxdc.setUpdateListener(u => seen.push(u.serial), 0);
                      await window.webxdc.sendUpdate({ payload: { proof: 'tbone' }, info: 'proof' });
                      await new Promise(r => setTimeout(r, 120));
                      out.listenerSaw = seen;
                      const all = await window.webxdc.getAllUpdates();
                      out.count = all.length;
                      out.last = all[all.length - 1];
                    } catch (e) { out.error = String(e); }
                    return out;
                  })()
                """)
                report.append({
                    "title": app["title"], "id": app["id"], "shot": shot,
                    "probe": probe, "pageErrors": errors[:6], "console": logs[-8:],
                    "crossOriginIsolated": page.evaluate("window.crossOriginIsolated"),
                })
                print(f"OK {app['title']}: webxdc={probe.get('hasWebxdc')} updates={probe.get('count')} errors={len(errors)}")
            except Exception as e:
                print(f"FAIL {app['title']}: {e}")
                report.append({"title": app["title"], "id": app["id"], "fatal": str(e)})
            ctx.close()
            httpd.shutdown()
        browser.close()

    json.dump(report, open(os.path.join(SHOTS, "report.json"), "w"), indent=2)


if __name__ == "__main__":
    main()
