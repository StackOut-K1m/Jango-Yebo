import { useContext } from 'react';
import { AuthContext, type AuthState } from '@/auth/authContext';

export function useAuth(): AuthState {
  const value = useContext(AuthContext);
  if (!value) throw new Error('AuthProvider 안에서만 쓸 수 있습니다.');
  return value;
}
