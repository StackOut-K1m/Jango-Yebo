# -*- coding: utf-8 -*-
"""
mockup/index.html 에서 화면 조각을 꺼내는 공용 모듈.

build-figma.py 와 build-mobile.py 가 이 모듈 하나를 함께 쓴다.
스크립트마다 화면 목록을 따로 두면 반드시 갈린다 — 검토보고서 D-01과 같은 종류의 문제다.
"""
import io, re, os

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
_read = lambda p: io.open(os.path.join(ROOT, p), encoding="utf-8").read()

SRC = _read("mockup/index.html")
CSS_SYSTEM = _read("mockup/design-system.css")
CSS_SCREEN = _read("mockup/styles.css")

FONT_LINK = ('<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/'
             'pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css" />')


def _between(s, start, end):
    i = s.index(start)
    j = s.index(end, i) + len(end)
    return s[i:j]


ASIDE = _between(SRC, '<aside class="aside" id="sidebar">', '</aside>')
TOPBAR = _between(SRC, '<header class="topbar">', '</header>')

_pane = _between(SRC, '<div class="pane">', '\n    </div>\n\n    <!-- 모바일 하단 탭 -->')
VIEWS = {}
for _chunk in _pane.split('<section class="view'):
    if 'id="view-' not in _chunk:
        continue
    _vid = re.search(r'id="view-([a-z]+)"', _chunk).group(1)
    _body = '<section class="view' + _chunk
    VIEWS[_vid] = _body[:_body.rindex('</section>') + len('</section>')]

DRAWER = _between(SRC, '<div class="drawer-host" id="drawerHost">', '</aside>\n</div>')
MODAL = _between(SRC, '<div class="modal-backdrop" id="modalBackdrop" hidden>', '</div>\n  </div>\n</div>')
SHEET = _between(SRC, '<div class="sheet" id="moreSheet">', '</div>\n  </div>\n</div>')
DEMO = _between(SRC, '<div class="sheet" id="demoSheet">', '</p>\n  </div>\n</div>')

MO_TABS = """<nav class="mo-nav app-tabs" aria-label="모바일 메뉴">
          <a href="#today" class="on"><i>⌂</i>홈</a><a href="#forecast"><i>⌁</i>흐름</a>
          <a href="#transactions"><i>≡</i>거래</a><a href="#"><i>⋯</i>더보기</a>
        </nav>"""

# ─────────────────────────────────── JS가 채우던 내용을 미리 렌더 ──
_TX = [
    ('08.26 09:12', '배달정산', '우아한형제들',      'in',  '+850,000원',   '배달 정산', 'need:86', '없음'),
    ('08.25 21:40', '카드매출입금', '신한카드',       'in',  '+1,230,000원', '카드 정산', 'done',    '정산표'),
    ('08.25 09:30', '서울우유', '서울우유 협동조합',  'out', '−42,350원',    '재료비',    'need:91', '1장'),
    ('08.24 16:22', '다이소성수', '다이소 성수점',    'out', '−18,900원',    '소모품',    'need:72', '없음'),
    ('08.24 11:05', '성수농산', '성수농산㈜',         'out', '−128,000원',   '재료비',    'need:88', '1장'),
    ('08.23 10:00', '임대료', '좋은건물 임대',        'out', '−1,500,000원', '임차료',    'fixed',   '계약서'),
    ('08.22 18:30', '쿠팡이츠정산', '쿠팡이츠',       'in',  '+312,500원',   '배달 정산', 'need:79', '없음'),
    ('08.22 08:15', '이마트에브리데이', '이마트 성수', 'out', '−64,200원',    '재료비',    'need:68', '없음'),
    ('08.21 14:02', '가스요금', '서울도시가스',        'out', '−87,400원',    '공과금',    'need:83', '1장'),
    ('08.20 19:55', '알바급여', '김ㅇㅇ',             'out', '−420,000원',   '인건비',    'need:74', '없음'),
]


def _status(c):
    if c == 'done':
        return '<span class="badge safe">확인함</span>'
    if c == 'fixed':
        return '<span class="badge safe">확인함 · 매달 반복</span>'
    return '<span class="badge caution">확인 필요 · 자동분류 %s%%</span>' % c.split(':')[1]


def _receipt(v):
    return ('<span class="badge mute">영수증 없음</span>' if v == '없음'
            else '<span class="badge safe">%s</span>' % v)


_rows = "".join(
    '\n  <button class="list-row tx-row"><span>%s</span>'
    '<span><b>%s</b><small>%s</small></span><span>%s</span>'
    '<span class="money">%s</span><span>%s</span><span>%s</span><span>%s</span></button>'
    % (t[0], t[1], t[2], '들어옴' if t[3] == 'in' else '나감', t[4], t[5],
       _status(t[6]), _receipt(t[7])) for t in _TX)

_cards = "".join(
    '\n  <button class="txcard tx-row">'
    '<span class="l1"><b>%s</b><span class="money">%s</span></span>'
    '<span class="l2"><span>%s</span><span aria-hidden="true">·</span><span>%s</span></span>'
    '<span class="l3">%s%s</span></button>'
    % (t[2], t[4], t[0], t[5], _status(t[6]), _receipt(t[7])) for t in _TX)

_DAY_EVENTS = {3: [('급여', 'risk'), ('배달 정산 밀림', '')], 5: [('밀린 배달 정산', '')],
               8: [('배달 정산', '')], 10: [('세금·4대보험', ''), ('대출 상환', '')],
               15: [('배달 정산', '')], 22: [('배달 정산', '')], 23: [('임차료', '')],
               25: [('공과금', ''), ('마통 이자', '')], 29: [('배달 정산', '')]}
_cal = "".join('<div class="calendar-day head">%s</div>' % d for d in ['일', '월', '화', '수', '목', '금', '토'])
_cal += "".join('<div class="calendar-day muted"><span class="daynum">8/%d</span></div>' % d for d in (30, 31))
for _d in range(1, 31):
    _ev = "".join('<span class="calendar-event %s">%s</span>' % (c, l) for l, c in _DAY_EVENTS.get(_d, []))
    _cal += ('<div class="calendar-day %s %s"><span class="daynum">%d</span>%s'
             '<button aria-label="9월 %d일 상세 보기"></button></div>'
             % ('risk' if _d in (3, 4) else '', 'selected' if _d == 3 else '', _d, _ev, _d))

VIEWS['transactions'] = VIEWS['transactions'].replace(
    '<div id="transactionRows"></div>', '<div id="transactionRows">%s</div>' % _rows)
VIEWS['transactions'] = VIEWS['transactions'].replace(
    '<div class="tx-cards" id="transactionCards"></div>',
    '<div class="tx-cards" id="transactionCards">%s</div>' % _cards)
VIEWS['calendar'] = VIEWS['calendar'].replace(
    '<div class="calendar" id="calendarGrid"></div>', '<div class="calendar" id="calendarGrid">%s</div>' % _cal)


# ───────────────────────────────────────────── 탭·단계 상태 만들기 ──
def set_panel(html, panel_id):
    """패널셋 안에서 panel_id 하나만 켠다. 탭 버튼과 카운트도 함께 맞춘다."""
    out = re.sub(r'class="tabpanel(?: on)?"', 'class="tabpanel"', html)
    out = out.replace('class="tabpanel" data-panel="%s"' % panel_id,
                      'class="tabpanel on" data-panel="%s"' % panel_id)
    out = re.sub(r'<button class="on" data-tab=', '<button data-tab=', out)
    out = out.replace('<button data-tab="%s">' % panel_id, '<button class="on" data-tab="%s">' % panel_id)
    out = re.sub(r'<em class="count on">', '<em class="count">', out)
    m = re.search(r'<button class="on" data-tab="%s">(.*?)</button>' % re.escape(panel_id), out, re.S)
    if m:
        out = out.replace(m.group(0), m.group(0).replace('<em class="count">', '<em class="count on">'))
    return out


def set_step(html, n):
    """온보딩 단계 표시."""
    out = set_panel(html, n)

    def fix(m):
        i = int(m.group(1))
        cls = 'step done' if i < int(n) else ('step on' if i == int(n) else 'step')
        no = '✓' if i < int(n) else str(i)
        return '<span class="%s" data-go="%d"><i class="no">%s</i>' % (cls, i, no)

    return re.sub(r'<span class="step(?: [a-z]+)?" data-go="(\d)"><i class="no">[^<]*</i>', fix, out)


NAV_OF = {'today': 'today', 'forecast': 'forecast', 'calendar': 'calendar', 'transactions': 'transactions',
          'recurring': 'recurring', 'evidence': 'evidence', 'scenario': 'scenario', 'finance': 'finance',
          'reports': 'reports', 'multi': 'multi', 'settings': 'settings', 'alerts': None, 'onboarding': None}


def aside_for(vid):
    a = ASIDE.replace(' class="on"', '')
    target = NAV_OF.get(vid)
    if target:
        a = a.replace('<a href="#%s" data-view="%s">' % (target, target),
                      '<a href="#%s" class="on" data-view="%s">' % (target, target))
    return a


def body_for(vid, key, panel):
    """화면 하나를 '활성' 상태로 만들어 돌려준다."""
    body = VIEWS[vid]
    if key == 'ob':
        body = set_step(body, panel)
    elif key:
        body = set_panel(body, panel)
    return body.replace('<section class="view" ', '<section class="view active" ', 1)


# ───────────────────────────────────────────── 화면 × 탭 목록 ──
# (뷰 id, 패널셋 키, 패널 id, 라벨, 설명, 기능)
BOARDS = [
    ('onboarding', 'ob', '1', 'SC-01 시작하기 ① 로그인',      '이메일·소셜 로그인, 인라인 오류',                 'P0-01'),
    ('onboarding', 'ob', '2', 'SC-01 시작하기 ② 가게 정보',    '필수 3개 먼저, 나머지는 접어 둠',                 'P0-01'),
    ('onboarding', 'ob', '3', 'SC-01 시작하기 ③ 자료 연결',    '1원 인증 대기·오류·남은 시간 + CSV 대안',          'P0-01'),
    ('onboarding', 'ob', '4', 'SC-01 시작하기 ④ 첫 예보',      '진행률과 단계별 상태',                            'P0-01'),
    ('today',      None, None, 'SC-02 대시보드',               '예보 판 · 경보 띠 · 날짜별 예보 띠 · 30일 목록',    'P0-08 · P1-08·09·11·17'),
    ('forecast',   'fc', 'f1', 'SC-03 자금 흐름 ① 잔액 곡선',  '7/14/30일 곡선, 공백 상세, 날짜별 표',            'P0-07 · P0-08'),
    ('forecast',   'fc', 'f2', 'SC-03 자금 흐름 ② 예보 정확도', '거래처별 오차와 적용 중인 보정',                  'P1-05'),
    ('forecast',   'fc', 'f3', 'SC-03 자금 흐름 ③ 반영된 것',  '명절 보정 · 원자재 · 계절성',                     'P1-04 · P1-06 · P1-07'),
    ('transactions', 'tx', 't1', 'SC-04 거래 ① 목록',          '좁은 화면에서는 표 대신 카드',                    'P0-02 · P0-05'),
    ('transactions', 'tx', 't2', 'SC-04 거래 ② 거래처 사전',   '적요 → 거래처·분류 매핑',                         'P1-01'),
    ('transactions', 'tx', 't3', 'SC-04 거래 ③ 자료 가져오기', '계좌 연결 상태 + CSV 열 맞추기',                  'P0-01'),
    ('recurring',  'rc', 'r1', 'SC-05 반복 ① 확인할 후보',     '다음 예정일·금액 범위·표본',                      'P0-03 · P0-04 · P0-05'),
    ('recurring',  'rc', 'r2', 'SC-05 반복 ② 확정된 규칙',     '주기·다음 예정·대출 연동',                        'P0-05'),
    ('recurring',  'rc', 'r3', 'SC-05 반복 ③ 예정 거래',       '실제 거래 매칭과 놓친 예정 거래',                 'P0-06 · P1-05'),
    ('scenario',   None, None, 'SC-06 시뮬레이션',             '조건 조합·전후 비교·원본 보존',                   'P0-09 · P0-13'),
    ('evidence',   'ev', 'e1', 'SC-07 증빙 ① 보관함',          'OCR 추출 결과·품목·거래 매칭 제안',               'P0-10 · P1-02 · P1-03'),
    ('evidence',   'ev', 'e2', 'SC-07 증빙 ② 거래에 붙이기',   '일괄 연결과 애매한 건 분리',                      'P0-10'),
    ('evidence',   'ev', 'e5', 'SC-07 증빙 ③ 품목 가계부',     '품목별 합계·단가 추이',                           'P1-03'),
    ('evidence',   'ev', 'e3', 'SC-07 증빙 ④ 정산 맞춰 보기',  '계산식 전개와 차이 원인 후보',                    'P0-11'),
    ('evidence',   'ev', 'e4', 'SC-07 증빙 ⑤ 신고자료 만들기', '누락 경고·항목 선택·지난 자료',                   'P0-12'),
    ('calendar',   None, None, 'SC-08 캘린더',                 '확정·범위·예측 구분과 세금 일정',                 'P1-18 · P1-04'),
    ('reports',    None, None, 'SC-09 브리핑',                 '주간 AI 브리핑 + 월별 지출·수익',                 'P1-17 · P1-19'),
    ('finance',    'fn', 'n1', 'SC-10 금융 ① 내 대출',         '일반 대출·마이너스통장, 상환일과 공백',           'P1-13'),
    ('finance',    'fn', 'n2', 'SC-10 금융 ② 신용등급',        'A~E 등급 눈금, 하락 전망, 상품 추천',             'P1-14'),
    ('finance',    'fn', 'n3', 'SC-10 금융 ③ 지원제도',        '자연어 검색, 조건 대조, 불명확 조건 표시',        'P1-15'),
    ('finance',    'fn', 'n4', 'SC-10 금융 ④ 서류와 문서',     '발급처 사전 + AI 문서 편집기',                    'P1-16'),
    ('multi',      None, None, '여러 가게',                    '통합 대시보드 + 매장 간 자금 융통',               'P1-12'),
    ('settings',   'st', 's1', '설정 ① 안전잔고',              '계산 방식 선택과 지금 상태',                      'P1-08'),
    ('settings',   'st', 's2', '설정 ② 준비금',                '오늘 모아둘 돈, 목표별 진행률',                   'P1-09'),
    ('settings',   'st', 's3', '설정 ③ 부가세',                '예수금·매입세액·써도 되는 돈 계산',               'P1-11'),
    ('settings',   'st', 's4', '설정 ④ 예보 방식',             '반영 요인 켜고 끄기, 원자재 비중',                'P1-06 · P1-07'),
    ('settings',   'st', 's5', '설정 ⑤ 가게와 계정',           '다사업장 전환, 파괴적 동작 구분',                 '—'),
    ('alerts',     None, None, '알림 센터',                    '유형별 알림과 다음 행동, 수신 설정',              'P0-08'),
]
