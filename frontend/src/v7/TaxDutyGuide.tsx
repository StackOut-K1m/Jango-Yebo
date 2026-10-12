import { useState } from 'react';
import { Link } from 'react-router';
import type { IndustryType } from '@jangoyebo/core';
import { ExportPackagePanel } from './ExportPackage';
import { Section } from './PageShell';
import { Icon } from './icons';
import { DOUBLE_ENTRY_PREP, DOUBLE_ENTRY_STEPS, ledgerThresholdLabel, likelyDuty } from './taxView';
import type { DutyAnswers, RevenueBand } from './taxView';

/**
 * 신고서 초안 화면의 갈래별 안내(S15P21C105-260). 장부 유형 × 신고 방식마다 다음 행동을 보여 준다.
 * 서비스는 유형을 판정하지 않는다 — 「모르겠어요」도 가능성과 확인 방법으로 끝난다(SRS 6.10).
 */

const DUTY_LABEL = { SIMPLE_LEDGER: '간편장부', DOUBLE_ENTRY: '복식부기' } as const;

function YesNo({ label, value, onChange }: { label: string; value: boolean | undefined; onChange: (v: boolean) => void }) {
  return (
    <div className="tax-quiz__q">
      <span>{label}</span>
      <div className="v7-chips" role="group" aria-label={label}>
        <button type="button" aria-pressed={value === true} onClick={() => onChange(true)}>
          예
        </button>
        <button type="button" aria-pressed={value === false} onClick={() => onChange(false)}>
          아니요
        </button>
      </div>
    </div>
  );
}

/** 「모르겠어요」 — 질문 몇 개로 가능성을 알려 주고, 홈택스에서 확인하게 한다 */
export function DutyQuiz({
  industry,
  onPick,
}: {
  industry: IndustryType | undefined;
  onPick: (duty: 'SIMPLE_LEDGER' | 'DOUBLE_ENTRY') => void;
}) {
  const [a, setA] = useState<DutyAnswers>({});
  const threshold = industry ? ledgerThresholdLabel(industry) : null;
  const guess = likelyDuty(a);
  const askNew = a.professional === false;
  const askRevenue = askNew && a.newlyOpened === false && threshold !== null;
  // 더 물을 것이 없는데 가능성을 말할 수 없는 경우 — 업종 기준이 없거나 매출을 모른다
  const stuck = guess === null && askNew && a.newlyOpened === false && (threshold === null || a.revenue === 'UNKNOWN');

  return (
    <Section title="장부 유형 알아보기">
      <div className="tax-quiz">
        <YesNo
          label="의사·변호사·세무사 같은 전문직 사업자인가요?"
          value={a.professional}
          onChange={(professional) => setA({ professional })}
        />
        {askNew && (
          <YesNo
            label="올해 개업했나요?"
            value={a.newlyOpened}
            onChange={(newlyOpened) => setA({ professional: false, newlyOpened })}
          />
        )}
        {askRevenue && (
          <div className="tax-quiz__q">
            <span>작년 매출(수입금액)은 얼마쯤인가요?</span>
            <div className="v7-chips" role="group" aria-label="작년 매출">
              {(
                [
                  ['BELOW', `${threshold} 미만`],
                  ['ABOVE', `${threshold} 이상`],
                  ['UNKNOWN', '잘 모르겠어요'],
                ] as [RevenueBand, string][]
              ).map(([id, label]) => (
                <button key={id} type="button" aria-pressed={a.revenue === id} onClick={() => setA({ ...a, revenue: id })}>
                  {label}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {guess && (
        <div className="v7-note v7-note--good" role="status">
          <Icon name="info" size={18} />
          <div>
            <strong>{DUTY_LABEL[guess]}일 가능성이 높아요</strong>
            <span>답해 주신 내용으로 본 가능성입니다. 정확한 유형은 아래 방법으로 확인해 주세요.</span>
            <button type="button" className="v7-secondary-button" onClick={() => onPick(guess)}>
              {DUTY_LABEL[guess]}로 계속하기 <Icon name="arrow" size={15} />
            </button>
          </div>
        </div>
      )}
      {stuck && (
        <p className="v7-note v7-note--small" role="status">
          {threshold === null ? '업종마다 기준이 달라 여기서는 알기 어렵습니다.' : '작년 매출을 알아야 가늠할 수 있습니다.'} 아래 방법으로 확인해
          주세요.
        </p>
      )}

      <h3 className="tax-subhead">정확한 유형 확인하기</h3>
      <ol className="tax-steps">
        <li>
          홈택스에서 <b>종합소득세 신고도움 서비스</b>를 열면 기장의무(간편장부·복식부기)가 나옵니다.
        </li>
        <li>5월에 모바일이나 우편으로 오는 신고안내문에도 같은 내용이 적혀 있습니다.</li>
        <li>회계사에게 맡기고 있다면 그쪽에 물어도 됩니다.</li>
      </ol>
    </Section>
  );
}

/** 복식부기 직접 신고 — 안내까지만(2026-09-18 팀 결정). 재무제표는 채우지 않는다 */
export function DoubleEntrySelfGuide({ onAccountant }: { onAccountant: () => void }) {
  return (
    <Section title="복식부기 직접 신고 준비">
      <div className="v7-note">
        <Icon name="info" size={18} />
        <div>
          <strong>먼저 직접 신고할 수 있는지 확인해 주세요</strong>
          <span>
            매출이 일정 규모 이상이면(음식점은 약 3억원 이상) 조정계산서를 세무대리인이 작성해야 합니다. 해당된다면 회계사에게 맡겨 주세요.
          </span>
          <button type="button" className="v7-secondary-button" onClick={onAccountant}>
            회계사에게 맡김으로 보기
          </button>
        </div>
      </div>
      <p className="v7-note v7-note--small">
        서비스는 재무제표를 대신 작성하지 않습니다. 홈택스에서 아래 순서로 입력하며, 우리 화면의 자료를 참고할 수 있습니다.
      </p>
      <h3 className="tax-subhead">홈택스 입력 순서</h3>
      <ol className="tax-steps">
        {DOUBLE_ENTRY_STEPS.map((step) => (
          <li key={step}>{step}</li>
        ))}
      </ol>
      <h3 className="tax-subhead">미리 준비할 값</h3>
      <ul className="tax-checklist">
        {DOUBLE_ENTRY_PREP.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
      <h3 className="tax-subhead">참고할 수 있는 우리 자료</h3>
      <ul className="tax-checklist">
        <li>
          <Link to="/tax/review" className="tax-link">
            신고자료 검토
          </Link>{' '}
          — 거래마다 매긴 판단과 메모
        </li>
        <li>
          <Link to="/settlements" className="tax-link">
            정산 대사
          </Link>{' '}
          — 플랫폼·카드 매출과 입금 대조
        </li>
        <li>
          <Link to="/loans" className="tax-link">
            대출
          </Link>{' '}
          — 빌린 돈의 잔액
        </li>
        <li>
          <Link to="/evidences" className="tax-link">
            증빙
          </Link>{' '}
          — 거래에 연결한 영수증·세금계산서
        </li>
      </ul>
    </Section>
  );
}

/**
 * 회계사에게 맡김 — 장부 유형과 관계없이 같다.
 *
 * 먼저 우리 화면에서 정리할 것을 안내하고, 그 아래에 <b>거래·증빙을 통째로 묶어 받는</b>
 * 준비자료 패키지(14.2)를 둔다. 회계사는 합계가 아니라 원자료로 장부를 다시 쓴다.
 */
export function AccountantHandoff({
  hasDraft,
  businessId,
  year,
}: {
  hasDraft: boolean;
  businessId: number | undefined;
  year: number;
}) {
  return (
    <>
      <Section title="회계사에게 넘기기 전에">
      {hasDraft && (
        <p className="v7-note v7-note--small">
          간편장부라면 아래에서 초안을 만든 뒤, 초안 화면의 <b>검토용 PDF</b>를 회계사에게 함께 전달할 수 있습니다.
        </p>
      )}
      <h3 className="tax-subhead">정리해 두면 좋은 것</h3>
      <ul className="tax-checklist">
        <li>
          <Link to="/tax/review" className="tax-link">
            신고자료 검토
          </Link>{' '}
          — 판단이 필요한 거래에 검토 상태와 메모 남기기
        </li>
        <li>
          <Link to="/evidences" className="tax-link">
            증빙
          </Link>{' '}
          — 영수증·세금계산서를 거래에 연결하고 빠진 증빙 찾기
        </li>
        <li>
          <Link to="/settlements" className="tax-link">
            정산 대사
          </Link>{' '}
          — 플랫폼·카드 매출과 입금이 맞는지 확인하기
        </li>
      </ul>
      <p className="v7-note v7-note--small">
        위 화면은 이 계정으로만 볼 수 있어 회계사에게 링크로 넘길 수 없습니다. 아래에서 거래내역과 증빙을 파일로 묶어 받아 전달해 주세요.
      </p>
      </Section>
      <ExportPackagePanel businessId={businessId} year={year} />
    </>
  );
}
