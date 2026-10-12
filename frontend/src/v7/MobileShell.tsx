import { Outlet } from 'react-router';
import { MobileTabs } from './MobileTabs';

/**
 * 로그인·사업장 게이트 안쪽 화면 전부를 덮는 껍데기. 하단 탭은 화면마다 다는 것이
 * 아니라 여기 한 곳에 단다 — 화면이 늘어도 고칠 곳이 없다.
 *
 * `MobileTabs` 가 좁을 때만 보이도록 CSS 가 숨긴다. 폭으로 컴포넌트를 갈라 끼우는 것은
 * 정보 구조가 달라지는 화면에서만 하고, 있고 없고로 끝나는 것은 CSS 에 맡긴다.
 */
export function MobileShell() {
  return (
    <>
      <Outlet />
      <MobileTabs />
    </>
  );
}
