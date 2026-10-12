import { Outlet } from 'react-router';
import { BusinessProvider } from '@/business/BusinessProvider';

/** 로그인한 뒤에만 사업장 목록을 불러온다. 라우트 트리에 Provider 를 끼우는 자리다. */
export function BusinessGate() {
  return (
    <BusinessProvider>
      <Outlet />
    </BusinessProvider>
  );
}
