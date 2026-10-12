import { useSyncExternalStore } from 'react';

/* 지갑 카드 색. 기본은 네 장이 각자 색을 갖는 원래 배합이고,
 * 사장님이 한 색을 고르면 거기서 색상을 돌려 네 장을 만든다(card-tone.css). 이 브라우저에 기억한다.
 *
 * 색은 뜻을 지지 않는다 — 카드마다 제목·아이콘·수치가 이미 무엇인지 말한다(도메인 규칙 6).
 * 그래서 어떤 색으로 뽑아도 읽는 데 지장이 없다. */

export type CardTone = 'default' | 'lavender' | 'mint' | 'peach' | 'rose' | 'slate';

/** 고를 수 있는 색. 값은 tokens 이 아니라 여기서만 정한다 —
 *  --lavender 등은 테마마다 뜻이 달라(라이트에서는 행동용 청록) 카드 재질과 맞지 않는다. */
export const CARD_TONES: { id: CardTone; label: string; swatch: string }[] = [
  { id: 'default', label: '기본 (원래 네 가지 색)', swatch: 'linear-gradient(135deg, #3572d4 0%, #7652d4 34%, #1f8f63 67%, #f2b33a 100%)' },
  { id: 'lavender', label: '라벤더', swatch: '#6f5ad6' },
  { id: 'mint', label: '민트', swatch: '#1f8f63' },
  { id: 'peach', label: '피치', swatch: '#d98032' },
  { id: 'rose', label: '로즈', swatch: '#c2557a' },
  { id: 'slate', label: '슬레이트', swatch: '#48607a' },
];

const KEY = 'jangoyebo.cardTone';
const listeners = new Set<() => void>();
export const isPresetTone = (value: string): value is CardTone =>
  CARD_TONES.some((option) => option.id === value);

function read(): string {
  try {
    const saved = localStorage.getItem(KEY);
    if (!saved) return 'default';
    // 가챠로 뽑은 색은 그대로 저장된다(hsl(...)). 프리셋이 아니면 색 문자열로 본다.
    return isPresetTone(saved) ? saved : saved.startsWith('hsl(') ? saved : 'default';
  } catch {
    return 'default';
  }
}

let tone = read();

function apply() {
  const root = document.documentElement;
  // 기본일 때는 속성 자체를 지운다 — 있으면 CSS 가 커스텀 배합으로 덮는다.
  if (tone === 'default') {
    delete root.dataset.cardTone;
    root.style.removeProperty('--card-tone');
    listeners.forEach((listener) => listener());
    return;
  }
  if (isPresetTone(tone)) {
    root.dataset.cardTone = tone;
    root.style.removeProperty('--card-tone');
  } else {
    // 가챠로 뽑은 색. 프리셋 규칙이 안 걸리도록 이름을 따로 주고 값은 직접 넣는다.
    root.dataset.cardTone = 'custom';
    root.style.setProperty('--card-tone', tone);
  }
  listeners.forEach((listener) => listener());
}

apply();

export function setCardTone(next: string) {
  tone = next;
  try {
    if (next === 'default') localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, next);
  } catch {
    // 저장이 막힌 브라우저에서는 이번 방문 동안만 유지된다.
  }
  apply();
}

/**
 * 가챠가 뽑는 통. 우리 토큰 중 **유채색**만 넣는다 —
 * 바탕·선·글자 토큰은 회색 계열이라 뽑아 봐야 네 장이 전부 잿빛이 된다.
 * 값은 읽을 때 계산된 색을 가져오므로 테마(다크·라이트)를 자동으로 따른다.
 */
const TOKEN_POOL = [
  '--lavender',
  '--accent-deep',
  '--accent-dim',
  '--line-accent',
  '--mint',
  '--mint-soft',
  '--peach',
  '--rose',
  '--w-sun',
  '--w-cloud',
  '--w-cloud-deep',
  '--w-rain',
];

/** 계산된 색 문자열을 HSL 로. 브라우저가 이미 rgb/color(srgb) 로 풀어 준 값만 받는다. */
function toHsl(value: string): { h: number; s: number; l: number } | null {
  const probe = document.createElement('canvas').getContext('2d');
  if (!probe) return null;
  probe.fillStyle = '#000';
  probe.fillStyle = value;
  const hex = probe.fillStyle;
  if (!/^#[0-9a-f]{6}$/i.test(hex)) return null;
  const r = parseInt(hex.slice(1, 3), 16) / 255;
  const g = parseInt(hex.slice(3, 5), 16) / 255;
  const b = parseInt(hex.slice(5, 7), 16) / 255;
  const max = Math.max(r, g, b),
    min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  if (d === 0) return { h: 0, s: 0, l: l * 100 };
  const s = d / (1 - Math.abs(2 * l - 1));
  const h =
    max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return { h: ((h * 60) % 360 + 360) % 360, s: s * 100, l: l * 100 };
}

/**
 * 가챠 — 토큰 색 하나를 뽑되 **채도·명도는 카드가 읽히는 폭으로 다시 맞춘다.**
 *
 * 토큰은 제 자리(글자·선·그림)에 맞게 잡힌 값이라 그대로 쓰면 어떤 건 너무 밝아
 * 흰 글자가 묻히고 어떤 건 탁해서 네 장이 잿빛이 된다. 가져오는 것은 **색상(hue)** 이고,
 * 그래야 「우리 팔레트의 색」이면서 카드로도 읽힌다.
 */
export function rollCardTone() {
  const root = getComputedStyle(document.documentElement);
  const hues = TOKEN_POOL.map((name) => toHsl(root.getPropertyValue(name).trim()))
    .filter((color): color is { h: number; s: number; l: number } => !!color && color.s > 12)
    .map((color) => color.h);
  // 토큰을 못 읽는 상황(구형 브라우저 등)에서는 색상만 무작위로 굴린다.
  const hue = hues.length ? hues[Math.floor(Math.random() * hues.length)] : Math.random() * 360;
  const saturation = 48 + Math.floor(Math.random() * 22); // 48~69%
  const lightness = 38 + Math.floor(Math.random() * 9); // 38~46%
  setCardTone(`hsl(${Math.round(hue)} ${saturation}% ${lightness}%)`);
}

export function useCardTone() {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => tone,
  );
}
