import type { ReactNode } from 'react';
import { Icon } from './icons';
import './forms.css';

/**
 * v7 언어의 폼 껍데기. 로그인·회원가입·사업장 등록·계좌 연결이 함께 쓴다.
 *
 * 기존 `routes/AuthShell` 과 **내보내는 이름과 인자가 같다** — 쓰는 쪽은
 * import 경로만 바꾸면 되고 폼 로직은 하나도 안 바뀐다.
 * 원래 껍데기는 예비로 그대로 남겨 둔다.
 */
export function AuthShell({
  title,
  lede,
  onBack,
  children,
}: {
  title: string;
  lede: string;
  /** 있으면 브랜드 위에 뒤로가기를 단다 */
  onBack?: () => void;
  children: ReactNode;
}) {
  return (
    <div className="v7-auth">
      {onBack && (
        <button type="button" className="v7-auth__back" onClick={onBack}>
          <Icon name="arrow" size={16} style={{ transform: 'rotate(180deg)' }} />
          뒤로
        </button>
      )}
      <section className="v7-auth__lede v7-rise">
        <p className="v7-auth__brand">
          <span className="brand-symbol" aria-hidden="true">
            <i />
            <i />
          </span>
          잔고예보
          <span className="brand-en">JANGO YEBO</span>
        </p>
        <h1>{title}</h1>
        <p className="v7-auth__sub">{lede}</p>
        {/* 관측 원. 로그인 전에는 상태를 알 수 없으므로 색으로 아무것도 말하지 않는다. */}
        <div className="v7-auth__scope" aria-hidden="true">
          <span />
          <span />
          <span />
          <span className="sweep" />
        </div>
      </section>

      <section className="v7-auth__panel v7-rise" style={{ animationDelay: '80ms' }}>
        {children}
      </section>
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
