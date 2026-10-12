import type { CSSProperties } from 'react';

/**
 * 날씨는 잔액 상태를 읽는 말이다 — 장식이 아니다.
 *
 *   비   잔액이 0 아래로 내려갈 가능성
 *   흐림 안전선 아래
 *   맑음 안전선 위
 *
 * 색만으로 구분하지 않는다(도메인 규칙 6). 아이콘과 상태명을 함께 낸다.
 */
export type Weather = 'sunny' | 'cloudy' | 'rainy';

export const weatherOf = (value: number, safety: number | null): Weather =>
  // 안전선이 없으면 0원만 기준이다 — 흐림을 판정할 근거가 없다(화면구성도 6.1).
  value < 0 ? 'rainy' : safety !== null && value < safety ? 'cloudy' : 'sunny';

export const weatherLabel: Record<Weather, string> = {
  sunny: '맑음',
  cloudy: '흐림',
  rainy: '비 · 공백 주의',
};

const SUN_RAYS = [
  'M12 2v2', 'M12 20v2', 'm4.93 4.93 1.41 1.41', 'm17.66 17.66 1.41 1.41',
  'M2 12h2', 'M20 12h2', 'm6.34 17.66-1.41 1.41', 'm19.07 4.93-1.41 1.41',
];

function SunMark() {
  return (
    <svg className="weather-sun" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1" strokeLinecap="round">
      <circle cx="12" cy="12" r="4" />
      {SUN_RAYS.map((d) => <path key={d} d={d} />)}
    </svg>
  );
}

function CloudMark({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1" strokeLinejoin="round">
      <path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z" />
    </svg>
  );
}

/** 큰 날씨 그림. 잔액이 바뀌면 해가 물러나고 구름과 비가 들어온다. */
export function WeatherVisual({ weather, className = '' }: { weather: Weather; className?: string }) {
  return (
    <div className={`weather-visual weather-${weather} ${className}`} aria-hidden="true">
      <div className="weather-orbit orbit-one" />
      <div className="weather-orbit orbit-two" />
      <div className="sun-position"><SunMark /></div>
      <CloudMark className="weather-cloud cloud-back" />
      <CloudMark className="weather-cloud cloud-front" />
      <div className="rain-field">
        {Array.from({ length: 14 }, (_, i) => (
          <i key={i} style={{
            '--drop-x': `${19 + ((i * 17) % 64)}%`,
            '--delay': `${-i * 0.137}s`,
            '--duration': `${0.65 + (i % 4) * 0.16}s`,
          } as CSSProperties} />
        ))}
      </div>
      <div className="weather-horizon" />
    </div>
  );
}

/** 작은 아이콘. 날짜별 예보 띠에 쓴다. */
export function WeatherGlyph({ weather }: { weather: Weather }) {
  if (weather === 'sunny') {
    return <svg className="glyph glyph-sunny" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <circle cx="12" cy="12" r="4.2" />{SUN_RAYS.map((d) => <path key={d} d={d} />)}
    </svg>;
  }
  if (weather === 'cloudy') {
    return <svg className="glyph glyph-cloudy" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round">
      <path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z" />
    </svg>;
  }
  return <svg className="glyph glyph-rainy" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M17.5 15H9a5.5 5.5 0 1 1 5.27-7h1.73a3.75 3.75 0 1 1 1.5 7Z" />
    <path d="M9 19.5 8 22M13 19.5 12 22M17 19.5 16 22" />
  </svg>;
}
