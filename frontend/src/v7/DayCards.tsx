import { useEffect, useRef } from 'react';
import { formatManwon } from '@jangoyebo/core';
import { shortMoneyNear } from './model';
import type { Point } from './model';
import { WeatherGlyph, weatherLabel, weatherOf } from './Weather';

/** 하루 단위 예보. 휠·트랙패드·터치로 가로 스크롤한다. */
export function DayCards({
  points,
  safetyLine,
  selected,
  onSelect,
  forecastStart = 0,
  todayIndex,
  centerRequest = 0,
}: {
  points: Point[];
  safetyLine: number | null;
  selected: number;
  onSelect: (index: number) => void;
  forecastStart?: number;
  todayIndex?: number;
  /** 값이 바뀔 때마다 고른 카드를 가운데로 옮긴다(「오늘로 이동」). 카드를 누를 때는 옮기지 않는다 */
  centerRequest?: number;
}) {
  const track = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = track.current;
    if (!element) return;
    const wheel = (event: WheelEvent) => {
      if (event.ctrlKey || Math.abs(event.deltaX) >= Math.abs(event.deltaY)) return;
      const delta =
        event.deltaY *
        (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? element.clientWidth : 1);
      const end = element.scrollWidth - element.clientWidth;
      if ((delta < 0 && element.scrollLeft <= 0) || (delta > 0 && element.scrollLeft >= end - 1))
        return;
      event.preventDefault();
      element.scrollLeft += delta;
    };
    element.addEventListener('wheel', wheel, { passive: false });
    return () => element.removeEventListener('wheel', wheel);
  }, []);

  // 고른 카드가 보이게 한다. 「오늘로 이동」 요청이 새로 오면 가운데로 옮긴다.
  // 부드럽게 굴리지 않는다 — 굴러가는 도중 선택이 한 번 더 렌더되면 가장자리로 다시 붙어 버렸다.
  const centered = useRef(centerRequest);
  useEffect(() => {
    const element = track.current;
    const card = element?.children[selected] as HTMLElement | undefined;
    if (!element || !card) return;
    const left = card.offsetLeft;
    if (centered.current !== centerRequest) {
      centered.current = centerRequest;
      element.scrollLeft = left - (element.clientWidth - card.offsetWidth) / 2;
    } else if (left < element.scrollLeft) element.scrollLeft = left;
    else if (left + card.offsetWidth > element.scrollLeft + element.clientWidth)
      element.scrollLeft = left + card.offsetWidth - element.clientWidth;
  }, [selected, centerRequest]);

  return (
    <div className="day-cards">
      <div
        ref={track}
        className="daily-strip day-cards__scroll"
        role="group"
        aria-label="과거부터 미래까지 하루 단위 잔액. 마우스 휠로 날짜 이동"
      >
        {points.map((day) => {
          const weather = weatherOf(day.value, safetyLine);
          const isToday = day.index === todayIndex;
          const period = isToday ? '현재' : day.index >= forecastStart ? '예측' : '실제';
          return (
            <button
              key={day.index}
              type="button"
              className={
                isToday ? 'is-today' : day.index >= forecastStart ? 'is-forecast' : 'is-actual'
              }
              aria-pressed={day.index === selected}
              aria-label={`${day.date} ${day.weekday}요일, ${period} 잔액, ${weatherLabel[weather]}, ${formatManwon(day.value)}`}
              onClick={() => onSelect(day.index)}
            >
              {/* 날짜 바로 아래가 금액이다. 사이에 요일과 날씨가 끼어 있던 때는 카드에서
                * 가장 중요한 것(그날 얼마냐)을 읽으려고 눈이 두 줄을 건너뛰어야 했다. */}
              <span className="day">{isToday ? '오늘' : day.label}</span>
              <span className={`cash ${day.value < 0 ? 'negative' : ''}`}>
                {shortMoneyNear(day.value, safetyLine)}
              </span>
              <span className="weekday">
                {day.weekday} · {period}
              </span>
              <WeatherGlyph weather={weather} />
            </button>
          );
        })}
      </div>
    </div>
  );
}
