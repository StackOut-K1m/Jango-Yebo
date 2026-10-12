import { useRef } from 'react';
import type { BalanceForecast, ResidualSource } from '@jangoyebo/core';
import { ChartLegend } from './BalanceChart';
import { Icon } from './icons';
import './basis-dialog.css';

const CONFIDENCE: Record<string, string> = {
  HIGH: '높음',
  MEDIUM: '보통',
  LOW: '낮음',
  UNPREDICTABLE: '예측 어려움',
};

/** 예정 거래 밖의 흐름(매일 들어오고 나가는 돈)을 무엇으로 추정했나. 셋 다 정상 상태다(API 10.1) */
const RESIDUAL: Record<ResidualSource, string> = {
  MODEL: '예측 모델',
  BASELINE: '과거 통계',
  NONE: '반영 안 함',
};

/**
 * 「이 곡선의 근거」 — 메인 곡선 아래 버튼으로 연다(구 잔액 예보 화면의 근거 구획).
 * 네이티브 dialog 라 Esc·포커스 가두기·배경 막기를 브라우저가 맡는다.
 */
export function BasisDialog({ forecast }: { forecast: BalanceForecast }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const basis = forecast.basis;
  const residual = basis?.residual;

  return (
    <>
      <button type="button" className="basis-open" onClick={() => dialog.current?.showModal()}>
        <Icon name="info" size={15} /> 이 곡선의 근거
      </button>

      <dialog
        ref={dialog}
        className="basis-dialog"
        aria-labelledby="basis-title"
        // 바깥(배경)을 누르면 닫는다
        onClick={(e) => e.target === e.currentTarget && dialog.current?.close()}
      >
        <div className="basis-dialog__body">
          <div className="basis-dialog__top">
            <h2 id="basis-title">이 곡선의 근거</h2>
            <button type="button" aria-label="닫기" onClick={() => dialog.current?.close()}>
              <Icon name="close" size={18} />
            </button>
          </div>
          <p className="basis-dialog__lede">
            예측 값은 범위이며 확정 금액이 아닙니다. 아래 자료로 계산했습니다.
          </p>

          <h3 className="v7-subhead">곡선 색과 날씨</h3>
          <p className="basis-legend chart-legend">
            <ChartLegend safetyLine={forecast.safetyLine ?? null} />
          </p>

          <h3 className="v7-subhead">반복·예정 거래</h3>
          <dl className="v7-facts v7-facts--wide">
            <div>
              <dt>확정 예정 거래</dt>
              <dd>{basis?.confirmedPlannedCount ?? 0}건</dd>
            </div>
            <div>
              <dt>반복 패턴이 예측한 거래</dt>
              <dd>{basis?.expectedPlannedCount ?? 0}건</dd>
            </div>
            <div>
              <dt>직접 입력</dt>
              <dd>{basis?.userAddedCount ?? 0}건</dd>
            </div>
            <div>
              <dt>참고 기간</dt>
              <dd>{basis?.referencePeriod ?? '—'}</dd>
            </div>
          </dl>

          <h3 className="v7-subhead">예정 밖 흐름(잔여)</h3>
          <dl className="v7-facts v7-facts--wide">
            <div>
              <dt>추정 방식</dt>
              <dd>{residual?.source ? RESIDUAL[residual.source] : '—'}</dd>
            </div>
            <div>
              <dt>학습에 쓴 거래 기록</dt>
              <dd>{residual?.historyDays !== undefined ? `${residual.historyDays}일` : '—'}</dd>
            </div>
            <div>
              <dt>신뢰 수준</dt>
              <dd>{forecast.confidence ? (CONFIDENCE[forecast.confidence] ?? forecast.confidence) : '—'}</dd>
            </div>
            <div>
              <dt>계산 엔진</dt>
              <dd>{forecast.modelVersion ?? '—'}</dd>
            </div>
          </dl>

          {forecast.adjustments && forecast.adjustments.length > 0 && (
            <>
              <h3 className="v7-subhead">보정</h3>
              <ul className="v7-rows">
                {forecast.adjustments.map((a, i) => (
                  <li key={`${a.type}-${i}`}>
                    <span className="d">
                      {a.description ?? a.type ?? '—'}
                      {a.affectedDates?.length ? <small>{a.affectedDates.join(', ')}</small> : null}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}

          {forecast.warnings && forecast.warnings.length > 0 && (
            <ul className="v7-warnings">
              {forecast.warnings.map((w) => (
                <li key={w}>
                  <Icon name="info" size={15} /> {w}
                </li>
              ))}
            </ul>
          )}
        </div>
      </dialog>
    </>
  );
}
