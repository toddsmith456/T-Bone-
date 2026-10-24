#!/usr/bin/env python3
"""Render the T-Bone webxdc UI from the Compose layout constants.

No emulator exists in this environment (/dev/kvm is absent), so these screens are
generated from the same palette, type scale, spacing and strings used by the
Kotlin composables in app/src/main/kotlin/social/tbone/ui/toolbox/webxdc/*.kt and
ui/toolbox/ToolboxScreen.kt. They are pixel-accurate renderings of the layout,
not photographs of a device.
"""
import base64
import re
import json
import os
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
ICONS = str(HERE / "icons")
APPS = str(HERE / "apps")
OUT = str(HERE)
FONTS = str(HERE / "fonts")

# BonyColors (Theme.kt)
BG = "#0A0A0A"
SURFACE = "#0F0F0E"
SURFACE_ALT = "#0D0D0C"
RULE = "#2A2826"
RULE_STRONG = "#3A3733"
TEXT = "#E8E6DF"
TEXT_DIM = "#9A968D"
TEXT_MUTE = "#6B6862"
ACCENT = "#7FDE6B"
DANGER = "#D96A5A"
LINK = "#82C8FF"


def icon(name):
    p = os.path.join(ICONS, name + ".svg")
    svg = pathlib.Path(p).read_text()
    return svg


def img(path):
    return "file://" + path


def b64_img(path):
    ext = pathlib.Path(path).suffix.lower().lstrip(".")
    mime = {"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp"}.get(ext, "image/png")
    return f"data:{mime};base64," + base64.b64encode(pathlib.Path(path).read_bytes()).decode()


def app_icon(name, size, radius=10):
    return (f'<div class="appicon" style="width:{size}px;height:{size}px;border-radius:{radius}px">'
            f'<img src="{img(os.path.join(APPS, name))}"></div>')


def letter_icon(text, size, radius=10):
    return (f'<div class="appicon" style="width:{size}px;height:{size}px;border-radius:{radius}px">'
            f'<span class="letter">{text}</span></div>')


def vec(name, size, color):
    svg = icon(name).replace('<svg ', f'<svg fill="{color}" ', 1)
    return f'<span class="vec" style="width:{size}px;height:{size}px">{svg}</span>'


STATUS = """
<div class="statusbar">
  <div class="sb-left">9:41</div>
  <div class="sb-right">
    <span class="bars"><i></i><i></i><i></i><i></i></span>
    <span class="batt"></span>
  </div>
</div>
"""

BASE_CSS = """
@font-face { font-family: 'JBMono'; font-weight: 400; src: url('file://__FONTS__/jetbrains_mono_regular.ttf'); }
@font-face { font-family: 'JBMono'; font-weight: 600; src: url('file://__FONTS__/jetbrains_mono_semibold.ttf'); }
@font-face { font-family: 'JBMono'; font-weight: 700; src: url('file://__FONTS__/jetbrains_mono_bold.ttf'); }
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { width: 412px; height: 915px; background: BG; }
body { background: BG; color: TEXT; font-family: 'JBMono', monospace; -webkit-font-smoothing: antialiased; overflow: hidden; }
.screen { position: relative; width: 412px; height: 915px; background: BG; display: flex; flex-direction: column; }
.statusbar { height: 30px; display: flex; align-items: center; justify-content: space-between;
             padding: 0 20px; font-size: 11px; letter-spacing: .5px; color: TEXT_MUTE; flex: 0 0 auto; }
.sb-right { display: flex; align-items: center; gap: 6px; }
.bars { display: inline-flex; align-items: flex-end; gap: 2px; height: 10px; }
.bars i { width: 2px; background: TEXT_MUTE; display: block; }
.bars i:nth-child(1) { height: 4px; } .bars i:nth-child(2) { height: 6px; }
.bars i:nth-child(3) { height: 8px; } .bars i:nth-child(4) { height: 10px; }
.batt { width: 18px; height: 9px; border: 1px solid TEXT_MUTE; border-radius: 2px; display: inline-block; position: relative; }
.batt::after { content: ''; position: absolute; left: 1px; top: 1px; bottom: 1px; width: 12px; background: TEXT_MUTE; }
.header { display: flex; align-items: center; padding: 10px 14px; flex: 0 0 auto; }
.back { font-size: 15px; color: TEXT_MUTE; }
.hgap { width: 8px; }
.header-title { flex: 1; min-width: 0; }
.title { font-size: 15px; line-height: 22px; color: TEXT; }
.subtitle { font-size: 11px; line-height: 14px; letter-spacing: 1.5px; color: TEXT_MUTE; }
.divider { height: 1px; background: RULE; flex: 0 0 auto; }
.body { flex: 1 1 auto; overflow: hidden; position: relative; }
.row { display: flex; align-items: center; padding: 12px 14px; }
.row-between { border-bottom: 1px solid RULE; }
.appicon { background: SURFACE_ALT; border: 1px solid RULE; overflow: hidden; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.appicon img { width: 100%; height: 100%; object-fit: cover; display: block; }
.letter { font-size: 13px; font-weight: 600; letter-spacing: 1px; color: TEXT_MUTE; }
.vec { display: inline-flex; align-items: center; justify-content: center; }
.vec svg { width: 100%; height: 100%; display: block; }
.meta { font-size: 13px; line-height: 18px; letter-spacing: .4px; color: TEXT_DIM; }
.caption { font-size: 11px; line-height: 14px; letter-spacing: 1.5px; color: TEXT_MUTE; }
.tag { font-size: 11px; line-height: 14px; letter-spacing: 1px; font-weight: 600; }
.button { font-size: 13px; line-height: 16px; letter-spacing: 1px; font-weight: 600; }
.ellipsis { color: TEXT_MUTE; font-size: 15px; padding: 4px 10px; }
.plus { width: 36px; height: 36px; border: 1px solid RULE_STRONG; background: SURFACE_ALT;
        display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.square-btn { display: flex; align-items: center; justify-content: center; border: 1px solid RULE; background: SURFACE_ALT; }
"""

TOKENS = {
    "BG": BG, "SURFACE_ALT": SURFACE_ALT, "SURFACE": SURFACE,
    "RULE_STRONG": RULE_STRONG, "RULE": RULE,
    "TEXT_DIM": TEXT_DIM, "TEXT_MUTE": TEXT_MUTE, "TEXT": TEXT,
    "ACCENT": ACCENT, "DANGER": DANGER, "LINK": LINK,
}


def sub(css):
    """Replace bare colour tokens (BG, TEXT_MUTE, RULE …) with their hex value."""
    for name in sorted(TOKENS, key=len, reverse=True):
        css = re.sub(r"(?<![#\w-])" + name + r"\b", TOKENS[name], css)
    return css


BASE_CSS = sub(BASE_CSS)


def page(body_html, extra_css=""):
    return f"""<!doctype html><html><head><meta charset="utf-8">
<style>{BASE_CSS.replace("__FONTS__", FONTS)}{sub(extra_css)}</style></head><body><div class="screen">{STATUS}{body_html}</div></body></html>"""


# ── 1. Toolbox ───────────────────────────────────────────────────────────────
def toolbox():
    tools = [
        ("widgets", "webxdc", "single-player · local, store & nostr"),
        ("sports_esports", "chess club", "webxdc mini app"),
        ("edit_note", "encrypted notes", "notes, checklists, folders & inline attachments"),
        ("bookmark_border", "bookmarks", "encrypted local bookmarks, folders & quotes"),
        ("mic", "voice recorder", "record, anonymize, save or share a voice note"),
        ("place", "geohash channels", "location channels with an anonymous per-area npub"),
        ("calendar_month", "calendar", "fully local encrypted calendar"),
        ("bolt", "wallet tool", "Lightning zaps & Monero xaps"),
    ]
    rows = ""
    for name, title, subtitle in tools:
        rows += f"""
        <div class="trow">
          <div class="tsq">{vec(name, 22, ACCENT)}</div>
          <div class="ttxt"><div class="tt">{title}</div><div class="ts">{subtitle}</div></div>
          <div class="drag">≡</div>
        </div>"""
    return page(f"""
    <div class="header">
      <span class="back">←</span><div class="hgap"></div>
      <div class="header-title"><span class="title">toolbox</span></div>
      <div class="square-btn" style="padding:4px 8px">{vec('settings', 16, TEXT_MUTE)}</div>
    </div>
    <div class="divider"></div>
    <div class="caption" style="padding:6px 14px">long-press a tool to reorder</div>
    <div class="body">{rows}
      <div style="height:16px"></div>
      <div class="caption" style="padding:0 14px">more tools coming soon</div>
    </div>""", """
    .trow { height: 68px; display: flex; align-items: center; padding: 14px; }
    .tsq { width: 40px; height: 40px; border: 1px solid RULE; background: SURFACE_ALT;
           display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
    .ttxt { flex: 1; margin-left: 12px; min-width: 0; }
    .tt { font-size: 15px; line-height: 22px; color: TEXT; }
    .ts { font-size: 13px; line-height: 18px; letter-spacing: .4px; color: TEXT_MUTE;
           max-height: 18px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
    .drag { font-size: 15px; color: TEXT_MUTE; margin-left: 8px; flex: 0 0 auto; }
    """)


# ── tabs ─────────────────────────────────────────────────────────────────────
def tabs(active, counts):
    labels = [("saved", "SAVED"), ("store", "STORE"), ("t-bone", "TBONE"), ("nostr", "NOSTR")]
    out = '<div class="tabs">'
    for label, key in labels:
        n = counts.get(key, 0)
        is_active = label == active
        color = ACCENT if is_active else TEXT_MUTE
        dim = "#3F6E36" if is_active else TEXT_MUTE
        out += f"""<div class="tab">
            <div class="tabline"><span class="tag" style="color:{color}">{label}</span>
            {f'<span class="cap2" style="color:{dim}">{n}</span>' if n else ''}</div>
            <div class="ind" style="background:{ACCENT if is_active else 'transparent'}"></div>
        </div>"""
    return out + "</div>"


TAB_CSS = """
.tabs { display: flex; }
.tab { flex: 1; padding-top: 10px; text-align: center; }
.tabline { display: flex; align-items: center; justify-content: center; gap: 4px; }
.cap2 { font-size: 11px; line-height: 14px; letter-spacing: 1.5px; }
.ind { height: 2px; margin-top: 8px; }
"""


def xdc_header():
    return f"""
    <div class="header">
      <span class="back">←</span><div class="hgap"></div>
      <div class="header-title"><div class="title">webxdc</div>
        <div class="subtitle" style="letter-spacing:0;font-size:11px">single-player mini apps</div></div>
      <div class="square-btn" style="width:34px;height:34px">{vec('add', 18, ACCENT)}</div>
    </div>
    <div class="divider"></div>"""


# ── 2. saved ─────────────────────────────────────────────────────────────────
def saved():
    apps = [
        ("xdc-chess.png", "XDC-Chess", "63.5 kB · 2 hours ago · tool"),
        ("arcanecircle-2048.png", "2048", "36.5 kB · yesterday"),
        ("arcanecircle-arkanoid.png", "Arkanoid", "71.8 kB · yesterday"),
        ("oregon-trail.png", "Oregon Trail", "9.3 MB · 2 days ago · tool"),
        ("shell.png", "Shell", "160 kB · 3 days ago"),
    ]
    rows = ""
    for icon_name, title, meta in apps:
        rows += f"""
        <div class="row row-between">
          {app_icon(icon_name, 48)}
          <div style="flex:1;margin-left:12px;min-width:0">
            <div class="title" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{title}</div>
            <div class="caption" style="margin-top:2px;letter-spacing:0">{meta}</div>
          </div>
          <div class="ellipsis">⋯</div>
        </div>"""
    return page(xdc_header() + tabs("saved", {"SAVED": 5, "STORE": 167, "TBONE": 0, "NOSTR": 30})
                + f'<div class="body">{rows}</div>', TAB_CSS)


# ── 3. store ─────────────────────────────────────────────────────────────────
def store():
    entries = [
        ("arcanecircle-2048.png", "2048", "Join numbers to a 2048 tile", "game · 36.5 kB"),
        ("arcanecircle-arkanoid.png", "Arkanoid", "Brick-breaker game", "game · 71.8 kB"),
        ("arcanecircle-audiodash.png", "Audio Dash", "Play the wave!", "game · 19.2 kB"),
        ("breisa-betterlist.png", "BetterList", "A better list", "tool · 32.7 kB"),
        ("arcanecircle-pixel-dungeon.png", "Arcane Dungeon", "Rogue-like RPG game", "game · 3.2 MB"),
        ("arcanecircle-backread.png", "Back Read", "Read the conversation from the start", "game · 86.9 kB"),
    ]
    rows = ""
    for icon_name, title, sub, meta in entries:
        rows += f"""
        <div class="row row-between">
          {app_icon(icon_name, 52)}
          <div style="flex:1;margin:0 10px 0 12px;min-width:0">
            <div class="title" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{title}</div>
            <div class="meta" style="margin-top:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">{sub}</div>
            <div class="caption" style="margin-top:2px;letter-spacing:0">{meta}</div>
          </div>
          <div class="plus">{vec('add', 18, ACCENT)}</div>
        </div>"""
    return page(xdc_header() + tabs("store", {"SAVED": 5, "STORE": 167, "TBONE": 0, "NOSTR": 30})
                + f"""
    <div class="searchwrap"><div class="searchbox"><span class="meta" style="color:{TEXT_MUTE}">filter apps</span></div></div>
    <div class="body">
      <div class="caption" style="padding:8px 14px;letter-spacing:0">official repository · apps.testrun.org · tap + to download</div>
      {rows}
    </div>""", TAB_CSS + f"""
    .searchwrap {{ padding: 8px 14px; }}
    .searchbox {{ border: 1px solid {RULE}; border-radius: 4px; padding: 15px 16px; }}
    """)


# ── 4. t-bone feed (empty state, which is the truth right now) ───────────────
def tbone():
    return page(xdc_header() + tabs("t-bone", {"SAVED": 5, "STORE": 167, "TBONE": 0, "NOSTR": 30})
                + f"""
    <div class="searchwrap"><div class="searchbox"><span class="meta" style="color:{TEXT_MUTE}">filter apps</span></div></div>
    <div class="body">
      <div class="empty">
        <div class="title" style="color:{TEXT_DIM};font-size:15px">the t-bone npub hasn't published a mini app yet</div>
        <div class="caption" style="margin-top:6px;letter-spacing:0">npub1zx2…ftjq · kind 1063 · #m application/x-webxdc</div>
        <div class="refresh">{vec('refresh', 14, TEXT_MUTE)}<span class="button" style="color:{TEXT_MUTE};margin-left:6px">refresh</span></div>
      </div>
    </div>""", TAB_CSS + f"""
    .searchwrap {{ padding: 8px 14px; }}
    .searchbox {{ border: 1px solid {RULE}; border-radius: 4px; padding: 15px 16px; }}
    .empty {{ position:absolute; inset:0; display:flex; flex-direction:column; align-items:center; justify-content:center; padding: 28px; text-align:center; }}
    .refresh {{ margin-top:14px; display:flex; align-items:center; border:1px solid {RULE}; padding:8px 14px; }}
    """)


# ── 5. nostr feed ────────────────────────────────────────────────────────────
def nostr():
    rows = [
        ("iridescent-puddle.png", "Iridescent Puddle", "A calm particle toy you can drag around", "nostr · 844 kB"),
        ("xdc-chess.png", "XDC-Chess", "Chess with engine, puzzles and tournaments", "nostr · 63.8 kB"),
        ("heretic-shareware.png", "Heretic (Shareware)", "DOS classic running in the browser", "nostr · 10.6 MB"),
        ("oregon-trail.png", "Oregon Trail", "The 1990 classic", "nostr · 9.5 MB"),
        ("mahjong.png", "Mahjong", "Solitaire mahjong", "nostr · 452 kB"),
        ("container.png", "Container", "Compose a container image", "nostr · 11.4 kB"),
    ]
    out = ""
    for icon_name, title, sub, meta in rows:
        out += f"""
        <div class="row row-between">
          {app_icon(icon_name, 52)}
          <div style="flex:1;margin:0 10px 0 12px;min-width:0">
            <div class="title" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis">{title}</div>
            <div class="meta" style="margin-top:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">{sub}</div>
            <div class="caption" style="margin-top:2px;letter-spacing:0">{meta}</div>
          </div>
          <div class="plus">{vec('add', 18, ACCENT)}</div>
        </div>"""
    return page(xdc_header() + tabs("nostr", {"SAVED": 5, "STORE": 167, "TBONE": 0, "NOSTR": 30})
                + f"""
    <div class="searchwrap"><div class="searchbox"><span class="meta" style="color:{TEXT_MUTE}">filter apps</span></div></div>
    <div class="body">
      <div class="caption" style="padding:8px 14px;letter-spacing:0">30 apps · tap + to save</div>
      {out}
    </div>""", TAB_CSS + f"""
    .searchwrap {{ padding: 8px 14px; }}
    .searchbox {{ border: 1px solid {RULE}; border-radius: 4px; padding: 15px 16px; }}
    """)


# ── 6. add-as-tool dialog over the saved list ────────────────────────────────
def dialog():
    picks = ["sports_esports", "poll", "checklist", "edit_note", "calculate",
             "calendar_month", "timer", "map", "music_note", "photo_camera",
             "chat_bubble_outline", "extension", "casino", "account_balance_wallet", "wb_sunny",
             "quiz", "piano", "description", "star_border", "palette"]
    cells = ""
    for i, name in enumerate(picks):
        sel = i == 0
        border = ACCENT if sel else RULE
        bg = "rgba(127,222,107,0.04)" if sel else SURFACE_ALT
        tint = ACCENT if sel else TEXT_DIM
        cells += f'<div class="pick" style="border-color:{border};background:{bg}">{vec(name, 20, tint)}</div>'
    return page(xdc_header() + tabs("saved", {"SAVED": 5, "STORE": 167, "TBONE": 0, "NOSTR": 30})
                + f"""
    <div class="body">
      <div class="row row-between">{app_icon('xdc-chess.png', 48)}
        <div style="flex:1;margin-left:12px"><div class="title">XDC-Chess</div>
        <div class="caption" style="margin-top:2px;letter-spacing:0">63.5 kB · 2 hours ago</div></div>
        <div class="ellipsis">⋯</div></div>
      <div class="scrim"></div>
      <div class="dialog">
        <div class="dtitle">add as tool</div>
        <div class="meta" style="color:{TEXT_MUTE}">give the toolbox entry a name and an icon</div>
        <div class="field"><span class="title">chess club</span><span class="caret"></span></div>
        <div class="caption" style="margin:14px 0 8px 0">icon</div>
        <div class="picks">{cells}</div>
        <div class="actions"><span class="button" style="color:{TEXT_MUTE}">cancel</span>
          <span class="button" style="color:{ACCENT}">add tool</span></div>
      </div>
    </div>""", TAB_CSS + f"""
    .scrim {{ position:fixed; left:0; top:0; width:412px; height:915px; background: rgba(0,0,0,0.6); z-index: 2; }}
    .dialog {{ position:absolute; left:50%; top:52%; transform:translate(-50%,-50%); width:364px; z-index: 3;
              background:{SURFACE}; border-radius:28px; padding:24px; box-shadow:0 8px 40px rgba(0,0,0,.6); }}
    .dtitle {{ font-size:15px; line-height:22px; color:{TEXT}; margin-bottom:10px; }}
    .field {{ margin-top:10px; border:1px solid {ACCENT}; border-radius:4px; padding:15px 16px; display:flex; align-items:center; }}
    .caret {{ width:1.5px; height:18px; background:{ACCENT}; margin-left:2px; animation: none; }}
    .picks {{ display:grid; grid-template-columns:repeat(5, 44px); gap:6px; }}
    .pick {{ width:44px; height:44px; border:1px solid {RULE}; display:flex; align-items:center; justify-content:center; }}
    .actions {{ display:flex; justify-content:flex-end; gap:24px; margin-top:20px; }}
    """)


# ── 7. player chrome (real mini app underneath) ──────────────────────────────
def player():
    shot = b64_img(os.path.join(APPS, "../app-2048.png"))
    return f"""<!doctype html><html><head><meta charset="utf-8">
<style>{BASE_CSS.replace("__FONTS__", FONTS)}
.playerbar {{ display:flex; align-items:center; padding:10px 14px; }}
.pvtitle {{ font-size:15px; line-height:22px; color:{TEXT}; margin-left:8px; flex:1;
            white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }}
.webview {{ flex:1 1 auto; min-height:0; background:#fff; overflow:hidden; position:relative; }}
.screen {{ background: BG; }}
.webview img {{ position:absolute; inset:0; width:100%; height:100%; object-fit:cover; object-position: top center; display:block; }}
.navbar {{ height:44px; background:{BG}; border-top:1px solid {RULE}; display:flex; align-items:center;
           justify-content:space-around; }}
.nb {{ font-size:10px; letter-spacing:1px; color:{TEXT_MUTE}; text-align:center; }}
.nb.active {{ color:{ACCENT}; }}
.progress {{ height:2px; background:{ACCENT}; width:40%; }}
</style></head><body><div class="screen"><div class="statusbar">
  <div class="sb-left">9:41</div><div class="sb-right"><span class="bars"><i></i><i></i><i></i><i></i></span><span class="wifi">▮</span><span class="batt"></span></div>
</div>
<div class="playerbar">
  <span class="back">←</span>
  <div style="margin:0 8px 0 10px">{app_icon('arcanecircle-2048.png', 22, 4)}</div>
  <div class="pvtitle">2048</div>
  <div class="ellipsis">⋯</div>
</div>
<div class="divider"></div>
<div class="webview"><img src="{shot}"></div>
</div></body></html>"""


SCREENS = {
    "01-toolbox": toolbox(),
    "02-webxdc-saved": saved(),
    "03-webxdc-store": store(),
    "04-webxdc-tbone": tbone(),
    "05-webxdc-nostr": nostr(),
    "06-add-as-tool": dialog(),
    "07-player": player(),
}


def main():
    os.makedirs(OUT, exist_ok=True)
    from playwright.sync_api import sync_playwright

    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        for name, html in SCREENS.items():
            path = os.path.join(OUT, name + ".html")
            pathlib.Path(path).write_text(html)
            page = browser.new_page(viewport={"width": 412, "height": 915}, device_scale_factor=2.5)
            page.goto("file://" + path)
            page.wait_for_timeout(350)
            page.screenshot(path=os.path.join(OUT, name + ".png"))
            page.close()
            print("rendered", name)
        browser.close()


if __name__ == "__main__":
    main()
