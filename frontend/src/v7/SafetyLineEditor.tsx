import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { formatManwon, putSafetyLine } from '@jangoyebo/core';
import { Icon } from './icons';

export function SafetyLineEditor({ businessId, amount, onClose, onSaved }: {
  businessId: number;
  amount: number | null;
  onClose: () => void;
  onSaved: (amount: number) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const queryClient = useQueryClient();
  const [value, setValue] = useState(amount === null ? '' : String(amount / 10_000));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => element?.close();
  }, []);

  async function save() {
    if (saving) return;
    const won = Math.round(Number(value) * 10_000);
    if (!/^\d+(\.\d{1,4})?$/.test(value) || !Number.isSafeInteger(won) || won <= 0) {
      setError('0원보다 큰 금액을 입력해 주세요. 소수점은 최대 네 자리까지 입력할 수 있습니다.');
      return;
    }
    setSaving(true);
    setError('');
    try {
      const result = await putSafetyLine(businessId, { amount: won, method: 'MANUAL' });
      if (!result.ready) throw new Error('안전선 기능이 아직 준비되지 않았습니다.');
      if (!result.ok) throw new Error(result.error.message);
      // 먼저 화면을 고정한 뒤 저장 캐시를 갱신한다. 예보는 사용자가 새로고침한다.
      onSaved(result.data.amount ?? won);
      await queryClient.cancelQueries({ queryKey: ['v7', 'safety-line', businessId] });
      queryClient.setQueryData(['v7', 'safety-line', businessId], result);
      void queryClient.invalidateQueries({ queryKey: ['v7', 'forecast', businessId], refetchType: 'none' });
      void queryClient.invalidateQueries({ queryKey: ['safetyLine'] });
      void queryClient.invalidateQueries({ queryKey: ['v7', 'alerts', businessId] });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '저장하지 못했습니다. 다시 시도해 주세요.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <dialog ref={dialog} className="safety-editor" aria-labelledby="safety-editor-title"
      onCancel={(event) => { event.preventDefault(); if (!saving) onClose(); }}>
      <div className="safety-editor-heading">
        <h2 id="safety-editor-title">우리 가게의 안전선</h2>
        <button type="button" className="dashboard-control" aria-label="안전선 수정 닫기" disabled={saving} onClick={onClose}><Icon name="close" size={20} /></button>
      </div>
      <p id="safety-editor-hint">갑작스러운 지출에 대비해 남겨 둘 최소 잔액이에요.</p>
      <form onSubmit={(event) => { event.preventDefault(); void save(); }} noValidate>
        <label htmlFor="safety-editor-amount">우리 가게 안전선</label>
        <div className="safety-editor-input">
          <input id="safety-editor-amount" type="text" inputMode="decimal" autoFocus
            value={value} disabled={saving} aria-invalid={!!error}
            aria-describedby={`safety-editor-hint${error ? ' safety-editor-error' : ''}`}
            onChange={(event) => { setValue(event.target.value); setError(''); }} />
          <span>만원</span>
        </div>
        {/* 고르는 게 아니라 더한다 — 여러 번 눌러 쌓는다. 그래서 aria-pressed 를 쓰지 않는다. */}
        <div className="safety-editor-presets" aria-label="안전선 금액 더하기">
          {/* 단위는 만원. 100 = 100만원, 10000 = 1억원 */}
          {[100, 1000, 10000].map((preset) => (
            <button type="button" className="dashboard-control" key={preset} disabled={saving}
              onClick={() => { setValue(String((Number(value) || 0) + preset)); setError(''); }}>
              + {formatManwon(preset * 10_000)}
            </button>
          ))}
          <button type="button" className="dashboard-control" disabled={saving}
            onClick={() => { setValue(''); setError(''); }}>비우기</button>
        </div>
        <p>저장 후 메인의 ‘예보 새로고침’을 누르면 그래프와 날짜별 날씨에 반영됩니다.</p>
        {error && <p id="safety-editor-error" className="dashboard-error" role="alert">{error}</p>}
        <button type="submit" className="safety-editor-save" disabled={saving}>{saving ? '저장 중…' : '안전선 저장'}</button>
      </form>
    </dialog>
  );
}
