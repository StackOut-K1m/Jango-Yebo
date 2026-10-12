import { useContext } from 'react';
import { BusinessContext, type BusinessState } from '@/business/businessContext';

export function useBusiness(): BusinessState {
  const value = useContext(BusinessContext);
  if (!value) throw new Error('BusinessProvider 안에서만 쓸 수 있습니다.');
  return value;
}
