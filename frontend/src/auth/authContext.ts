import type { Me } from '@jangoyebo/core';
import { createContext } from 'react';

export interface AuthState {
  me: Me | null;
  /** 첫 확인이 끝나기 전. 이때 화면을 그리면 로그인 페이지가 깜빡인다 */
  loading: boolean;
  setMe: (me: Me | null) => void;
}

export const AuthContext = createContext<AuthState | null>(null);
