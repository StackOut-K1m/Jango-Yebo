import { useState } from 'react';
import type { PlannedTransaction } from '@jangoyebo/core';
import { Icon } from './icons';
import { WeatherGlyph, weatherLabel } from './Weather';
import type { Weather } from './Weather';

const WEEKDAY = ['일', '월', '화', '수', '목', '금', '토'];
const DAY = 86_400_000;
const iso = (time: number) => new Date(time).toISOString().slice(0, 10);
const monthOf = (date: string) => date.slice(0, 7);

/**
 * 예정 거래 달력. 날짜를 고르면 옆 칸에서 그날의 예정 거래를 넣는다 — 날짜 칸을 따로 두지 않는다.
 * 고를 수 있는 날은 from~to(오늘~90일 뒤)뿐이다. 지난 날에는 예정을 넣을 수 없다.
 */
export function PlannedCalendar({
  from,
  to,
  rows,
  weather,
  selected,
  onSelect,
  onMove,
  moved,
}: {
  from: string;
  to: string;
  rows: PlannedTransaction[];
  /** 'YYYY-MM-DD' → 그날 예상 잔액의 날씨. 곡선이 없는 날은 비워 둔다 */
  weather: Map<string, Weather>;
  selected: string;
  onSelect: (date: string) => void;
  /**
   * 있으면 예정 지출 칩을 다른 날로 끌어다 놓을 수 있다(시나리오 화면). 원본을 고치지 않고 알리기만 한다.
   * ponytail: 마우스 끌기뿐이다 — 키보드·터치는 시나리오 화면의 「직접 조합하기 · 지출일 변경」으로 대신한다.
   */
  onMove?: (row: PlannedTransaction, date: string) => void;
  /** 옮긴 칩 표시용. 원래 날짜가 아닌 곳에 놓인 거래 id */
  moved?: Set<number>;
}) {
  const [month, setMonth] = useState(monthOf(selected || from));
  /**
   * 「외 N건」을 펴 둔 날. <b>고른 날과 따로 센다.</b>
   *
   * 폄과 고름을 한 값으로 묶어 두었더니 반복·예정 화면에서 「접기」가 듣지 않았다. 거기서
   * 고른 날은 <b>등록할 예정 거래의 날짜</b>라 다시 눌러도 같은 날짜를 도로 넣을 뿐이고,
   * 토글로 바꾸면 이번엔 접을 때마다 폼의 날짜가 비워진다. 접는 것은 보기의 일이라 여기서 든다.
   */
  const [opened, setOpened] = useState<string | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const byDate = new Map<string, PlannedTransaction[]>();
  for (const row of rows) byDate.set(row.expectedDate, [...(byDate.get(row.expectedDate) ?? []), row]);

  const first = Date.parse(`${month}-01T00:00:00Z`);
  const lead = new Date(first).getUTCDay();
  const days = new Date(Date.UTC(+month.slice(0, 4), +month.slice(5, 7), 0)).getUTCDate();
  const shift = (n: number) => {
    const d = new Date(first);
    d.setUTCMonth(d.getUTCMonth() + n);
    return iso(d.getTime()).slice(0, 7);
  };
  const [y, m] = month.split('-').map(Number);

  return (
    <div className="v7-cal">
      <div className="v7-cal__head">
        <button
          type="button"
          className="v7-secondary-button"
          aria-label="이전 달"
          disabled={shift(-1) < monthOf(from)}
          onClick={() => setMonth(shift(-1))}
        >
          <Icon name="chevron" size={14} style={{ transform: 'rotate(180deg)' }} />
        </button>
        <strong>
          {y}년 {m}월
        </strong>
        <button
          type="button"
          className="v7-secondary-button"
          aria-label="다음 달"
          disabled={shift(1) > monthOf(to)}
          onClick={() => setMonth(shift(1))}
        >
          <Icon name="chevron" size={14} />
        </button>
      </div>
      <div className="v7-cal__grid" role="grid" aria-label={`${y}년 ${m}월 예정 거래`}>
        {WEEKDAY.map((w) => (
          <span key={w} className="v7-cal__weekday" role="columnheader">
            {w}
          </span>
        ))}
        {Array.from({ length: lead }, (_, i) => (
          <span key={`lead-${i}`} />
        ))}
        {Array.from({ length: days }, (_, i) => {
          const date = iso(first + i * DAY);
          const items = byDate.get(date) ?? [];
          const sky = weather.get(date);
          const outside = date < from || date > to;
          return (
            <button
              key={date}
              type="button"
              role="gridcell"
              className="v7-cal__day"
              data-today={date === from || undefined}
              aria-selected={date === selected}
              disabled={outside}
              data-drop={over === date || undefined}
              onDragOver={
                onMove && !outside
                  ? (e) => {
                      e.preventDefault();
                      setOver(date);
                    }
                  : undefined
              }
              onDragLeave={onMove ? () => setOver((d) => (d === date ? null : d)) : undefined}
              onDrop={
                onMove && !outside
                  ? (e) => {
                      e.preventDefault();
                      setOver(null);
                      const row = rows.find((r) => String(r.plannedTransactionId) === e.dataTransfer.getData('text/plain'));
                      if (row && row.expectedDate !== date) onMove(row, date);
                    }
                  : undefined
              }
              aria-label={`${m}월 ${i + 1}일, 예정 거래 ${items.length}건${sky ? `, ${weatherLabel[sky]}` : ''}`}
              onClick={() => {
                onSelect(date);
                setOpened((d) => (d === date ? null : date));
              }}
            >
              <span className="v7-cal__top">
                <span className="v7-cal__num">{i + 1}</span>
                {sky && !outside && <WeatherGlyph weather={sky} />}
              </span>
              {/* 평소에는 두 건만 보인다. 고른 날은 전부 편다 — 접힌 채로 두면 「외 N건」에
                * 묶인 지출은 끌어다 놓을 방법이 없다. */}
              {(onMove ? [...items].sort((a, b) => Number(a.direction === 'DEPOSIT') - Number(b.direction === 'DEPOSIT')) : items)
                .slice(0, date === opened ? items.length : 2)
                .map((row) => (
                <span
                  key={row.plannedTransactionId}
                  className="v7-cal__chip"
                  data-direction={row.direction}
                  data-moved={moved?.has(row.plannedTransactionId) || undefined}
                  draggable={onMove && !outside && row.direction === 'WITHDRAWAL' ? true : undefined}
                  onDragStart={(e) => e.dataTransfer.setData('text/plain', String(row.plannedTransactionId))}
                  /* 입금 칩은 끌리지 않는다. 아무 말도 없으면 끌어 보고 나서야 알게 되므로
                   * 왜 안 되는지와 대신 무엇을 하면 되는지를 함께 적어 둔다. 서버가 지출
                   * 예정 거래만 날짜를 옮기게 한다(11.1 EXPENSE_DATE_SHIFT). */
                  title={
                    !onMove || outside
                      ? undefined
                      : row.direction === 'WITHDRAWAL'
                        ? '다른 날로 끌어다 놓아 보세요'
                        : '입금 예정일은 옮길 수 없습니다 — 「정산 지연」 조건으로 가정해 보세요'
                  }
                >
                  {row.direction === 'DEPOSIT' ? '+' : '−'}
                  {row.title}
                </span>
              ))}
              {items.length > 2 && (
                <span className="v7-cal__more">
                  {date === opened ? '접기' : `외 ${items.length - 2}건`}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
