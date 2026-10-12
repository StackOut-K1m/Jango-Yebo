import type { ReactNode } from 'react';
import type { RiskLevel } from '@jangoyebo/core';
import '../styles/sky.css';

interface Props {
  /** 위험 등급이 곧 배경색이다. 모르면 SAFE로 두지 말고 안전선 미설정 안내를 띄운다(6.1). */
  risk: RiskLevel;
  /** 예보 화면만 하늘을 크게 쓴다. 나머지는 얇게 쓰고 답 한 줄만 얹는다. */
  thin?: boolean;
  children: ReactNode;
}

export function Sky({ risk, thin = false, children }: Props) {
  return (
    <section className={`sky${thin ? ' sky--thin' : ''}`} data-risk={risk}>
      {children}
    </section>
  );
}
