import { expect, test } from 'vitest';
import { pageNumbers } from './pageNumbers';

// 화면에는 1부터 보이지만 여기서는 0부터 센다. null 은 「…」 자리다.
test('페이지 번호 줄은 처음·끝과 현재 앞뒤만 보이고 건너뛴 자리는 줄임표다', () => {
  expect(pageNumbers(0, 5)).toEqual([0, 1, 2, 3, 4]);
  expect(pageNumbers(0, 20)).toEqual([0, 1, 2, 3, 4, 5, null, 19]);
  expect(pageNumbers(10, 20)).toEqual([0, null, 8, 9, 10, 11, 12, null, 19]);
  expect(pageNumbers(19, 20)).toEqual([0, null, 14, 15, 16, 17, 18, 19]);
  expect(pageNumbers(0, 0)).toEqual([]);
});
