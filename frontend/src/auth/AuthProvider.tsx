import { getMe, type Me } from '@jangoyebo/core';
import { useEffect, useState, type ReactNode } from 'react';
import { AuthContext } from '@/auth/authContext';

/**
 * 로그인 상태. Access Token은 core의 토큰 저장소(메모리)에 있고 여기서는 다루지 않는다.
 *
 * 새로고침하면 메모리 토큰이 사라지므로, 시작할 때 getMe를 한 번 부른다.
 * 실패하면 client.ts가 Refresh Token 쿠키로 갱신을 시도하고, 그것도 안 되면 로그아웃 상태다.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let alive = true;
    getMe()
      .then((res) => {
        if (!alive) return;
        setMe(res.ready && res.ok ? res.data : null);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  return <AuthContext.Provider value={{ me, loading, setMe }}>{children}</AuthContext.Provider>;
}
