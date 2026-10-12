import type { Business } from '@jangoyebo/core';
import { createContext } from 'react';

export interface BusinessState {
  businesses: Business[];
  /** 지금 보고 있는 사업장. 사업장이 없으면 null */
  current: Business | null;
  loading: boolean;
  select: (businessId: number) => void;
  /** 등록·수정 후 목록을 다시 불러온다 */
  refresh: () => Promise<void>;
}

export const BusinessContext = createContext<BusinessState | null>(null);

/** 마지막으로 보던 사업장. 다사업장이라 새로고침 때 되돌려 준다. */
export const LAST_BUSINESS_KEY = 'jangoyebo.lastBusinessId';
