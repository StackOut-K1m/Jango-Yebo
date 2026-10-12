import { useSyncExternalStore } from 'react';

/* 화면 배합. 기본은 기기 설정을 따르고, 사장님이 고르면 이 브라우저에 기억한다.
 * 첫 칠은 index.html 의 인라인 스크립트가 먼저 한다 — 여기서만 하면 어두운 화면이 한 번 번쩍인다.
 * 두 곳의 저장 키와 판정이 같아야 한다. */

export type ThemePref = 'system' | 'light' | 'dark';
export type Theme = 'light' | 'dark';

const KEY = 'jangoyebo.theme';
const media = matchMedia('(prefers-color-scheme: light)');
const listeners = new Set<() => void>();

function readPref(): ThemePref {
  try {
    const saved = localStorage.getItem(KEY);
    return saved === 'light' || saved === 'dark' ? saved : 'system';
  } catch {
    return 'system';
  }
}

let pref = readPref();

function apply() {
  const theme: Theme = pref === 'system' ? (media.matches ? 'light' : 'dark') : pref;
  document.documentElement.dataset.theme = theme;
  listeners.forEach((listener) => listener());
}

media.addEventListener('change', () => {
  if (pref === 'system') apply();
});
apply();

export function setThemePref(next: ThemePref) {
  pref = next;
  try {
    if (next === 'system') localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, next);
  } catch {
    // 저장이 막힌 브라우저에서는 이번 방문 동안만 유지된다.
  }
  apply();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useTheme() {
  const theme = useSyncExternalStore(subscribe, () => document.documentElement.dataset.theme as Theme);
  const current = useSyncExternalStore(subscribe, () => pref);
  return { theme, pref: current };
}
