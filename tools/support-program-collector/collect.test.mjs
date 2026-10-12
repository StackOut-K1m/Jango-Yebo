import { describe, expect, it } from 'vitest';

import {
  byUrgency,
  decodeEntities,
  extractBody,
  parseMaxStructure,
  parsePeriod,
  parseStamp,
} from './collect.mjs';

/**
 * 상세 본문 추출.
 *
 * 여기서 지키려는 것은 **다른 공고의 내용이 원문에 섞이지 않는 것**이다. 기업마당 상세
 * 페이지 아래에는 「이 공고를 열람한 사용자가 함께 본 공고」 블록이 있고, 거기에 다른
 * 공고의 제목과 신청기간이 그대로 들어 있다. 안 자르면 모델이 그것을 이 공고의 내용으로
 * 읽어 **엉뚱한 마감일이 자격 조건에 들어간다.**
 */
describe('extractBody', () => {
  const page = `
    <html><head><style>.x{color:red}</style><script>var a=1;</script></head>
    <body>
      <div class="gnb">지원사업 공고 스크랩 13</div>
      <div class="sub_cont">
        <h2>2026년 소공인특화지원센터 운영기관 모집 공고</h2>
        <p>사업개요</p>
        <p>소공인의 집적지 활성화를 지원하기 위한 운영기관을 모집합니다.</p>
        <p>&#9758; 특화지원센터 운영역량을 갖춘 &lt;소공인 관련 비영리기관&gt;</p>
        <p>문의처</p>
        <p>소상공인시장진흥공단 042-363-7903</p>
      </div>
      <div class="rating">정보에 만족하셨나요?</div>
      <div class="related">
        이 공고를 열람한 사용자가 함께 본 공고
        NO. 1 2026.05.07~2026.10.31 [대구] 2026년 기술혁신전문가 지원사업 공고
      </div>
    </body></html>`;

  const body = extractBody(page);

  it('본문을 사업개요부터 시작한다', () => {
    expect(body.startsWith('사업개요')).toBe(true);
    expect(body).toContain('소공인 관련 비영리기관');
    expect(body).toContain('042-363-7903');
  });

  it('다른 공고의 제목과 기간을 들이지 않는다', () => {
    expect(body).not.toContain('기술혁신전문가');
    expect(body).not.toContain('2026.05.07');
    expect(body).not.toContain('함께 본 공고');
  });

  it('평점 위젯에서 끊는다', () => {
    expect(body).not.toContain('만족하셨나요');
  });

  it('script·style 내용을 남기지 않는다', () => {
    expect(body).not.toContain('var a=1');
    expect(body).not.toContain('color:red');
  });

  it('엔티티를 되돌린다', () => {
    expect(body).toContain('<소공인 관련 비영리기관>');
  });

  /** 사업개요 표시가 없는 페이지도 통째로는 넘긴다 — 빈 문자열이 되면 구조화가 통으로 빠진다. */
  it('사업개요 표시가 없어도 본문을 버리지 않는다', () => {
    const text = extractBody('<div>지원대상 전 업종 소상공인</div>');
    expect(text).toContain('지원대상');
  });
});

/**
 * 신청기간 파싱.
 *
 * 공식 문서와 실제 응답의 형식이 다르다 — 둘 다 받아야 한다. 그리고 **비어 있는 것이
 * 정상**이다(상시모집). 없다고 거르면 정상 공고가 목록에서 사라진다.
 */
describe('parsePeriod', () => {
  it('실제 응답 형식(하이픈)을 읽는다', () => {
    expect(parsePeriod('2026-09-14 ~ 2026-09-20')).toEqual({
      start: '2026-09-14',
      end: '2026-09-20',
    });
  });

  it('문서 형식(YYYYMMDD)도 읽는다', () => {
    expect(parsePeriod('20220727 ~ 20220930')).toEqual({ start: '2022-07-27', end: '2022-09-30' });
  });

  it('상시모집은 빈 값이다', () => {
    expect(parsePeriod('')).toEqual({ start: null, end: null });
    expect(parsePeriod('추후 공지')).toEqual({ start: null, end: null });
    expect(parsePeriod(null)).toEqual({ start: null, end: null });
  });

  it('한쪽만 있어도 버리지 않는다', () => {
    expect(parsePeriod('2026-09-14 ~')).toEqual({ start: '2026-09-14', end: null });
  });
});

/**
 * 수정일자 파싱.
 *
 * **한국시간으로 읽어야 한다.** UTC 로 읽으면 9시간이 밀려 「출처가 고쳤나」 판정이
 * 흔들리고, 그러면 안 바뀐 공고의 상세 페이지를 매일 다시 받는다.
 */
describe('parseStamp', () => {
  it('한국시간으로 읽는다', () => {
    expect(parseStamp('2026-09-14 15:55:52')).toBe('2026-09-14T15:55:52+09:00');
  });

  it('같은 값은 같은 결과를 낸다 — 이게 흔들리면 매일 전건을 다시 긁는다', () => {
    expect(parseStamp('2026-09-14 15:55:52')).toBe(parseStamp('2026-09-14 15:55:52'));
  });

  it('모르는 모양은 null 이다', () => {
    expect(parseStamp('')).toBeNull();
    expect(parseStamp('추후 공지')).toBeNull();
  });
});

/** 요약에도 써야 한다 — 안 풀면 상세 화면에 `&lt;` 가 그대로 보인다. */
describe('decodeEntities', () => {
  it('엔티티를 되돌린다', () => {
    expect(decodeEntities('&lt;이커머스&gt;&nbsp;에이블')).toBe('<이커머스> 에이블');
  });

  it('&amp; 를 먼저 풀어 이중 복원하지 않는다', () => {
    expect(decodeEntities('&amp;lt;태그가 아님&amp;gt;')).toBe('&lt;태그가 아님&gt;');
  });
});

/**
 * 본문을 받을 순서.
 *
 * **예산이 중간에 끊겨도 가장 쓸모 있는 것부터 채워지게 한다.** 마감이 가까운 공고는
 * 지금 안 읽으면 읽을 기회가 사라지고, 기간이 없는 공고(1,526건 중 916건이
 * 「예산 소진시까지」·「상시」다)는 내일 읽어도 그대로 있다.
 */
describe('byUrgency', () => {
  it('마감이 가까운 것부터', () => {
    const rows = [
      { externalId: 'c', applyEnd: '2026-12-31' },
      { externalId: 'a', applyEnd: '2026-09-20' },
      { externalId: 'b', applyEnd: '2026-10-05' },
    ];
    expect([...rows].sort(byUrgency).map((r) => r.externalId)).toEqual(['a', 'b', 'c']);
  });

  it('기간이 없는 공고는 뒤로 — 내일 읽어도 그대로 있다', () => {
    const rows = [
      { externalId: '상시', applyEnd: null },
      { externalId: '마감임박', applyEnd: '2026-09-20' },
    ];
    expect([...rows].sort(byUrgency).map((r) => r.externalId)).toEqual(['마감임박', '상시']);
  });
});

/**
 * `--max-structure` 파싱.
 *
 * **이 인자가 곧 돈이다.** 전에는 `Number(...) || 200` 이라 0 을 주면 200 이 됐다 —
 * 「돈 안 쓰고 목록만 받아 보자」고 0 을 주는 것이 가장 자연스러운 시도인데 정확히
 * 반대 결과가 나왔다. 200건이 구조화되어 약 $1.3 이 나간다.
 */
describe('parseMaxStructure', () => {
  it('0 은 0 이다 — 구조화하지 않는다는 뜻이고, 200 으로 둔갑하지 않는다', () => {
    expect(parseMaxStructure(['--max-structure', '0'])).toBe(0);
  });

  it('없으면 기본 200', () => {
    expect(parseMaxStructure([])).toBe(200);
    expect(parseMaxStructure(['--region', '광주'])).toBe(200);
  });

  it('정상 값을 그대로 읽는다', () => {
    expect(parseMaxStructure(['--region', '광주', '--max-structure', '40'])).toBe(40);
  });

  // 조용히 기본값으로 흘려보내면 쓰려던 것보다 훨씬 많이 쓴다. 죽는 편이 낫다.
  it.each([
    ['값이 없을 때', ['--max-structure']],
    ['다음 인자가 또 옵션일 때', ['--max-structure', '--region']],
    ['숫자가 아닐 때', ['--max-structure', 'abc']],
    ['음수일 때', ['--max-structure', '-1']],
    ['소수일 때', ['--max-structure', '1.5']],
  ])('%s 던진다', (_, argv) => {
    expect(() => parseMaxStructure(argv)).toThrow('0 이상의 정수');
  });
});
