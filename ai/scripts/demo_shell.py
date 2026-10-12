"""시연 페이지의 공용 껍데기 — 증빙(20.4)과 매출 인쇄물(20.9)이 함께 쓴다.

CSS 와 상호작용 스크립트를 두 생성기에 복사하면 화면이 서로 갈라진다. 이 저장소에서 같은
것을 두 곳에 두었다가 조용히 어긋난 사례가 이미 셋 있다(콜백 규약 검사, 스텁 좌표, 콜백
픽스처). 여기 한 곳에 둔다.

표별 스타일(`.fields`·`.items`·`.rows`)은 생성기마다 다르므로 각자 `extra_css` 로 넘긴다.
"""

from __future__ import annotations

#: `.format()` 이 아니라 f-string 안에 넣으므로 중괄호를 두 번 쓴다.
SHELL_CSS = """
* { box-sizing: border-box; }
body { margin: 0; font: 15px/1.6 "Malgun Gothic", system-ui, sans-serif; color: #1a1a1a;
  background: #f6f7f9; }
header { padding: 20px 28px 14px; background: #fff; border-bottom: 1px solid #e4e6eb; }
h1 { margin: 0 0 4px; font-size: 20px; }
.sub { color: #666; font-size: 13px; }
.tabs { display: flex; gap: 6px; padding: 12px 28px 0; flex-wrap: wrap; }
.tab { padding: 8px 14px; border: 1px solid #d6d9df; background: #fff; border-radius: 8px 8px 0 0;
  cursor: pointer; font: inherit; font-size: 13px; border-bottom-color: transparent; }
.tab.on { background: #1f2937; color: #fff; border-color: #1f2937; }
.card { margin: 0 28px 28px; background: #fff; border: 1px solid #e4e6eb;
  border-radius: 0 10px 10px; padding: 20px; }
.meta { font-size: 13px; color: #555; margin-bottom: 14px; }
.shows { color: #b45309; }
.sid { color: #9aa0aa; font-family: ui-monospace, monospace; font-size: 12px; }
.split { display: grid; grid-template-columns: minmax(260px, 42%) 1fr; gap: 22px; }
figure { margin: 0; }
img { width: 100%; border: 1px solid #e4e6eb; border-radius: 6px; }
table { width: 100%; border-collapse: collapse; margin-bottom: 16px; font-size: 14px; }
th, td { padding: 6px 8px; border-bottom: 1px solid #eef0f3; text-align: left; }
.truth { color: #b91c1c; font-variant-numeric: tabular-nums; font-size: 13px; }
.th-truth, .truth { display: none; }
body.reveal .th-truth, body.reveal .truth { display: table-cell; }
.panels { display: flex; gap: 14px; flex-wrap: wrap; }
.panel { flex: 1 1 320px; border: 1px solid #e4e6eb; border-radius: 8px; padding: 14px;
  background: #fbfcfd; }
.panel.fixed { display: none; border-color: #86efac; background: #f0fdf4; }
body.fixed .panel.fixed { display: block; }
.gate { display: inline-block; padding: 3px 10px; border-radius: 999px; font-size: 12px;
  font-weight: 600; margin-bottom: 10px; }
.gate.need { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
.gate.fine { background: #f0fdf4; color: #15803d; border: 1px solid #bbf7d0; }
.reasons code { display: inline-block; background: #1f2937; color: #fff; padding: 2px 7px;
  border-radius: 5px; font-size: 11px; margin: 0 4px 4px 0; }
.reasons .none { color: #9aa0aa; font-size: 12px; }
.issues { margin: 10px 0; padding-left: 18px; font-size: 13px; color: #7c2d12; }
.checks { list-style: none; margin: 12px 0 0; padding: 0; font-size: 12px; }
.checks li { display: flex; justify-content: space-between; padding: 3px 0;
  border-top: 1px dashed #e8eaee; }
.checks b { font-weight: 600; }
.checks .ok b { color: #15803d; }
.checks .bad b { color: #b91c1c; }
.checks .na b { color: #9aa0aa; }
.bar { padding: 10px 28px; background: #fff; border-bottom: 1px solid #e4e6eb;
  display: flex; gap: 10px; align-items: center; font-size: 13px; }
.bar button { padding: 6px 12px; border: 1px solid #d6d9df; border-radius: 7px; background: #fff;
  font: inherit; font-size: 12px; cursor: pointer; }
footer { padding: 8px 28px 30px; color: #8a8f98; font-size: 12px; }
@media (max-width: 900px) { .split { grid-template-columns: 1fr; } }
"""

#: 탭 전환 + 「실제 값 보기」. `.apply` 단추는 증빙 쪽에만 있고 없으면 그냥 지나간다.
SHELL_JS = """
const cards = [...document.querySelectorAll('.card')];
document.querySelectorAll('.tab').forEach(tab => tab.addEventListener('click', () => {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('on', t === tab));
  const i = tab.dataset.i;
  cards.forEach(c => c.hidden = c.dataset.i !== i);
  document.body.classList.remove('fixed');
}));
document.getElementById('reveal').addEventListener('click', e => {
  const on = document.body.classList.toggle('reveal');
  e.target.textContent = on ? '실제 값 숨기기' : '실제 값 보기';
});
document.querySelectorAll('.apply').forEach(b => b.addEventListener('click', () => {
  document.body.classList.add('fixed');
  b.textContent = '검산을 다시 돌렸습니다 (비용 0)';
  b.disabled = true;
}));
"""
