/**
 * 상단 바가 보여 주는 메뉴 지도. 1차 바(묶음 이름)와 2차 바(형제 화면)가 <b>같은 목록</b>을
 * 읽는다 — 두 벌로 갈라지면 눌러서 들어간 화면이 2차 바에 없는 일이 생긴다.
 *
 * 메인(`/`)은 로고가 맡는다. 정산 대사(14.1)는 보류라 메뉴에 두지 않는다 — 경로는 살아 있다.
 * 계좌는 마이페이지 안으로 옮겼다.
 */
export interface NavItem {
  to: string;
  label: string;
  /** 메인 지갑 카드와 같은 그림을 쓴다 — 같은 기능이 곳마다 다른 그림이면 다른 것으로 읽힌다. */
  icon: string;
}
export interface NavGroup {
  label: string;
  items: NavItem[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    label: '자금 예보',
    items: [
      { to: '/gaps', label: '정산 공백', icon: 'warning' },
      { to: '/safety', label: '자금 안전', icon: 'shield' },
      { to: '/scenarios', label: '시나리오', icon: 'sparkle' },
    ],
  },
  {
    label: '거래 관리',
    items: [
      { to: '/transactions', label: '거래', icon: 'card' },
      { to: '/patterns', label: '반복·예정', icon: 'repeat' },
      { to: '/evidences', label: '증빙', icon: 'receipt' },
    ],
  },
  {
    // 신고 흐름 — 검토 상태를 매기고(SC-07-7) 초안을 만든다(SC-07-8)
    label: '세무 신고',
    items: [
      { to: '/tax/review', label: '신고자료 검토', icon: 'check' },
      { to: '/tax/drafts', label: '신고서 초안', icon: 'fold' },
    ],
  },
  {
    // 화면구성도 LNB 「대출·지원」
    label: '대출·지원',
    items: [
      { to: '/loans', label: '대출·신용', icon: 'wallet' },
      { to: '/support-programs', label: '지원 제도', icon: 'bag' },
    ],
  },
];

/**
 * 이 항목이 지금 경로를 품는가.
 *
 * 접두사로 재야 `/evidences/3` 같은 상세 화면도 제 항목에 붙는다. 다만 `/` 는 모든 경로의
 * 접두사라 그대로 재면 <b>어느 화면에서나 맞는다</b> — 지금 메뉴에 `/` 는 없지만, 나중에
 * 넣더라도 조용히 깨지지 않도록 정확히 같을 때로 가르는 길을 남겨 둔다.
 */
export const holds = (to: string, pathname: string) =>
  to === '/' ? pathname === '/' : pathname.startsWith(to);

/** 지금 화면이 속한 묶음. 메뉴에 없는 화면(메인·마이페이지·정산 대사 등)에서는 `undefined` 다. */
export const groupFor = (pathname: string) =>
  NAV_GROUPS.find((group) => group.items.some((item) => holds(item.to, pathname)));
