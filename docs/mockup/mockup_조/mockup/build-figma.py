# -*- coding: utf-8 -*-
"""
mockup/index.html (SPA) → mockup/figma.html (정적 아트보드 시트)

    python mockup/build-figma.py

목업 화면을 고친 뒤에는 이 스크립트를 다시 돌린다. 손으로 figma.html을 고치면
목업과 어긋난다 — 검토보고서 D-01에서 겪은 문제와 같은 종류다.

Figma의 html.to.design 계열 플러그인은 '렌더된 DOM'을 그대로 가져온다.
따라서 SPA인 원본은 활성 화면 하나와 활성 탭 하나만 들어온다.
이 스크립트는 모든 화면 × 모든 탭을 동시에 펼친 단일 파일을 만든다.

  - CSS 전부 인라인 (file:// 로 열어도, 붙여넣기로 임포트해도 동작)
  - JS 없음 (거래 표·달력을 미리 렌더)
  - 오버레이(Drawer·Modal·Sheet)를 각각 아트보드로

화면 목록·조각 추출은 `_screens.py` 하나에 모여 있다. build-mobile.py 와 공유한다.
"""
import io, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _screens as S


def artboard(vid, key, panel, label, desc, feat, size='pc'):
    return """
<div class="screen">
  <div class="cap"><b>%s</b><small>%s</small><span class="feat">%s</span></div>
  <div class="frame %s">
    <div class="app">
      %s
      <main class="main">
        %s
        <div class="pane">%s</div>
        %s
      </main>
    </div>
  </div>
</div>""" % (label, desc, feat, size, S.aside_for(vid), S.TOPBAR,
             S.body_for(vid, key, panel), S.MO_TABS)


# 좁은 폭에서 같은 마크업이 어떻게 바뀌는지 보여 주는 아트보드
RESPONSIVE = [
    ('today',        'tablet', '대시보드 · 태블릿 1000px',  '사이드바가 아이콘만 남고 비대칭 2단이 접힌다'),
    ('evidence',     'tablet', '증빙 · 태블릿 1000px',      '목록+상세 2단 → 1단'),
    ('today',        'mo',     '대시보드 · 모바일 375px',   '예보 판·경보 띠·날짜 띠가 그대로 접힌다'),
    ('transactions', 'mo',     '거래 · 모바일 375px',       '7열 표를 자르지 않고 카드로 바꾼다'),
    ('forecast',     'mo',     '자금 흐름 · 모바일 375px',  '기준 시각과 재시도 버튼을 지우지 않는다'),
    ('finance',      'mo',     '대출 · 모바일 375px',       '4열 지표가 2열로'),
]

OVERLAYS = [
    ('거래 상세 Drawer', '판단에 필요한 것을 맨 위에. 아니에요를 눌렀을 때만 폼이 펼쳐진다', 'P0-05 · P1-01', 'pc',
     S.DRAWER.replace('class="drawer-host" id="drawerHost"', 'class="drawer-host ov open"')),
    ('한 번뿐인 거래 넣기 Modal', '되돌릴 수 없는 일이 아니므로 확인 창은 이것 하나뿐', 'P0-06', 'pc',
     S.MODAL.replace(' hidden>', '>').replace('class="modal-backdrop" id="modalBackdrop"', 'class="modal-backdrop ov"')),
    ('더보기 시트 · 모바일', '하단 탭 4개에 없는 화면으로 가는 길', '—', 'mo',
     S.SHEET.replace('class="sheet" id="moreSheet"', 'class="sheet ov open"')),
    ('화면 목록 시트', '13개 화면과 각 화면이 덮는 기능', '시연용', 'pc',
     S.DEMO.replace('class="sheet" id="demoSheet"', 'class="sheet ov open"')),
]

parts = []
groups = [
    ('시작하기', 'SC-01 · 로그인 전 화면', [b for b in S.BOARDS if b[0] == 'onboarding']),
    ('매일 보는 것', 'SC-02 · SC-03 · SC-04 · SC-05 · SC-08',
     [b for b in S.BOARDS if b[0] in ('today', 'forecast', 'transactions', 'recurring', 'calendar')]),
    ('대응', 'SC-06 · SC-09 · SC-10',
     [b for b in S.BOARDS if b[0] in ('scenario', 'reports', 'finance')]),
    ('서류와 자료', 'SC-07', [b for b in S.BOARDS if b[0] == 'evidence']),
    ('전체', '여러 가게 · 설정 · 알림',
     [b for b in S.BOARDS if b[0] in ('multi', 'settings', 'alerts')]),
]
for title, sub, boards in groups:
    parts.append('\n<section class="group"><div class="group-head"><h2>%s</h2><span>%s</span>'
                 '<span class="badge mute">%d개</span></div><div class="rail">' % (title, sub, len(boards)))
    for b in boards:
        parts.append(artboard(*b))
    parts.append('</div></section>')

parts.append('\n<section class="group"><div class="group-head"><h2>좁은 화면</h2>'
             '<span>같은 마크업이 폭에 따라 어떻게 바뀌는지</span>'
             '<span class="badge mute">%d개</span></div><div class="rail">' % len(RESPONSIVE))
for vid, size, label, desc in RESPONSIVE:
    key = panel = None
    for b in S.BOARDS:
        if b[0] == vid:
            key, panel = b[1], b[2]
            break
    parts.append(artboard(vid, key, panel, label, desc, '반응형', size))
parts.append('</div></section>')

parts.append('\n<section class="group"><div class="group-head"><h2>겹쳐 뜨는 화면</h2>'
             '<span>Drawer · Modal · Sheet</span><span class="badge mute">%d개</span></div><div class="rail">'
             % len(OVERLAYS))
for label, desc, feat, size, html in OVERLAYS:
    parts.append("""
<div class="screen">
  <div class="cap"><b>%s</b><small>%s</small><span class="feat">%s</span></div>
  <div class="frame %s overlay-frame">%s</div>
</div>""" % (label, desc, feat, size, html))
parts.append('</div></section>')

CANVAS_CSS = """
/* ── Figma 임포트용 캔버스. 아트보드를 늘어놓는 껍데기일 뿐이다. ── */
body{background:#e9ebee}
.canvas{padding:48px 40px 120px;max-width:none;margin:0}
.doc-head{margin-bottom:40px;max-width:900px}
.doc-head h1{font-size:32px;font-weight:800;letter-spacing:-.02em;margin:0 0 12px}
.doc-head p{margin:0;color:var(--muted);font-size:14px;line-height:1.75}
.doc-meta{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}
.chip{font-size:11px;font-weight:700;padding:5px 10px;border-radius:99px;background:#fff;border:1px solid var(--line)}

.group{margin-top:56px}
.group-head{display:flex;align-items:baseline;gap:12px;padding-bottom:12px;border-bottom:2px solid var(--ink);margin-bottom:24px}
.group-head h2{font-size:20px;margin:0;letter-spacing:-.01em}
.group-head span{font-size:12px;color:var(--muted)}
.group-head .badge{margin-left:auto}

.rail{display:flex;flex-wrap:wrap;gap:56px 40px;align-items:flex-start}
.screen{flex:0 0 auto}
.cap{margin-bottom:10px;max-width:560px}
.cap b{display:block;font-size:15px}
.cap small{display:block;font-size:12px;color:var(--muted);margin-top:2px;line-height:1.5}
.cap .feat{margin-top:6px}

/* 아트보드. 컨테이너 쿼리를 프레임에 걸어 자기 폭으로 반응형 규칙이 실제로 돈다. */
.frame{background:var(--surface);border:1px solid var(--line);overflow:hidden;
       container-type:inline-size;container-name:shell;box-shadow:0 1px 3px rgba(20,24,31,.08)}
.pc{width:1440px}
.tablet{width:1000px}
.mo{width:375px}
.frame .app{min-height:640px}
.frame .mo-nav.app-tabs{position:static}

/* 겹쳐 뜨는 화면은 프레임 안에 고정한다 */
.overlay-frame{min-height:640px;position:relative;background:var(--surface-2)}
.overlay-frame .ov{position:absolute !important;inset:0 !important;z-index:1}
.overlay-frame .drawer-host.ov{display:flex;justify-content:flex-end}
.overlay-frame .modal-backdrop.ov{display:grid;place-items:center}
.overlay-frame .sheet.ov{display:block}
"""

HTML = """<!doctype html>
<html lang="ko">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<meta name="description" content="잔고예보 목업 · Figma 임포트용 아트보드 시트" />
<title>잔고예보 목업 · Figma 임포트용</title>
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
  <h1>잔고예보 목업 · Figma 임포트용</h1>
  <p>
    <code>mockup/index.html</code>은 한 번에 한 화면만 렌더하는 SPA라, 플러그인으로 가져오면 활성 화면 하나와 활성 탭 하나만 들어옵니다.
    이 파일은 <b>모든 화면 × 모든 탭 × 겹쳐 뜨는 화면</b>을 동시에 펼쳐 놓은 정적 시트입니다.
    CSS를 전부 인라인했고 JS가 없어, URL로 가져오든 HTML을 붙여넣든 같은 결과가 나옵니다.
  </p>
  <div class="doc-meta">
    <span class="chip">아트보드 %(total)d개</span>
    <span class="chip">PC 1440 · 태블릿 1000 · 모바일 375</span>
    <span class="chip">모바일만 필요하면 mobile.html</span>
    <span class="chip">기능명세서 P0 13개 · P1 18개 전부</span>
    <span class="chip">기준 데이터 · 온기식당 · 2026.08.26 09:00</span>
  </div>
</header>
%(body)s

</div>
</body>
</html>
"""

total = len(S.BOARDS) + len(RESPONSIVE) + len(OVERLAYS)
out = HTML % {"font": S.FONT_LINK, "ds": S.CSS_SYSTEM, "st": S.CSS_SCREEN,
              "canvas": CANVAS_CSS, "total": total, "body": "".join(parts)}
io.open(os.path.join(S.ROOT, "mockup", "figma.html"), "w", encoding="utf-8", newline="\n").write(out)
print("figma.html 생성")
print("  아트보드 %d개 (화면·탭 %d · 반응형 %d · 오버레이 %d)"
      % (total, len(S.BOARDS), len(RESPONSIVE), len(OVERLAYS)))
print("  크기 %d KB" % round(len(out.encode("utf-8")) / 1024))
