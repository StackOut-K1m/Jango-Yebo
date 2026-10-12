import type { ReactNode } from 'react';
import './EmptyState.css';

/**
 * 빈 상태 (화면구성도 6.4).
 *
 * 데이터가 없는 것은 오류가 아니다. API 레이어의 NOT_READY 도 여기로 온다 —
 * throw 하지 않고 정상 빈 상태로 그린다.
 */

interface Props {
  title: string;
  /** 무엇을 하면 채워지는지. 없으면 생략한다. */
  hint?: string;
  /** [동기화] [CSV 업로드] 같은 다음 행동 */
  action?: ReactNode;
}

export function EmptyState({ title, hint, action }: Props) {
  return (
    <div className="empty">
      <p className="empty__title">{title}</p>
      {hint && <p className="empty__hint">{hint}</p>}
      {action && <div className="empty__action">{action}</div>}
    </div>
  );
}

/** 백엔드가 아직 열지 않은 기능. 오류 화면이 아니다. */
export function NotReady({ what }: { what: string }) {
  return <EmptyState title={`${what}는 준비 중입니다`} hint="기능이 열리면 바로 표시됩니다." />;
}
