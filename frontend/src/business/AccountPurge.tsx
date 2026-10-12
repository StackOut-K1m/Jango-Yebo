import { purgeAccount, type Account } from '@jangoyebo/core';
import { useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';

/**
 * 계좌 삭제 (API 7.2 purge).
 *
 * 연동 해제와 **다른 동작**이라 버튼도 확인도 따로 둔다. 해제는 되돌릴 수 있어 한 줄
 * 되묻기로 충분하지만, 이쪽은 거래가 실제로 사라지고 되돌릴 수 없다.
 *
 * 그래서 셋을 요구한다 — 무엇이 사라지는지 읽게 하고, 별칭을 직접 치게 하고, 증빙은
 * 따로 켜게 한다. 별칭을 묻는 이유는 서버가 그것으로 확인하기 때문이다(계좌번호는
 * 마스킹이 앞뒤 3자리씩만 남겨 무엇을 치라는 건지 모호하다).
 *
 * <h2>버튼과 패널을 나눠 둔 이유</h2>
 *
 * 버튼은 계좌 줄의 **버튼 칸**(`AccountSync` 의 children)에 들어가야 동기화·해제와 나란히
 * 서고, 패널은 **줄 전체**를 차지해야 한다. 한 컴포넌트가 둘 다 그리면 패널이 버튼 칸
 * 안에 갇혀 `flex-basis: 100%` 가 `li` 에 닿지 않는다 — 실제로 계좌번호가 두 줄로 접히고
 * 잔액이 패널 왼쪽에 끼었다. 그래서 여는 상태는 부르는 쪽이 들고, 여기서는 두 조각을
 * 각자 내놓는다.
 */

/**
 * 계좌 줄의 버튼 칸에 들어가는 조각.
 *
 * **연결된 계좌에서는 눌리지 않는다.** 서버가 409 ACCOUNT_STATE_CONFLICT 로 막는다 —
 * 자동 반영 워커가 `auto_execute_planned AND CONNECTED` 로 계좌를 골라 금융망에 실제로
 * 출금을 보내므로, 연결된 채로 지우면 그 창이 열린다. 해제가 그 설정을 함께 끄기 때문에
 * 해제를 앞 단계로 두면 경합이 구조적으로 닫힌다.
 *
 * **숨기지 않고 비활성으로 둔다.** 버튼이 사라지면 "삭제가 없네"로 읽히는데, 비활성에
 * 이유를 붙이면 다음에 무엇을 할지 알려준다.
 */
export function AccountPurgeButton({
  account,
  onOpen,
}: {
  account: Account;
  onOpen: () => void;
}) {
  const deletable = account.status !== 'CONNECTED';

  // title 만 두면 접근성 트리에서 그 문장이 버튼 이름을 **밀어낸다** — 「계좌 삭제」가
  // 사라지고 「연동을 먼저 해제해 주세요.」만 읽힌다(브라우저 a11y 트리로 확인).
  // 이름에 둘을 함께 담아 무엇을 하는 버튼인지와 왜 못 누르는지를 같이 알린다.
  const reason = '연동을 먼저 해제해 주세요.';

  return (
    <button
      type="button"
      className="account-purge__open"
      onClick={onOpen}
      disabled={!deletable}
      title={deletable ? undefined : reason}
      aria-label={deletable ? undefined : `계좌 삭제 — ${reason}`}
    >
      계좌 삭제
    </button>
  );
}

/** 계좌 줄 아래로 펼쳐지는 확인 패널. `li` 의 직계 자식이라 한 줄을 통째로 쓴다. */
export function AccountPurgePanel({
  account,
  businessId,
  onClose,
}: {
  account: Account;
  businessId: number;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const inputId = useId();
  const checkboxId = useId();

  const [confirm, setConfirm] = useState('');
  const [purgeEvidence, setPurgeEvidence] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 타입은 optional 이지만 서버에서 NOT NULL 이고 등록 때 @NotBlank 다 — 실제로 비는 일이 없다.
  // 그래도 비면 버튼이 열리지 않는다: 확인할 값이 없는데 지우게 두는 쪽이 더 나쁘다.
  const alias = account.alias ?? '';
  // 서버가 별칭으로 확인한다. 앞뒤 공백은 서버도 흘리므로 여기서도 흘린다.
  const matches = alias.trim() !== '' && confirm.trim() === alias.trim();

  async function onPurge() {
    setBusy(true);
    setError(null);

    const res = await purgeAccount(account.accountId, {
      confirm: confirm.trim(),
      purgeOrphanEvidence: purgeEvidence,
    });

    setBusy(false);

    if (!res.ready) {
      setError('계좌 삭제 기능이 아직 준비되지 않았습니다.');
      return;
    }
    if (!res.ok) {
      setError(res.error.message);
      return;
    }

    onClose();
    // 계좌만 다시 읽으면 안 된다 — 거래와 예보가 같이 바뀌었다.
    // 예보는 서버가 재계산할 때까지 잠깐 비어 있을 수 있다(정상. 화면구성도 6.4).
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['accounts', businessId] }),
      queryClient.invalidateQueries({ queryKey: ['transactions'] }),
      queryClient.invalidateQueries({ queryKey: ['forecast'] }),
    ]);
  }

  return (
    <div className="account-purge" role="group" aria-label={`${alias} 계좌 삭제`}>
      {/* 색만으로 위험을 말하지 않는다(도메인 규칙 6) — 무엇이 사라지는지 글로 적는다 */}
      <p className="account-purge__warning">
        이 계좌의 <b>거래 내역·예정 거래·반복 패턴</b>이 함께 지워집니다.{' '}
        <b>되돌릴 수 없습니다.</b> 잠시 끊어 두려는 것이라면 「연결 해제」를 쓰세요.
      </p>

      <label className="account-purge__field" htmlFor={inputId}>
        확인을 위해 계좌 이름 <b>{alias}</b> 을(를) 입력해 주세요
      </label>
      <input
        id={inputId}
        className="account-purge__input"
        value={confirm}
        onChange={(e) => setConfirm(e.target.value)}
        autoComplete="off"
        placeholder={alias}
      />

      <label className="account-purge__check" htmlFor={checkboxId}>
        <input
          id={checkboxId}
          type="checkbox"
          checked={purgeEvidence}
          onChange={(e) => setPurgeEvidence(e.target.checked)}
        />
        <span>
          이 계좌 거래에만 붙어 있던 증빙도 함께 정리
          <small>다른 계좌 거래에도 붙은 증빙은 남습니다. 파일은 지워지지 않습니다.</small>
        </span>
      </label>

      {error && (
        <p className="account-purge__error" role="alert">
          {error}
        </p>
      )}

      <div className="account-purge__actions">
        <button
          type="button"
          className="account-purge__confirm"
          onClick={onPurge}
          disabled={!matches || busy}
        >
          {busy ? '지우는 중…' : '계좌 삭제'}
        </button>
        <button type="button" className="account-purge__cancel" onClick={onClose} disabled={busy}>
          취소
        </button>
      </div>
    </div>
  );
}
