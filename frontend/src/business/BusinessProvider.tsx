import { listBusinesses } from '@jangoyebo/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useState, type ReactNode } from 'react';
import { BusinessContext, LAST_BUSINESS_KEY } from '@/business/businessContext';

/**
 * 사업장 목록과 현재 선택. 다사업장이 전제다(도메인 규칙 7).
 *
 * 로그인한 뒤에만 의미가 있으므로 RequireAuth 안쪽에 둔다.
 * 서버 상태는 TanStack Query 가 들고, 여기서는 "어느 사업장을 보는가"만 다룬다.
 */
export function BusinessProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [picked, setPicked] = useState<number | null>(null);

  const { data: businesses = [], isPending } = useQuery({
    queryKey: ['businesses'],
    queryFn: async () => {
      const res = await listBusinesses();
      // NOT_READY 도 오류가 아니라 빈 목록이다(화면구성도 6.4).
      return res.ready && res.ok ? res.data.items : [];
    },
  });

  const select = useCallback((businessId: number) => {
    setPicked(businessId);
    localStorage.setItem(LAST_BUSINESS_KEY, String(businessId));
  }, []);

  const refresh = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: ['businesses'] });
  }, [queryClient]);

  // 고른 것이 없으면 마지막으로 보던 사업장, 그것도 없으면 첫 번째.
  // 렌더 중에 계산하므로 effect 에서 setState 할 일이 없다.
  const saved = Number(localStorage.getItem(LAST_BUSINESS_KEY));
  const currentId =
    (picked && businesses.some((b) => b.businessId === picked) && picked) ||
    (saved && businesses.some((b) => b.businessId === saved) && saved) ||
    businesses[0]?.businessId ||
    null;

  const current = businesses.find((b) => b.businessId === currentId) ?? null;

  return (
    <BusinessContext.Provider
      value={{ businesses, current, loading: isPending, select, refresh }}
    >
      {children}
    </BusinessContext.Provider>
  );
}
