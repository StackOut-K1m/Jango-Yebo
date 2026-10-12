import { useRef, useState } from 'react';
import { NavLink, useNavigate } from 'react-router';
import { uploadEvidence } from '@jangoyebo/core';
import { useBusiness } from '@/business/useBusiness';
import { Icon } from './icons';
import './mobile-tabs.css';

/**
 * 좁은 화면의 하단 탭 바 (화면구성도 4.1).
 *
 * 명세는 다섯 칸(홈·거래·📷·캘린더·전체)이지만 캘린더는 웹에 제 라우트가 없다 —
 * `PlannedCalendar` 는 반복·예정과 시나리오 안에만 있다. 갈 곳 없는 탭을 두느니
 * 네 칸으로 두고 캘린더는 「전체」에 맡긴다. 라우트가 생기면 한 줄 늘린다.
 */
const TABS = [
  { to: '/', label: '홈', icon: 'chart' },
  { to: '/transactions', label: '거래', icon: 'card' },
  /* 여기 가운데에 촬영 단추가 선다 — 칸이 홀수여야 띠 한가운데다 */
  { to: '/evidences', label: '증빙', icon: 'receipt' },
  { to: '/menu', label: '전체', icon: 'box' },
];

export function MobileTabs() {
  const { current } = useBusiness();
  const navigate = useNavigate();
  const picker = useRef<HTMLInputElement>(null);
  /** 찍은 사진. 종류를 고를 때까지 들고 있는다 */
  const [shot, setShot] = useState<File | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function asEvidence() {
    if (!shot || !current) return;
    setSending(true);
    setError(null);
    const result = await uploadEvidence(current.businessId, shot, shot.name);
    setSending(false);
    if (!result.ready) {
      setError('아직 준비되지 않은 기능입니다.');
      return;
    }
    if (!result.ok) {
      setError(result.error.message);
      return;
    }
    close();
    navigate('/evidences');
  }

  function close() {
    setShot(null);
    setError(null);
    // 같은 사진을 다시 찍어도 change 가 오도록 비운다
    if (picker.current) picker.current.value = '';
  }

  return (
    <>
      <nav className="m-tabs" aria-label="주요 메뉴">
        {TABS.slice(0, 2).map((tab) => (
          <NavLink key={tab.to} to={tab.to} end={tab.to === '/'}>
            <Icon name={tab.icon} />
            {tab.label}
          </NavLink>
        ))}

        {/* 가운데 촬영 단추. 어느 화면에서든 엄지 닿는 자리에 둔다 */}
        {/* 그림은 카메라다. 증빙 탭과 같은 영수증을 쓰면 둘이 같은 곳으로 읽힌다 */}
        <button type="button" className="m-shoot" onClick={() => picker.current?.click()}>
          <Icon name="camera" />
          <span className="sr-only">영수증 촬영</span>
        </button>
        <input
          ref={picker}
          type="file"
          accept="image/*"
          // 네이티브 카메라를 연다. 카메라가 없으면 브라우저가 알아서 파일 고르기로 떨어진다
          capture="environment"
          hidden
          onChange={(event) => setShot(event.target.files?.[0] ?? null)}
        />

        {TABS.slice(2).map((tab) => (
          <NavLink key={tab.to} to={tab.to}>
            <Icon name={tab.icon} />
            {tab.label}
          </NavLink>
        ))}
      </nav>

      {shot && (
        <div className="m-sheet-veil" onClick={sending ? undefined : close}>
          <div
            className="m-sheet"
            role="dialog"
            aria-modal="true"
            aria-label="찍은 것이 무엇인가요"
            onClick={(event) => event.stopPropagation()}
          >
            <h2>무엇을 찍으셨나요?</h2>
            <p className="m-sheet__lede">종류에 따라 다른 곳으로 들어갑니다.</p>

            <button type="button" className="m-sheet__pick" onClick={asEvidence} disabled={sending}>
              <Icon name="receipt" />
              <span>
                <b>영수증 · 세금계산서</b>
                <small>증빙함에 넣고 거래와 맞춰 봅니다</small>
              </span>
            </button>

            {/* ponytail: 매출전표 OCR(14.7)은 결과를 저장하지 않는다 — 검토 화면이 생기기
                전에 부르면 인식 결과가 갈 곳 없이 버려진다. 그 화면이 생기면 연다 */}
            <button type="button" className="m-sheet__pick" disabled>
              <Icon name="chart" />
              <span>
                <b>매출전표 · 포스 정산표</b>
                <small>검토 화면 준비 중입니다</small>
              </span>
            </button>

            {error && <p className="m-sheet__error" role="alert">{error}</p>}

            <button type="button" className="m-sheet__cancel" onClick={close} disabled={sending}>
              {sending ? '올리는 중…' : '다시 찍기'}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
