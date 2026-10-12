import { expect, it } from 'vitest';
import { riskOf } from './risk';

// 경계값 판정이다. 숫자만 받는 순수 함수라 API가 붙어도 이 테스트는 그대로 유효하다.
// 등급이 한 칸 밀리면 화면 배경색과 알림이 통째로 달라지므로 경계를 고정해 둔다.
it('안전선을 기준으로 등급을 가른다', () => {
  const line = 2_000_000;
  expect(riskOf(-1, line)).toBe('GAP');
  expect(riskOf(0, line)).toBe('DANGER');
  expect(riskOf(499_999, line)).toBe('DANGER'); // 안전선의 25% 미만
  expect(riskOf(500_000, line)).toBe('CAUTION');
  expect(riskOf(1_999_999, line)).toBe('CAUTION');
  expect(riskOf(2_000_000, line)).toBe('SAFE'); // 안전선 이상
});

// 안전선을 설정하지 않은 사용자가 있다. 임의의 기본값을 만들어 '주의'라고 말하면 안 된다.
it('안전선이 없으면 0원 기준만 판정한다', () => {
  expect(riskOf(-1, null)).toBe('GAP');
  expect(riskOf(0, null)).toBe('SAFE');
});
