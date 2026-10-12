/* ─────────────────────────────────────────────────────────────────────────
 * 잔고예보 · 와이어프레임 보강본 v2 — Figma 배치 스크립트
 *
 * 쓰는 곳 : Figma MCP `use_figma` 도구 (fileKey = 6veSEZej4e6Bc0UY5RHnwx)
 * 언제    : Figma MCP 호출 한도가 풀린 뒤 (Starter 플랜은 월 20회)
 * 하는 일 : 기존 3개 섹션을 건드리지 않는 빈 자리에 새 섹션과 화면 자리를 만든다.
 *
 * ── 기존 섹션이 차지한 좌표 (2026-09-01 기준, get_metadata로 확인함) ──
 *   108:10342  화면구성   x  7500 ~ 14404   y -847 ~  9743
 *   147:3547   pc         x 14672 ~ 22872   y -847 ~ 10509
 *   147:647    mobile     x 23140 ~ 27340   y -847 ~  2878
 *
 *   → 새 섹션은 x 28140 부터 시작한다. mobile 오른쪽 끝(27340)에서 800px 띄운 자리라
 *     기존 어떤 노드와도 겹치지 않는다. y는 기존 섹션과 같은 -847로 맞춘다.
 *
 * ── 두 번에 나눠 실행할 것 ──
 *   STEP 1 : 섹션 + 그룹 제목 + 화면 자리(빈 프레임)만 만든다.
 *   STEP 2 : 각 자리에 실제 화면을 넣는다.
 *            → 이 파일 맨 아래 "화면 채우는 방법"을 볼 것.
 * ───────────────────────────────────────────────────────────────────────── */

/* ═══════════════════════ STEP 1 ═══════════════════════ */

const SECTION_X = 28140;
const SECTION_Y = -847;
const PAD = 100;      // 기존 섹션들과 같은 안쪽 여백
const GAP_X = 120;    // 화면 사이 가로 간격
const GAP_Y = 200;    // 그룹 사이 세로 간격
const CAP_H = 92;     // 캡션(제목+설명)이 차지하는 높이

/* 만들 화면 목록 — 이름은 HTML 작업본의 화면 캡션과 그대로 맞춰 둔다 */
const GROUPS = [
  {
    title: '시작하기',
    desc: '가입부터 첫 예보가 나올 때까지 · 화면구성도 A1',
    screens: [
      { name: '시작하기 · PC', w: 1180, h: 900, note: '로그인 → 가게 정보 → 자료 연결 → 첫 예보' },
      { name: '시작하기 · 모바일', w: 360, h: 780, note: '한 번에 한 단계씩' },
    ],
  },
  {
    title: '증빙과 신고자료',
    desc: '영수증 확인 → 거래에 붙이기 → 정산 맞춰 보기 → 신고자료 만들기 · 화면구성도 F1',
    screens: [
      { name: '증빙과 신고자료 · PC', w: 1180, h: 860, note: '탭 4개가 실제로 바뀜' },
    ],
  },
  {
    title: '알림',
    desc: '부족 예보 · 연결 끊김 · 새 지원금 · 화면구성도 J3',
    screens: [
      { name: '알림 센터 · PC', w: 1180, h: 720, note: '오른쪽 서랍으로 열림' },
    ],
  },
  {
    title: '화면 상태 규칙',
    desc: '불러오는 중 · 없을 때 · 잘못됐을 때 · 못 누를 때 · 다 됐을 때',
    screens: [
      { name: '공통 상태 견본 · PC', w: 1180, h: 1500, note: '모든 화면이 같은 모양을 씀' },
    ],
  },
  {
    title: '거래 · 모바일',
    desc: '매장에서 폰으로 거래를 보고 한 건씩 확인하기 · 화면구성도 D2 · D3',
    screens: [
      { name: '거래 목록 · 모바일', w: 360, h: 900, note: '표를 자르지 않고 카드로' },
      { name: '거래 확인 · 모바일', w: 360, h: 760, note: '확인하고 다음 건 (n/8)' },
      { name: '거래 확인 끝 · 모바일', w: 360, h: 700, note: '바뀐 예보로 이어 줌' },
    ],
  },
  {
    title: '더보기와 상태 · 모바일',
    desc: '설정으로 가는 길과, 자료가 없거나 끊겼을 때 · 화면구성도 J1',
    screens: [
      { name: '더보기 · 모바일', w: 360, h: 1100, note: '예보에 쓰이는 값을 여기서 바꿈' },
      { name: '자료를 못 읽을 때 · 모바일', w: 360, h: 720, note: '기준 시각을 항상 보여 줌' },
      { name: '자료가 아직 없을 때 · 모바일', w: 360, h: 720, note: '빈 화면 대신 다음 할 일' },
    ],
  },
];

/* ── 한글이 깨지지 않는 폰트를 고른다 ── */
const available = await figma.listAvailableFontsAsync();
const has = (fam) => available.some((f) => f.fontName.family === fam);
const FAMILY = has('Pretendard') ? 'Pretendard' : has('Noto Sans KR') ? 'Noto Sans KR' : 'Inter';
const styleOf = (want) => {
  const styles = available.filter((f) => f.fontName.family === FAMILY).map((f) => f.fontName.style);
  if (styles.includes(want)) return want;
  if (want === 'Bold' && styles.includes('SemiBold')) return 'SemiBold';
  if (want === 'Bold' && styles.includes('Semi Bold')) return 'Semi Bold';
  return styles.includes('Regular') ? 'Regular' : styles[0];
};
const F_BOLD = { family: FAMILY, style: styleOf('Bold') };
const F_REG = { family: FAMILY, style: styleOf('Regular') };
await figma.loadFontAsync(F_BOLD);
await figma.loadFontAsync(F_REG);

const INK = { r: 0.11, g: 0.114, b: 0.122 };
const MUTED = { r: 0.42, g: 0.44, b: 0.46 };
const LINE = { r: 0.874, g: 0.886, b: 0.902 };
const WHITE = { r: 1, g: 1, b: 1 };

const mkText = (chars, size, font, color) => {
  const t = figma.createText();
  t.fontName = font;
  t.characters = chars;
  t.fontSize = size;
  t.fills = [{ type: 'SOLID', color }];
  return t;
};

/* ── 전체 크기를 먼저 계산한다 ── */
let innerW = 0;
let innerH = 0;
const rows = [];
for (const g of GROUPS) {
  const rowW = g.screens.reduce((a, s) => a + s.w, 0) + GAP_X * (g.screens.length - 1);
  const rowH = Math.max(...g.screens.map((s) => s.h)) + CAP_H;
  rows.push({ w: rowW, h: rowH });
  innerW = Math.max(innerW, rowW);
  innerH += rowH + 56 /* 그룹 제목 */ + GAP_Y;
}
innerH -= GAP_Y;

/* ── 섹션과 바깥 프레임 ── */
const section = figma.createSection();
section.name = '보강 v2 — 누락 화면 (기존 섹션 수정 안 함)';
section.x = SECTION_X;
section.y = SECTION_Y;
section.resizeWithoutConstraints(innerW + PAD * 2, innerH + PAD * 2 + 120);

const outer = figma.createFrame();
outer.name = '보강 v2';
outer.resize(innerW, innerH + 120);
outer.x = PAD;
outer.y = PAD;
outer.fills = [];
outer.clipsContent = false;
section.appendChild(outer);

/* ── 문서 제목 ── */
const h1 = mkText('잔고예보 · 와이어프레임 보강본 v2', 34, F_BOLD, INK);
h1.x = 0; h1.y = 0;
outer.appendChild(h1);

const h1sub = mkText(
  '화면구성도(A1~J6)에는 있으나 와이어프레임이 없던 화면과, UX/UI 검토보고서 v2의 P0·P1 지적사항을 해결하는 화면만 새로 만들었습니다.\n' +
  '기존 pc(12) · mobile(8) 섹션은 그대로 둡니다. 기준 데이터는 온기식당 · 2026.08.26 09:00입니다.',
  15, F_REG, MUTED
);
h1sub.x = 0; h1sub.y = 48;
h1sub.textAutoResize = 'HEIGHT';
h1sub.resize(900, 48);
outer.appendChild(h1sub);

/* ── 그룹과 화면 자리 ── */
const created = [];
let cursorY = 120;

for (let gi = 0; gi < GROUPS.length; gi++) {
  const g = GROUPS[gi];

  const gt = mkText(g.title, 22, F_BOLD, INK);
  gt.x = 0; gt.y = cursorY;
  outer.appendChild(gt);

  const gd = mkText(g.desc, 13, F_REG, MUTED);
  gd.x = 0; gd.y = cursorY + 32;
  outer.appendChild(gd);

  let cursorX = 0;
  const rowTop = cursorY + 56;

  for (const s of g.screens) {
    const cap = mkText(s.name, 15, F_BOLD, INK);
    cap.x = cursorX; cap.y = rowTop;
    outer.appendChild(cap);

    const note = mkText(s.note, 12, F_REG, MUTED);
    note.x = cursorX; note.y = rowTop + 24;
    outer.appendChild(note);

    const slot = figma.createFrame();
    slot.name = s.name;
    slot.resize(s.w, s.h);
    slot.x = cursorX;
    slot.y = rowTop + CAP_H - 44;
    slot.fills = [{ type: 'SOLID', color: WHITE }];
    slot.strokes = [{ type: 'SOLID', color: LINE }];
    slot.strokeWeight = 1;
    slot.cornerRadius = 14;
    slot.clipsContent = true;
    slot.placeholder = true; // 아직 안 채웠다는 표시 — STEP 2에서 끈다
    outer.appendChild(slot);

    created.push({ id: slot.id, name: s.name, w: s.w, h: s.h });
    cursorX += s.w + GAP_X;
  }

  cursorY = rowTop + rows[gi].h - 44 + GAP_Y;
}

figma.currentPage.selection = [section];
figma.viewport.scrollAndZoomIntoView([section]);

return {
  createdNodeIds: [section.id, outer.id, ...created.map((c) => c.id)],
  sectionId: section.id,
  sectionName: section.name,
  position: { x: SECTION_X, y: SECTION_Y, w: Math.round(section.width), h: Math.round(section.height) },
  font: FAMILY,
  slots: created,
  next: 'STEP 2 — 각 slot 프레임 안에 화면을 넣고 slot.placeholder = false 로 바꾼다',
};

/* ═══════════════════════ 화면 채우는 방법 (STEP 2) ═══════════════════════
 *
 * 방법 A — HTML 그대로 가져오기 (권장, Figma MCP 호출을 쓰지 않음)
 *
 *   기존 pc / mobile 섹션의 레이어 이름을 보면 Aside · Main · Nav · Link ·
 *   Background · Overlay+Shadow · Paragraph+HorizontalBorder 처럼
 *   HTML 태그에서 그대로 딴 이름이다. 즉 지금 있는 와이어프레임도
 *   html.to.design 계열 플러그인으로 웹 화면을 가져와 만든 것이다.
 *   보강본도 같은 방법이 가장 빠르고 결과도 기존 화면과 결이 맞는다.
 *
 *   1. wireframe/v2/index.html 을 로컬 서버로 연다.
 *        python -m http.server 4173 -d wireframe/v2
 *        → http://localhost:4173
 *   2. Figma에서 html.to.design 플러그인을 열고 위 주소를 넣어 가져온다.
 *      화면 폭은 1700px로 잡으면 이 파일의 배치와 같게 나온다.
 *   3. 가져온 결과에서 .frame 에 해당하는 프레임만 골라
 *      STEP 1이 만든 같은 이름의 slot 안으로 옮긴다.
 *   4. slot.placeholder = false 로 바꾼다.
 *
 * 방법 B — use_figma로 직접 그리기
 *
 *   화면 하나가 노드 100~250개라 호출을 여러 번 나눠 써야 한다.
 *   Starter 플랜(월 20회)에서는 화면 두세 개가 한계다.
 *   급한 화면부터 순서대로 하려면 아래 순서를 권한다.
 *
 *     1) 시작하기 · PC            (P0-1)
 *     2) 증빙과 신고자료 · PC      (P0-2 · P0-3)
 *     3) 거래 목록 · 모바일        (P0-4)
 *     4) 공통 상태 견본 · PC       (P1-13)
 *
 *   각 호출은 slot 을 figma.getNodeByIdAsync(SLOT_ID) 로 가져와서
 *   그 안에 auto-layout 프레임을 쌓는 방식으로 쓴다.
 *   한 호출에 프레임 10개 안쪽으로 끊고, 끝날 때마다 만든 노드 ID를 return 한다.
 * ═══════════════════════════════════════════════════════════════════════ */
