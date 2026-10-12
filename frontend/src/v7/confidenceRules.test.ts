import { expect, test } from 'vitest';
import {
  CONFIDENCE_HINT,
  FORECAST_SCORE,
  GRADE_TABLE,
  NOT_YET,
  PATTERN_SCORE,
} from './confidenceRules';
import { PATTERN_CONFIDENCE } from './patternView';

/**
 * 서버 상수를 옮겨 적은 값이라, 바뀌면 화면이 조용히 거짓말을 한다.
 * 여기서 못 박아 두면 **고칠 자리가 한 곳**이고 리뷰에서 눈에 띈다.
 *
 * 원본: `ConfidenceBand`(0.80 / 0.60 / 0.40) · `ConfidencePolicy` · `RecurringPatternDetector#score`
 */
test('등급 경계는 서버 ConfidenceBand 와 같다', () => {
  expect(GRADE_TABLE).toEqual([
    { range: '0.80 이상', grade: '높음' },
    { range: '0.60 ~ 0.80', grade: '보통' },
    { range: '0.40 ~ 0.60', grade: '낮음' },
    { range: '0.40 미만', grade: '예측 어려움' },
  ]);
});

// 표의 등급 이름과 화면의 이름표가 갈리면 같은 것인지 알 수 없다
test('표의 등급 이름이 화면 이름표와 맞는다', () => {
  const labels = Object.values(PATTERN_CONFIDENCE).map((v) => v.replace('신뢰 ', ''));
  for (const row of GRADE_TABLE) {
    expect(labels, row.grade).toContain(row.grade);
  }
});

// 하나만 나빠도 믿을 수 없어서 곱한다 — 더하기로 바뀌면 뜻이 통째로 달라진다
test('두 점수 모두 곱셈으로 적는다', () => {
  expect(PATTERN_SCORE.formula).toBe('표본 충분성 × 주기 규칙성 × 금액 안정성');
  expect(FORECAST_SCORE.formula).toBe('잔여 추정 × 예정 거래 × 예측 기간');
  for (const rule of [PATTERN_SCORE, FORECAST_SCORE]) {
    expect(rule.formula).not.toContain('+');
    expect(rule.notes.length).toBeGreaterThan(0);
  }
});

// 서버 상수를 옮겨 적은 자리라, 숫자가 설명에 남아 있어야 대조할 수 있다
test('설명에 서버 계수가 그대로 들어 있다', () => {
  const text = FORECAST_SCORE.notes.join(' ');
  for (const factor of ['1.00', '0.90', '0.75', '0.85', '180일', '0.70']) {
    expect(text, factor).toContain(factor);
  }
  expect(PATTERN_SCORE.notes.join(' ')).toContain('12건');
});

/**
 * 빠진 것을 밝히지 않으면 등급이 실제보다 단단해 보인다.
 * 적중률은 P1-05 라 아직 없다(`ConfidencePolicy` 주석).
 */
test('아직 안 들어간 것을 밝힌다', () => {
  expect(NOT_YET).toContain('적중률');
  expect(NOT_YET).toContain('아직');
});

// 도메인 규칙 1 — 0.81 같은 점수를 화면에 내지 않는다. 등급으로만 말한다
test('화면 문구가 개별 점수를 약속하지 않는다', () => {
  expect(CONFIDENCE_HINT).toContain(FORECAST_SCORE.formula);
  expect(CONFIDENCE_HINT).toContain('근거');
});
