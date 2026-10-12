import { useEffect, useRef, useState } from 'react';
import { mountWindowWeather } from './windowWeather';
import type { WindowWeather } from './windowWeather';
import { useTheme } from './theme';
import type { Weather } from './Weather';
// 캔버스를 절대배치로 겹치는 규칙이 여기 있다. 빠지면 네 장이 흐름에 들어가
// 서로 높이를 밀어 올려 판이 무한히 자란다(캔버스 할당 실패 → 깨진 이미지).
import './glass-weather.css';

export function GlassWeather({ weather }: { weather: Weather }) {
  const host = useRef<HTMLDivElement>(null);
  // 유리 바탕색을 붙일 때 한 번 읽으므로 배합이 바뀌면 다시 붙인다.
  const { theme } = useTheme();
  const [glass, setGlass] = useState<WindowWeather | null>(null);

  // 날씨는 deps 에 넣지 않는다. 고른 날이 바뀔 때마다 등급이 흔들리는데,
  // 여기서 다시 붙이면 그때마다 WebGL 컨텍스트와 셰이더를 새로 컴파일한다.
  useEffect(() => {
    if (!host.current) return;
    const mounted = mountWindowWeather(host.current, weather);
    setGlass(mounted);
    return () => {
      setGlass(null);
      mounted.destroy();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- weather 는 아래 effect 가 갈아끼운다
  }, [theme]);

  useEffect(() => {
    glass?.setWeather(weather);
  }, [glass, weather]);

  return <div ref={host} className="glass-weather" data-weather={weather} aria-hidden="true" />;
}
