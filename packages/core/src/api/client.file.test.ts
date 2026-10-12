import { expect, test } from 'vitest';
import { fileNameFrom } from './client';

// 한글 파일명은 filename* 에만 온전히 담긴다(Spring ContentDisposition 이 둘을 함께 낸다)
test('Content-Disposition 에서 파일 이름을 읽는다', () => {
  expect(
    fileNameFrom(
      "attachment; filename=\"=?UTF-8?Q?=EC=8B=A0=EA=B3=A0?=\"; filename*=UTF-8''%EC%8B%A0%EA%B3%A0%EC%84%9C%EC%B4%88%EC%95%88_301.xlsx",
    ),
  ).toBe('신고서초안_301.xlsx');
  expect(fileNameFrom('attachment; filename="report.xlsx"')).toBe('report.xlsx');
  expect(fileNameFrom(null)).toBeUndefined();
});
