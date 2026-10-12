import { useId } from 'react';
import { CARD_TONES, isPresetTone, rollCardTone, setCardTone, useCardTone } from './cardTone';
import './card-tone-picker.css';

/** 무지개 별. icons.tsx 의 sparkle 과 같은 길이지만 단색이 아니라 그라디언트를 입힌다 —
 *  Icon 은 stroke 가 currentColor 라 여러 색을 못 낸다. */
function RainbowSparkle() {
  const id = useId().replace(/:/g, '');
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#ff5f6d" />
          <stop offset="25%" stopColor="#ffb648" />
          <stop offset="50%" stopColor="#3ecf8e" />
          <stop offset="75%" stopColor="#3a86ff" />
          <stop offset="100%" stopColor="#b14aed" />
        </linearGradient>
      </defs>
      <path
        d="m12 3 2.3 6.7L21 12l-6.7 2.3L12 21l-2.3-6.7L3 12l6.7-2.3L12 3Z"
        stroke={`url(#${id})`}
        strokeWidth="1.9"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** 프로필 메뉴의 한 줄. 지갑 카드 네 장의 색을 한 배합으로 묶는다.
 *  색만으로 뜻을 말하지 않으므로(카드마다 제목·아이콘·수치가 있다) 자유롭게 골라도 된다. */
export function CardTonePicker() {
  const tone = useCardTone();
  // 프리셋에 없는 값 = 가챠로 뽑은 색
  const custom = tone !== 'default' && !isPresetTone(tone);
  return (
    <div className="card-tone" role="group" aria-label="지갑 카드 색">
      <span className="card-tone__label">지갑 카드 색</span>
      <div className="card-tone__swatches">
        {CARD_TONES.map((option) => (
          <button
            key={option.id}
            type="button"
            className="card-tone__swatch"
            style={{ background: option.swatch }}
            aria-label={option.label}
            aria-pressed={tone === option.id}
            title={option.label}
            onClick={() => setCardTone(option.id)}
          />
        ))}
        {/* 가챠 — 우리 토큰 색 가운데 하나를 뽑는다(cardTone.ts) */}
        <button
          type="button"
          className="card-tone__roll"
          aria-label="토큰 색 가운데 무작위로 뽑기"
          aria-pressed={custom}
          title="무작위"
          onClick={rollCardTone}
        >
          <RainbowSparkle />
        </button>
      </div>
    </div>
  );
}
