import { describe, expect, it } from 'vitest';
import { EMPTY, formatManwon, formatManwonRange, formatMoney } from './money';

// 눈으로 잡을 수 없는 규칙만 고정한다. toLocaleString 같은 표준 동작은 테스트하지 않는다.
describe('formatMoney', () => {
  // −(U+2212)와 -(하이픈)은 화면에서 구분되지 않는다. 리뷰로 잡을 수 없다.
  it('음수는 빼기 기호 U+2212를 쓴다', () => {
    expect(formatMoney(-5000)).toBe('−5,000원');
    expect(formatMoney(-5000)).not.toContain('-');
  });

  // 응답에 없는 필드는 null이 아니라 키 자체가 없다(구현현황 3.3).
  // undefined를 빠뜨리면 화면에 'undefined원'이 뜬다.
  it('값이 없으면 —로 받아낸다', () => {
    expect(formatMoney(undefined)).toBe(EMPTY);
    expect(formatMoney(null)).toBe(EMPTY);
  });
});

describe('formatManwon', () => {
  it('만원 단위로 줄인다', () => {
    expect(formatManwon(1340000)).toBe('134만원');
  });

  // '447.6만'은 돈으로 읽히지 않는다는 의견으로 소수점을 없앴다.
  it('소수점 없이 만 단위로 반올림한다', () => {
    expect(formatManwon(4476000)).toBe('448만원');
  });

  it('만 미만은 원 단위로 그대로 쓴다', () => {
    expect(formatManwon(9500)).toBe('9,500원');
  });

  // '−10,800만'은 자릿수를 세야 읽힌다.
  it('1억부터는 억을 붙인다', () => {
    expect(formatManwon(-108000000)).toBe('−1억 800만원');
    expect(formatManwon(200000000)).toBe('2억원');
    // 만 단위 반올림이 억으로 올라가는 경계
    expect(formatManwon(99995000)).toBe('1억원');
  });
});

// 도메인 규칙 1: 예측은 확정값이 아니라 범위로 표시한다.
describe('formatManwonRange', () => {
  it('예측은 범위로 표시한다', () => {
    expect(formatManwonRange(1200000, 1400000)).toBe('120~140만원');
  });

  it('줄여서 같아지면 값 하나만 쓴다', () => {
    expect(formatManwonRange(1000000, 1004000)).toBe('100만원');
    // 반복 거래 화면이 이 접힘에 기대 단위를 하나로 쓴다 — 값이 하나여도 이 함수만 부른다
    expect(formatManwonRange(1340000, 1340000)).toBe('134만원');
    expect(formatManwonRange(9500, 9500)).toBe('9,500원');
  });

  // 억이 낀 범위에서 앞쪽 '만'을 떼면 '9,800~1억'으로 읽힌다.
  it('억이 끼면 앞쪽 만 단위를 남긴다', () => {
    expect(formatManwonRange(98000000, 102000000)).toBe('9,800만~1억 200만원');
    expect(formatManwonRange(-108000000, -72000000)).toBe('−1억 800만~−7,200만원');
    expect(formatManwonRange(100000000, 200000000)).toBe('1억~2억원');
  });

  it('만 미만이 끼면 앞쪽 단위를 그대로 둔다', () => {
    expect(formatManwonRange(5000, 20000)).toBe('5,000원~2만원');
  });
});
