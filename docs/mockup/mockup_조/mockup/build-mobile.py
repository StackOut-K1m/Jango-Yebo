# -*- coding: utf-8 -*-
"""
mockup/index.html → mockup/mobile.html

    python mockup/build-mobile.py

목업은 반응형이라 창을 좁히면 모바일이 된다. 다만 그렇게 보면
**한 번에 한 화면**만 볼 수 있고, 화면마다 폭을 다시 맞춰야 한다.

이 파일은 33개 화면·탭을 전부 375px 프레임에 담아 한 장에 늘어놓는다.
모바일 전용 마크업을 따로 쓰지 않는다 — 목업과 같은 HTML이 좁은 폭에서
어떻게 접히는지를 그대로 보여 준다.

  - CSS 전부 인라인, JS 없음 (file:// 로 열어도, Figma에 붙여넣어도 동작)
  - 겹쳐 뜨는 화면(하단 시트·서랍·모달)은 모바일 형태로 따로
"""
import io, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _screens as S

W = 375  # 아이폰 기준 폭


def frame(vid, key, panel, label, desc, feat):
    return """
<div class="screen">
  <div class="cap"><b>%s</b><small>%s</small><span class="feat">%s</span></div>
  <div class="frame mo">
    <div class="app">
      %s
      <main class="main">
        %s
        <div class="pane">%s</div>
        %s
      </main>
    </div>
  </div>
</div>""" % (label, desc, feat, S.aside_for(vid), S.TOPBAR,
             S.body_for(vid, key, panel), S.MO_TABS)


parts = []
groups = [
    ('시작하기', 'SC-01 · 로그인 전', [b for b in S.BOARDS if b[0] == 'onboarding']),
    ('매일 보는 것', 'SC-02 · SC-03 · SC-04 · SC-05 · SC-08',
     [b for b in S.BOARDS if b[0] in ('today', 'forecast', 'transactions', 'recurring', 'calendar')]),
    ('서류와 자료', 'SC-07', [b for b in S.BOARDS if b[0] == 'evidence']),
    ('대응', 'SC-06 · SC-09 · SC-10',
     [b for b in S.BOARDS if b[0] in ('scenario', 'reports', 'finance')]),
    ('전체', '여러 가게 · 설정 · 알림',
     [b for b in S.BOARDS if b[0] in ('multi', 'settings', 'alerts')]),
]
for title, sub, boards in groups:
    parts.append('\n<section class="group"><div class="group-head"><h2>%s</h2><span>%s</span>'
                 '<span class="badge mute">%d개</span></div><div class="rail">' % (title, sub, len(boards)))
    for b in boards:
        parts.append(frame(*b))
    parts.append('</div></section>')

# 겹쳐 뜨는 화면 — 모바일에서는 전폭 서랍·하단 시트가 된다
OVERLAYS = [
    ('거래 상세 · 전폭 서랍', '좁은 화면에서 서랍은 폭 전체를 덮는다', 'P0-05 · P1-01',
     S.DRAWER.replace('class="drawer-host" id="drawerHost"', 'class="drawer-host ov open"')),
    ('한 번뿐인 거래 넣기 · 하단 시트', '되돌릴 수 없는 일이 아니므로 확인 창은 이것 하나뿐', 'P0-06',
     S.MODAL.replace(' hidden>', '>').replace('class="modal-backdrop" id="modalBackdrop"',
                                              'class="modal-backdrop ov"')),
    ('더보기 시트', '하단 탭 4개에 없는 화면으로 가는 길', '—',
     S.SHEET.replace('class="sheet" id="moreSheet"', 'class="sheet ov open"')),
    ('화면 목록 시트', '33개 화면과 각 화면이 덮는 기능', '시연용',
     S.DEMO.replace('class="sheet" id="demoSheet"', 'class="sheet ov open"')),
]
parts.append('\n<section class="group"><div class="group-head"><h2>겹쳐 뜨는 화면</h2>'
             '<span>서랍 · 모달 · 하단 시트</span><span class="badge mute">4개</span></div><div class="rail">')
for label, desc, feat, html in OVERLAYS:
    parts.append("""
<div class="screen">
  <div class="cap"><b>%s</b><small>%s</small><span class="feat">%s</span></div>
  <div class="frame mo overlay-frame">%s</div>
</div>""" % (label, desc, feat, html))
parts.append('</div></section>')

CANVAS = """
/* ── 모바일 목업 캔버스. 아트보드를 늘어놓는 껍데기일 뿐이다. ── */
body{background:#e9ebee}
.canvas{padding:48px 40px 120px;max-width:none;margin:0}
.doc-head{margin-bottom:40px;max-width:840px}
.doc-head h1{font-size:32px;font-weight:800;letter-spacing:-.02em;margin:0 0 12px}
.doc-head p{margin:0;color:var(--muted);font-size:14px;line-height:1.75}
.doc-meta{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}
.chip{font-size:11px;font-weight:700;padding:5px 10px;border-radius:99px;background:#fff;border:1px solid var(--line)}

.group{margin-top:56px}
.group-head{display:flex;align-items:baseline;gap:12px;padding-bottom:12px;border-bottom:2px solid var(--ink);margin-bottom:24px}
.group-head h2{font-size:20px;margin:0;letter-spacing:-.01em}
.group-head span{font-size:12px;color:var(--muted)}
.group-head .badge{margin-left:auto}

.rail{display:flex;flex-wrap:wrap;gap:48px 32px;align-items:flex-start}
.screen{flex:0 0 auto;width:%(w)dpx}
.cap{margin-bottom:10px}
.cap b{display:block;font-size:14px;line-height:1.4}
.cap small{display:block;font-size:12px;color:var(--muted);margin-top:2px;line-height:1.5}
.cap .feat{margin-top:6px}

/* 아트보드. 컨테이너 쿼리를 프레임에 걸어 375px 규칙이 실제로 돈다. */
.frame{background:var(--surface);border:1px solid var(--line);border-radius:20px;overflow:hidden;
       container-type:inline-size;container-name:shell;box-shadow:0 2px 10px rgba(20,24,31,.10)}
.mo{width:%(w)dpx}
.frame .app{min-height:812px}

/* 겹쳐 뜨는 화면은 프레임 안에 고정한다 */
.overlay-frame{min-height:812px;position:relative;background:var(--surface-2)}
.overlay-frame .ov{position:absolute !important;inset:0 !important;z-index:1}
.overlay-frame .drawer-host.ov{display:flex;justify-content:flex-end}
.overlay-frame .modal-backdrop.ov{display:grid;place-items:end center}
.overlay-frame .sheet.ov{display:block}
""" % {"w": W}

HTML = """<!doctype html>
<html lang="ko">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<meta name="description" content="잔고예보 목업 · 모바일 375px 전 화면" />
<title>잔고예보 목업 · 모바일</title>
%(font)s
<style>
/* ═══════════════ design-system.css (인라인) ═══════════════ */
%(ds)s
/* ═══════════════ styles.css (인라인) ═══════════════ */
%(st)s
%(canvas)s
</style>
</head>
<body>
<div class="canvas">

<header class="doc-head">
  <h1>잔고예보 목업 · 모바일</h1>
  <p>
    목업은 반응형이라 창을 좁히면 모바일이 됩니다. 다만 그렇게 보면 <b>한 번에 한 화면</b>만 볼 수 있습니다.
    이 파일은 화면·탭 %(n)d개와 겹쳐 뜨는 화면 4개를 전부 <b>375px 프레임</b>에 담아 한 장에 늘어놓은 것입니다.
    모바일 전용 마크업을 따로 쓰지 않았습니다 — <b>목업과 같은 HTML</b>이 좁은 폭에서 어떻게 접히는지를 그대로 보여 줍니다.
  </p>
  <div class="doc-meta">
    <span class="chip">아트보드 %(total)d개 · 폭 375px</span>
    <span class="chip">사이드바 → 하단 탭 4개</span>
    <span class="chip">표 → 카드 · 정보를 숨기지 않음</span>
    <span class="chip">터치 영역 44px</span>
    <span class="chip">기준 데이터 · 온기식당 · 2026.08.26 09:00</span>
  </div>
</header>
%(body)s

</div>
</body>
</html>
"""

n = len(S.BOARDS)
out = HTML % {"font": S.FONT_LINK, "ds": S.CSS_SYSTEM, "st": S.CSS_SCREEN,
              "canvas": CANVAS, "n": n, "total": n + len(OVERLAYS),
              "body": "".join(parts)}
path = os.path.join(S.ROOT, "mockup", "mobile.html")
io.open(path, "w", encoding="utf-8", newline="\n").write(out)
print("mobile.html 생성")
print("  아트보드 %d개 (화면·탭 %d · 겹쳐 뜨는 화면 %d)" % (n + len(OVERLAYS), n, len(OVERLAYS)))
print("  크기 %d KB" % round(len(out.encode("utf-8")) / 1024))
