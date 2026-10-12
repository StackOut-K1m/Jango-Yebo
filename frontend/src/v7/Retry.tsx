import { Icon } from './icons';
import './retry.css';

/** 조회 오류 안내 옆의 「다시 불러오기」. 다시 묻는 동안은 막아 연타로 요청이 겹치지 않게 한다 */
export function RetryButton({ onRetry, busy }: { onRetry: () => void; busy: boolean }) {
  return (
    <button type="button" className="retry-button" onClick={onRetry} disabled={busy} aria-busy={busy}>
      <Icon name="refresh" size={14} /> {busy ? '불러오는 중…' : '다시 불러오기'}
    </button>
  );
}
