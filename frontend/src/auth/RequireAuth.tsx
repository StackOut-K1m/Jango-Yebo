import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from '@/auth/useAuth';

/** 로그인하지 않았으면 로그인 화면으로 보낸다. 원래 가려던 곳은 기억해 뒀다가 로그인 후 되돌린다. */
export function RequireAuth() {
  const { me, loading } = useAuth();
  const location = useLocation();

  // 세션 확인이 끝나기 전에 판단하면 로그인 화면이 한 번 깜빡인다.
  if (loading) return null;
  if (!me) return <Navigate to="/login" replace state={{ from: location.pathname }} />;

  return <Outlet />;
}
