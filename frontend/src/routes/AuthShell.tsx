import type { ReactNode } from 'react';
import { Sky } from '@/components/Sky';
import '@/styles/auth.css';

/** 로그인·회원가입 공통 틀. 하늘 아래 폼 하나만 둔다. */
export function AuthShell({
  title,
  lede,
  children,
}: {
  title: string;
  lede: string;
  children: ReactNode;
}) {
  return (
    <div className="auth-page">
      {/* 로그인 전에는 위험 등급을 알 수 없다. 하늘은 기본값으로 두고 상태를 말하지 않는다. */}
      <Sky risk="SAFE" thin>
        <p className="auth-brand">잔고예보</p>
        <h1 className="auth-title">{title}</h1>
        <p className="auth-lede">{lede}</p>
      </Sky>
      <div className="auth-body">{children}</div>
    </div>
  );
}

export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="auth-field">
      <span className="auth-label">{label}</span>
      {children}
      {hint && !error && <span className="auth-hint">{hint}</span>}
      {/* 색만으로 오류를 말하지 않는다 — 아이콘과 문장을 함께 둔다(화면구성도 6.1·6.5) */}
      {error && (
        <span className="auth-error" role="alert">
          <span aria-hidden="true">⚠</span> {error}
        </span>
      )}
    </label>
  );
}

export function FormError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <p className="auth-form-error" role="alert">
      <span aria-hidden="true">⚠</span> {message}
    </p>
  );
}
