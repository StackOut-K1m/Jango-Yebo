import { Fragment } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { formatDateTime } from '@jangoyebo/core';
import type { TaxDraftDetail } from '@jangoyebo/core';
import { money } from './model';
import { fieldValueLabel } from './taxView';

/**
 * 별지 제74호서식 본표(간편장부소득금액계산서, 개정 2023.3.20)와 부표(총수입금액 및 필요경비명세서,
 * 개정 2025.3.21)를 서식 칸 배치대로 그린 인쇄 전용 화면. 브라우저 인쇄에서 「PDF로 저장」하면 서식 PDF 가 된다.
 *
 * 화면에는 보이지 않고 인쇄할 때만 나온다(tax.css `@media print`). body 바로 아래에 두어야 인쇄가
 * 앱 화면을 통째로 숨길 수 있다. 값은 초안 상세를 옮겨 적기만 한다 — 계산은 서버가 끝냈다(도메인 규칙 2).
 *
 * ponytail: 사업장 칸 넷 중 첫 칸만 채운다. 여러 사업장을 합친 열·합계 열은 API 14.3 `includeBusinessIds`
 * 가 맡으니 백엔드 PDF 가 열리면 그쪽으로 옮긴다.
 */

const COLUMNS = 4;
const HAND = '직접 기재';

type Values = Map<string, number | string | null | undefined>;

function dotted(iso: string) {
  return iso.replaceAll('-', '.') + '.';
}

export function TaxFormPrint({ draft, sensitive }: { draft: TaxDraftDetail; sensitive: Record<string, string> }) {
  const values: Values = new Map(draft.sections.flatMap((s) => s.fields).map((f) => [f.fieldKey, f.value]));
  for (const [key, v] of Object.entries(sensitive)) if (v.trim()) values.set(key, v.trim());

  const text = (key: string) => {
    const v = values.get(key);
    if (typeof v === 'number') return String(v);
    const label = fieldValueLabel(v, key);
    return label === '—' ? HAND : label;
  };
  const amount = (key: string) => {
    const v = values.get(key);
    return typeof v === 'number' ? money(v) : '';
  };
  const mark = `초안 · ${draft.generatedAt ? formatDateTime(draft.generatedAt) : '생성 기준일 없음'} 기준 · 확정 세무 자료 아님`;

  /** 사업장 한 줄 — 첫 칸에 값, 나머지 셋은 비운다 */
  const cells = (first: ReactNode) => (
    <>
      <td className="tax-print__value">{first}</td>
      {Array.from({ length: COLUMNS - 1 }, (_, i) => (
        <td key={i} />
      ))}
    </>
  );
  const incomeType = (
    <>
      (30, <span className="tax-print__circle">40</span>)
    </>
  );
  const blankIncomeType = Array.from({ length: COLUMNS - 1 }, (_, i) => <td key={i}>(30, 40)</td>);

  /** ①~⑩ 은 두 장이 같다. `labelSpan` 은 표마다 이름 칸 수가 달라서 받는다 */
  const header = (labelSpan: number) => (
    <>
      <tr>
        <th colSpan={labelSpan}>①주소지</th>
        <td colSpan={2} className="tax-print__left">{text('HOME_ADDRESS')}</td>
        <td colSpan={2} className="tax-print__left">②전화번호 {values.get('PHONE') ? text('PHONE') : ''}</td>
      </tr>
      <tr>
        <th colSpan={labelSpan}>③성 명</th>
        <td colSpan={2} className="tax-print__left">{text('NAME')}</td>
        <td colSpan={2} className="tax-print__left">④생년월일 {text('BIRTH_DATE')}</td>
      </tr>
      {(
        [
          ['⑤ 소 재 지', text('BIZ_ADDRESS')],
          ['⑥ 업 종', text('INDUSTRY')],
          ['⑦ 주업종코드', values.get('INDUSTRY_CODE') ? text('INDUSTRY_CODE') : ''],
          ['⑧ 사업자등록번호', text('BIZ_REG_NO')],
        ] as const
      ).map(([label, value], i) => (
        <tr key={label}>
          {i === 0 && (
            <th rowSpan={7} colSpan={labelSpan - 1}>
              사업장
            </th>
          )}
          <th className="tax-print__label">{label}</th>
          {cells(value)}
        </tr>
      ))}
      <tr>
        <th rowSpan={2} className="tax-print__label">⑨ 과 세 기 간</th>
        {cells(`${dotted(draft.periodStart)} 부터`)}
      </tr>
      <tr>{cells(`${dotted(draft.periodEnd)} 까지`)}</tr>
      <tr>
        <th className="tax-print__label">⑩ 소 득 종 류</th>
        <td className="tax-print__value">{incomeType}</td>
        {blankIncomeType}
      </tr>
    </>
  );

  /** 금액 한 줄. `groups` 는 이 줄에서 시작하는 묶음 칸 */
  const line = (label: ReactNode, key: string | null, groups?: ReactNode, labelSpan = 1) => (
    <tr>
      {groups}
      <th colSpan={labelSpan} className="tax-print__label">
        {label}
      </th>
      {cells(key ? amount(key) : '')}
    </tr>
  );
  const group = (name: string, rows: number, colSpan = 1, vertical = true) => (
    <th rowSpan={rows} colSpan={colSpan} className={vertical ? 'tax-print__group' : undefined}>
      {name}
    </th>
  );

  const expenses: [string, string][] = [
    ['㉕ 급 료', 'PAYROLL'],
    ['㉖ 제 세 공 과 금', 'TAX_DUES'],
    ['㉗ 임 차 료', 'RENT'],
    ['㉘ 지 급 이 자', 'INTEREST'],
    ['㉙ 기업업무추진비', 'ENTERTAINMENT'],
    ['㉚ 기 부 금', 'DONATION'],
    ['㉛ 감 가 상 각 비', 'DEPRECIATION'],
    ['㉜ 차 량 유 지 비', 'VEHICLE'],
    ['㉝ 지 급 수 수 료', 'COMMISSION'],
    ['㉞ 소 모 품 비', 'SUPPLIES'],
    ['㉟ 복 리 후 생 비', 'WELFARE'],
    ['㊱ 운 반 비', 'TRANSPORT'],
    ['㊲ 광 고 선 전 비', 'ADVERTISING'],
    ['㊳ 여 비 교 통 비', 'TRAVEL'],
    ['㊴ 기 타', 'OTHER_EXPENSE'],
    ['㊵ 일반관리비등계(㉕~㊴의 합계)', 'GENERAL_EXPENSE_TOTAL'],
  ];
  // 제조비용란(⑱~㉔)은 제조업 전용이라 음식점업은 비워 둔다(부표 작성방법 5)
  const manufacturing: [string, string | null][] = [
    ['⑱ 기초 재고액', null],
    ['⑲ 당기 매입액', null],
    ['⑳ 기말 재고액', null],
    ['㉑ 당기 재료비(⑱+⑲−⑳)', null],
  ];

  return createPortal(
    <div className="tax-print" aria-hidden="true">
      <section className="tax-print__page" data-mark={mark}>
        <p className="tax-print__law">■ 소득세법 시행규칙 [별지 제74호서식] &lt;개정 2023. 3. 20.&gt;</p>
        <table>
          <colgroup>
            <col style={{ width: '5%' }} />
            <col style={{ width: '6%' }} />
            <col style={{ width: '25%' }} />
            <col style={{ width: '16%' }} />
            <col style={{ width: '16%' }} />
            <col style={{ width: '16%' }} />
            <col style={{ width: '16%' }} />
          </colgroup>
          <thead>
            <tr>
              <th colSpan={7} className="tax-print__title">
                간편장부소득금액계산서( {draft.taxYear}년 귀속)
              </th>
            </tr>
          </thead>
          <tbody>
            {header(3)}
            {line('⑪ 장부상 수입금액', 'TOTAL_REVENUE', group('총수입금액', 4), 2)}
            {line('⑫ 수입금액에서 제외할 금액', 'REVENUE_EXCLUDED', undefined, 2)}
            {line('⑬ 수입금액에 가산할 금액', 'REVENUE_ADDED', undefined, 2)}
            {line('⑭ 세무조정 후 수입금액(⑪−⑫+⑬)', 'ADJUSTED_REVENUE', undefined, 2)}
            {line('⑮ 장부상 필요경비(부표 ㊶의 금액)', 'TOTAL_EXPENSE', group('필요경비', 4), 2)}
            {line('⑯ 필요경비에서 제외할 금액', 'EXPENSE_EXCLUDED', undefined, 2)}
            {line('⑰ 필요경비에 가산할 금액', 'EXPENSE_ADDED', undefined, 2)}
            {line('⑱ 세무조정 후 필요경비(⑮−⑯+⑰)', 'ADJUSTED_EXPENSE', undefined, 2)}
            {(
              [
                ['⑲ 차가감 소득금액(⑭−⑱)', 'NET_INCOME_BEFORE_DONATION'],
                ['⑳ 기부금 한도초과액', 'DONATION_EXCESS'],
                ['㉑ 기부금이월액 중 필요경비 산입액', 'DONATION_CARRYOVER'],
                ['㉒ 해당 연도 소득금액(⑲+⑳−㉑)', 'NET_INCOME'],
              ] as const
            ).map(([label, key]) => (
              <tr key={key}>
                <th colSpan={3} className="tax-print__label">
                  {label}
                </th>
                {cells(amount(key))}
              </tr>
            ))}
            <tr>
              <td colSpan={7} className="tax-print__oath">
                「소득세법」 제70조제4항제3호 단서 및 같은 법 시행령 제132조에 따라 간편장부소득금액계산서를 제출합니다.
                <span className="tax-print__date">년 월 일</span>
                <span className="tax-print__sign">
                  제 출 인 <b>{text('NAME')}</b> <i>(서명 또는 인)</i>
                </span>
                <span className="tax-print__sign">
                  세무대리인 <b /> <i>(서명 또는 인)</i>
                </span>
                <strong className="tax-print__office">세 무 서 장 귀하</strong>
              </td>
            </tr>
            <tr>
              <th colSpan={2}>첨부서류</th>
              <td colSpan={4} className="tax-print__left">총수입금액 및 필요경비명세서(별지 제74호서식 부표) 1부</td>
              <th>수수료 없음</th>
            </tr>
          </tbody>
        </table>
      </section>

      <section className="tax-print__page" data-mark={mark}>
        <p className="tax-print__law">■ 소득세법 시행규칙 [별지 제74호서식 부표] &lt;개정 2025. 3. 21.&gt;</p>
        <table>
          <colgroup>
            <col style={{ width: '5%' }} />
            <col style={{ width: '5%' }} />
            <col style={{ width: '5%' }} />
            <col style={{ width: '21%' }} />
            <col style={{ width: '16%' }} />
            <col style={{ width: '16%' }} />
            <col style={{ width: '16%' }} />
            <col style={{ width: '16%' }} />
          </colgroup>
          <thead>
            <tr>
              <th colSpan={8} className="tax-print__title">
                총수입금액 및 필요경비명세서( {draft.taxYear}년 귀속)
              </th>
            </tr>
          </thead>
          <tbody>
            {header(4)}
            {line('⑪ 매 출 액', 'SALES', group('장부상 수입금액', 3, 3, false))}
            {line('⑫ 기 타', 'OTHER_REVENUE')}
            {line('⑬ 수입금액 합계(⑪+⑫)', 'TOTAL_REVENUE')}
            {line('⑭ 기초재고액', 'OPENING_INVENTORY', <>{group('필요경비', 28)}{group('매출원가', 4, 2)}</>)}
            {line('⑮ 당기 상품매입액 또는 제조비용(㉔)', 'PURCHASES')}
            {line('⑯ 기말재고액', 'CLOSING_INVENTORY')}
            {line('⑰ 매출원가(⑭+⑮−⑯)', 'COST_OF_SALES')}
            {manufacturing.map(([label, key], i) => (
              <Fragment key={label}>
                {line(label, key, i === 0 ? <>{group('제조비용', 7)}{group('재료비', 4)}</> : undefined)}
              </Fragment>
            ))}
            {(['㉒ 노 무 비', '㉓ 경 비', '㉔ 당기제조비용(㉑+㉒+㉓)'] as const).map((label) => (
              <tr key={label}>
                <th colSpan={2} className="tax-print__label">
                  {label}
                </th>
                {cells('')}
              </tr>
            ))}
            {expenses.map(([label, key], i) => (
              <Fragment key={key}>{line(label, key, i === 0 ? group('일반관리비등', 16, 2) : undefined)}</Fragment>
            ))}
            <tr>
              <th colSpan={3} className="tax-print__label">
                ㊶ 필요경비 합계(⑰+㊵)
              </th>
              {cells(amount('TOTAL_EXPENSE'))}
            </tr>
          </tbody>
        </table>
      </section>
    </div>,
    document.body,
  );
}
