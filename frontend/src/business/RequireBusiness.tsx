import { Navigate, Outlet } from 'react-router';
import { useBusiness } from '@/business/useBusiness';

/** 사업장이 하나도 없으면 등록부터 시킨다. 가입 직후의 온보딩 경로다. */
export function RequireBusiness() {
  const { current, loading } = useBusiness();

  if (loading) return null;
  if (!current) return <Navigate to="/businesses/new" replace />;

  return <Outlet />;
}
