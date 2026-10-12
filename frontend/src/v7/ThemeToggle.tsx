import { Icon } from './icons';
import { setThemePref, useTheme } from './theme';

/** 프로필 메뉴의 한 줄. 누르면 바뀔 모드를 적는다 — 다크 모드에서는 「라이트 모드」가 보인다.
 * 한 번도 고르지 않았으면 기기 설정을 따라 칠해 두지만, 메뉴의 선택지는 두 개뿐이다. */
export function ThemeToggle() {
  const { theme } = useTheme();
  const next = theme === 'light' ? 'dark' : 'light';
  const label = next === 'light' ? '라이트 모드' : '다크 모드';
  return (
    <button type="button" aria-label={`${label}로 바꾸기`} onClick={() => setThemePref(next)}>
      <Icon name={next === 'light' ? 'sun' : 'moon'} size={17} /> {label}
    </button>
  );
}
