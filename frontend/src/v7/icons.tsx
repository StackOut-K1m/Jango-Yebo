import type { CSSProperties } from 'react';

const paths: Record<string, React.ReactNode> = {
  arrow: <><path d="M5 12h14m-5-5 5 5-5 5" /></>,
  diagonal: <><path d="M6 18 18 6M6 6h12v12" /></>,
  chevron: <path d="m9 5 7 7-7 7" />,
  down: <path d="m6 9 6 6 6-6" />,
  close: <path d="m6 6 12 12M6 18 18 6" />,
  check: <path d="m5 12 4 4L19 6" />,
  plus: <path d="M12 5v14M5 12h14" />,
  bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-2 7-2 9h16c0-2-2-2-2-9M10 21h4" /></>,
  store: <><path d="M4 10v10h16V10M3 10l2-7h14l2 7M3 10c0 4 6 4 6 0 0 4 6 4 6 0 0 4 6 4 6 0M9 20v-6h6v6" /></>,
  wallet: <><rect x="3" y="5" width="18" height="15" rx="3" /><path d="M17 11h4v5h-4a2.5 2.5 0 0 1 0-5M4 5l13-3v3" /></>,
  chart: <><path d="M4 4v16h16M7 14l4-4 4 3 5-7" /></>,
  sparkle: <><path d="m12 3 2.3 6.7L21 12l-6.7 2.3L12 21l-2.3-6.7L3 12l6.7-2.3L12 3Z" /></>,
  shield: <><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z" /><path d="m8 12 3 3 5-6" /></>,
  receipt: <><path d="M5 3v18l3-2 4 2 4-2 3 2V3l-3 2-4-2-4 2-3-2ZM9 9h6m-6 4h6" /></>,
  camera: <><path d="M3 8.5A2 2 0 0 1 5 6.5h2l1.4-2h7.2L17 6.5h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" /><circle cx="12" cy="13" r="3.4" /></>,
  box: <><path d="m3 7 9-4 9 4v10l-9 4-9-4V7Zm0 0 9 4 9-4M12 11v10M7 5l10 4" /></>,
  card: <><rect x="3" y="5" width="18" height="14" rx="3" /><path d="M3 10h18M7 15h3" /></>,
  bag: <><path d="M5 7h14l2 14H3L5 7ZM8 8V6a4 4 0 0 1 8 0v2" /></>,
  repeat: <><path d="M4 8h15l-4-4M20 16H5l4 4M19 8a7 7 0 0 1 1 4M5 16a7 7 0 0 1-1-4" /></>,
  // 화살표 둘짜리 「동기화」였다. 한 바퀴를 도는 화살표 하나가 「처음으로 되돌린다」에
  // 가깝고, 새로고침·다시 불러오기로 쓰는 다른 자리와도 같은 뜻이 된다.
  refresh: <><path d="M20 12a8 8 0 1 1-2.34-5.66" /><path d="M17.7 2.9V6.3H14.3" /></>,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  calendar: <><rect x="3" y="5" width="18" height="16" rx="3" /><path d="M3 10h18M8 3v4m8-4v4" /></>,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v5m0-9v1" /></>,
  warning: <><path d="m12 3 10 18H2L12 3ZM12 9v5m0 3v1" /></>,
  fold: <><path d="M3 4h7v17l-7-3V4Zm11 0h7v14l-7 3V4Z" /></>,
  settings: <><path d="M4 7h16M4 17h16" /><circle cx="9" cy="7" r="3" /><circle cx="15" cy="17" r="3" /></>,
  link: <><path d="M9 15l6-6" /><path d="M11 6l.46-.54a5 5 0 0 1 7.07 7.07l-.53.47" /><path d="M13 18l-.4.53a5.07 5.07 0 0 1-7.13 0 4.97 4.97 0 0 1 0-7.07l.53-.46" /></>,
  sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></>,
  moon: <path d="M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5Z" />,
  unlink: <><path d="M17 22v-2M9 15l6-6M20 17h2M2 7h2M7 2v2" /><path d="M11 6l.46-.54a5 5 0 0 1 7.07 7.07l-.53.47" /><path d="M13 18l-.4.53a5.07 5.07 0 0 1-7.13 0 4.97 4.97 0 0 1 0-7.07l.53-.46" /></>,
};

export function Icon({ name, size = 20, style }: { name: string; size?: number; style?: CSSProperties }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={style}>{paths[name] || paths.sparkle}</svg>;
}
