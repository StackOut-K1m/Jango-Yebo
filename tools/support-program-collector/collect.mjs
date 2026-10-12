#!/usr/bin/env node
// 지원사업 공고 수집기 (P1-15 · 명세 20.8)
//
//   npm run collect:programs -- [--limit 50] [--dry-run]
//
// 기업마당 공식 API 로 목록을 받고, 신규·변경분만 상세 페이지에서 본문을 채워
// POST /internal/v1/support-programs:batch 로 밀어 넣는다.
//
// **크롤링 정책** — 중소벤처24·소진공은 robots.txt 가 전면 차단이고 K-Startup 은 공고 목록
// 경로를 막았다. 기업마당만 공고 경로를 열어 뒀고 공식 API 도 있다(공통규약 23장, 2026-09-15).
// 여기서 HTML 을 받는 것은 기업마당 상세 페이지 하나뿐이다.
//
// ⚠ 시크릿은 인자로 받지 않는다. backend/.env 에서 읽고 화면에 찍지 않는다
//   (scripts/fin-account.mjs 와 같은 규칙).

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const REPO = process.env.JANGO_REPO ?? resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

const LIST_URL = 'https://www.bizinfo.go.kr/uss/rss/bizinfoApi.do';
const SOURCE_SITE = 'BIZINFO';

/** 한 번에 보낼 공고 수. 서버 상한(SupportProgramDtos.BatchRequest.MAX_ITEMS)과 같다. */
const BATCH_SIZE = 200;

/**
 * 상세 페이지 요청 간격(ms).
 *
 * <p>robots.txt 에 Crawl-delay 가 없지만 없다고 마음대로 하는 것은 아니다. 하루 한 번
 * 도는 배치라 서두를 이유가 없고, 차단당하면 시연 당일에 데이터가 안 들어온다.
 */
const DETAIL_DELAY_MS = 700;

/** 상세 본문에서 잘라 낼 지점. 이 뒤는 평점 위젯·안내문·**다른 공고 목록**이다. */
const BODY_END_MARKERS = ['정보에 만족하셨나요', '이 공고를 열람한 사용자가 함께 본 공고', '스크랩 링크복사'];

const args = process.argv.slice(2);
const DRY_RUN = args.includes('--dry-run');
const LIMIT = Number(args[args.indexOf('--limit') + 1]) || 0;

/**
 * 한 번에 **새로 본문을 받아** 구조화를 걸 공고 수의 상한.
 *
 * **이게 없으면 `npm run collect:programs` 한 줄이 1,526건어치 LLM 비용을 한 번에
 * 쓴다.** 공고는 쌓아 두고 나중에 읽어도 되는 자료라 서두를 이유가 없다 — 며칠에
 * 나눠 채우면 같은 결과를 얻으면서 하루치 사고 위험이 사라진다.
 *
 * <h2>⚠ 이것은 비용 상한이 아니다</h2>
 *
 * <p>오래 그렇게 적어 뒀는데 **거짓이었다.** 이 값은 **2단계(새 본문)에만** 걸린다.
 * 1단계는 목록 전량을 보내고, 서버는 그중 **본문을 이미 가졌는데 구조화가 안 된 것**을
 * 상한·지역과 무관하게 큐에 건다(`SupportProgramIngestService`). 대개 **지난 실행의
 * 실패분**이다.
 *
 * <p>2026-09-17 실측 — `--region 광주 --max-structure 40` 으로 돌렸더니 큐가 **169건**
 * (재투입 129 + 신규 40)이었고 비용이 **$0.65** 로 예상($0.26)의 2.5배였다.
 *
 * <p>다만 **무한정 늘지는 않는다.** 재투입 대상은 본문을 가진 것뿐이고 본문은 2단계에서만
 * 붙으므로 풀이 하루 최대 이 상한만큼만 커진다. 성공분은 빠지므로 며칠이면 마른다.
 *
 * <p>제대로 고치려면 <b>적재(20.8) 쪽이 상한을 알아야 한다.</b> 1단계가 아무것도 큐에
 * 걸지 않고 `needStructuring` 을 돌려주면, 수집기가 지역·상한을 걸어 요청할 수 있다
 * — S15P21C105-243.
 *
 * 상한에 걸려 남은 것은 다음 실행에서 이어 받는다. 서버가 `needBody` 로 계속 알려
 * 주므로 수집기가 어디까지 했는지 기억할 필요가 없다.
 *
 * **0 은 「구조화하지 않는다」다.** 목록만 $0 로 받아 보는 길이다.
 *
 * 전에는 `Number(...) || 200` 이라 **0 을 주면 200 이 됐다.** 「돈 안 쓰고 목록만
 * 받아 보자」고 0 을 주는 것이 가장 자연스러운 시도인데 정확히 반대 결과가 나왔다 —
 * 200건이 구조화되어 약 $1.3 이 나가고, 게다가 대부분 레이트리밋으로 실패한다.
 */
const MAX_STRUCTURE = (() => {
  try {
    return parseMaxStructure(args);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
})();

/**
 * `--max-structure` 를 읽는다. 없으면 200, **0 이면 구조화하지 않는다.**
 *
 * <p>던지고 끝낸다 — 잘못된 값을 기본값으로 갈음하지 않는다. 비용이 걸린 인자라
 * **오타가 조용히 200 으로 둔갑하면** 쓰려던 것보다 훨씬 많이 쓴다.
 */
export function parseMaxStructure(argv) {
  const at = argv.indexOf('--max-structure');
  if (at < 0) {
    return 200;
  }
  const raw = argv[at + 1];
  const value = Number(raw);
  if (raw === undefined || raw.startsWith('--') || !Number.isInteger(value) || value < 0) {
    throw new Error(
      `--max-structure 는 0 이상의 정수입니다 (받은 값: ${raw ?? '없음'}). ` +
        '0 은 「구조화하지 않고 목록만 적재」입니다.',
    );
  }
  return value;
}

/**
 * 지역 태그로 좁힌다 (선택).
 *
 * 1,526건 중 광주·전국 태그는 572건(37%)이다. 시연이 한 지역이면 나머지를 지금
 * 읽을 이유가 없다 — **버리는 것이 아니라 뒤로 미루는 것**이고, 행은 원문 없이 남아
 * 나중에 받아 올 수 있다. 전국 공고는 어느 지역이든 해당하므로 항상 남긴다.
 */
const REGION = args.includes('--region') ? args[args.indexOf('--region') + 1] : null;

// ------------------------------------------------------------------ 설정

/**
 * 설정 파일 경로.
 *
 * 기본은 `backend/.env` 다 — 개발 기계에서는 이미 거기에 값이 있고 `scripts/fin-account.mjs`
 * 와 같은 규칙이다.
 *
 * **서버에는 그 파일이 없다.** 저장소 미러에는 시크릿이 안 들어가고 실값은 `~/app/secrets/`
 * 에만 있다. 거기에 `backend/.env` 를 억지로 만들면 **시크릿을 저장소 모양으로 두 벌 두는
 * 셈**이라, 경로를 설정으로 뺀다.
 */
const ENV_FILE = process.env.COLLECTOR_ENV_FILE ?? resolve(REPO, 'backend', '.env');

function readEnv() {
  try {
    return Object.fromEntries(
      readFileSync(ENV_FILE, 'utf8')
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#') && line.includes('='))
        .map((line) => {
          const at = line.indexOf('=');
          return [line.slice(0, at).trim(), line.slice(at + 1).trim()];
        }),
    );
  } catch {
    console.error(`설정 파일을 읽을 수 없습니다: ${ENV_FILE}`);
    console.error('COLLECTOR_ENV_FILE 로 경로를 지정하거나 JANGO_REPO 로 저장소를 지정하세요.');
    process.exit(1);
  }
}

/**
 * 설정을 읽고 검증한다.
 *
 * <b>모듈 최상위에서 부르지 않는다.</b> 부르면 `extractBody` 하나를 import 하려는 테스트도
 * 키가 없다는 이유로 프로세스째 죽는다. 실행할 때만 읽는다.
 */
function config() {
  const env = readEnv();
  const apiKey = env.BIZINFO_API_KEY;
  const serviceToken = env.AI_SERVICE_TOKEN;
  const apiBase = env.COLLECTOR_API_BASE_URL ?? 'http://localhost:8080';

  if (!apiKey || apiKey.startsWith('__')) {
    console.error(
      'BIZINFO_API_KEY 가 비어 있습니다. https://www.bizinfo.go.kr/apiDetail.do?id=bizinfoApi 에서 신청하세요.',
    );
    process.exit(1);
  }
  if (!DRY_RUN && (!serviceToken || serviceToken.startsWith('__'))) {
    console.error('AI_SERVICE_TOKEN 이 비어 있습니다. 적재 경로는 서비스 토큰으로 인증합니다.');
    process.exit(1);
  }
  return { apiKey, serviceToken, apiBase };
}

// ------------------------------------------------------------------ 목록

/**
 * 목록 API.
 *
 * ⚠ **dataType=json 을 곧이곧대로 믿지 않는다.** 공식 문서의 JSON 예시가 유효한 JSON 이
 * 아니다(값에 따옴표가 없다). 실제 응답이 파싱되면 그대로 쓰고, 안 되면 원문을 그대로
 * 보여 주고 멈춘다 — 조용히 빈 목록으로 넘어가면 "오늘은 공고가 없네"로 읽힌다.
 */
async function fetchList(apiKey) {
  const url = new URL(LIST_URL);
  url.searchParams.set('crtfcKey', apiKey);
  url.searchParams.set('dataType', 'json');
  if (LIMIT) {
    url.searchParams.set('searchCnt', String(LIMIT));
  }

  const response = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!response.ok) {
    throw new Error(`목록 API ${response.status}`);
  }

  const text = await response.text();
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    // 키는 URL 에 실려 있으므로 URL 을 찍지 않는다. 본문 앞머리만 보여 준다.
    console.error('목록 응답이 JSON 이 아닙니다. dataType=rss(XML) 로 바꿔야 할 수 있습니다.');
    console.error(`--- 응답 앞부분 ---\n${text.slice(0, 400)}`);
    process.exit(1);
  }

  // ⚠ **실제 응답은 문서와 다르다** (2026-09-15 실호출 확인). 문서는 `jsonArray` 가
  // `item[]` 을 품은 객체라고 하는데 **실제로는 jsonArray 자체가 배열**이다.
  // 필드도 문서의 RSS 계열(seq·title·link·description)은 하나도 오지 않고
  // alias 계열(pblancId·pblancNm·pblancUrl·bsnsSumryCn)만 온다. 둘 다 받아 둔다.
  const raw = parsed?.jsonArray;
  const items = Array.isArray(raw) ? raw : (raw?.item ?? parsed?.item ?? []);
  return Array.isArray(items) ? items : [items];
}

// ------------------------------------------------------------------ 상세

/**
 * 상세 페이지 본문.
 *
 * <p><b>목록의 `description` 은 사업개요 한두 문장이라 원문이 아니다</b>(2026-09-15 공식
 * 응답 예시로 확인). 요약을 원문인 척 모델에 보내면 모델은 본문을 다 봤다고 여기고
 * `uncertainConditions` 를 비워 돌려준다 — 이 기능의 안전장치가 무력화된다.
 *
 * <p>상세 페이지에는 지원대상·지원내용·신청방법·문의처가 실려 있다. 통합공고처럼
 * "자세한 내용은 공고문 참조"만 있는 경우도 있는데, 그때는 모델이 확신 못 한 조건을
 * `uncertainConditions` 에 남기고 화면이 「기관 확인 필요」를 띄운다 — 의도한 동작이다.
 * 첨부 PDF 까지 읽는 것은 나중 과제다.
 */
async function fetchBody(url) {
  const response = await fetch(url, {
    headers: {
      // 누가 긁는지 밝힌다. 문제가 생기면 차단 전에 연락할 수 있어야 한다.
      'User-Agent': 'jango-yebo-collector/1.0 (SSAFY S15P21C105; +https://portfolio.example.invalid)',
      Accept: 'text/html',
    },
  });
  if (!response.ok) {
    return null;
  }
  return extractBody(await response.text());
}

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * HTML 엔티티를 되돌린다.
 *
 * 본문뿐 아니라 **요약에도 써야 한다** — 기업마당 `bsnsSumryCn` 에 `&lt;`·`&nbsp;` 가
 * 그대로 들어 있고, 안 풀면 상세 화면에 그 글자가 그대로 보인다.
 */
export function decodeEntities(text) {
  return (
    text
      .replace(/&nbsp;/g, ' ')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/&apos;/g, "'")
      // &amp; 를 마지막에 푼다. 먼저 풀면 `&amp;lt;` 가 `<` 로 두 번 풀린다.
      .replace(/&amp;/g, '&')
  );
}

/**
 * 신청기간 한 덩어리를 두 날짜로 쪼갠다.
 *
 * **형식이 두 가지다.** 공식 문서는 `20220727 ~ 20220930`(YYYYMMDD)이라 하고 실제 응답은
 * `2026-09-14 ~ 2026-09-20`(하이픈)이 온다. 둘 다 받는다.
 *
 * **비어 있는 것이 정상이다** — 상시모집 공고는 기간이 없다. 없다고 거르면 정상 공고가
 * 사라지므로 `null` 을 그대로 돌려준다.
 */
export function parsePeriod(value) {
  const empty = { start: null, end: null };
  if (!value || typeof value !== 'string') {
    return empty;
  }
  const dates = value.match(/\d{4}-?\d{2}-?\d{2}/g);
  if (!dates || dates.length === 0) {
    return empty;
  }
  const iso = (raw) =>
    raw.includes('-') ? raw : `${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6, 8)}`;
  return { start: iso(dates[0]), end: dates[1] ? iso(dates[1]) : null };
}

/**
 * `2026-09-14 15:55:52` 을 ISO 로. **한국시간이다** — 출처가 한국 기관이고 응답에 표준시
 * 표시가 없다. UTC 로 읽으면 9시간이 밀려 「고쳤다/안 고쳤다」 판정이 흔들린다.
 */
export function parseStamp(value) {
  if (!value || typeof value !== 'string') {
    return null;
  }
  const at = value.trim().match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/);
  if (!at) {
    return null;
  }
  const [, y, mo, d, h, mi, sec] = at;
  return `${y}-${mo}-${d}T${h}:${mi}:${sec}+09:00`;
}

/** 태그를 걷어내고 본문 구간만 남긴다. */
export function extractBody(html) {
  let text = html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(br|\/p|\/div|\/tr|\/li|\/h[1-6])[^>]*>/gi, '\n')
    .replace(/<[^>]+>/g, ' ');

  text = decodeEntities(text)
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();

  const start = text.indexOf('사업개요');
  if (start > 0) {
    text = text.slice(start);
  }

  // **여기서 자르지 않으면 다른 공고의 제목과 신청기간이 원문에 섞인다.**
  // 페이지 아래쪽 「함께 본 공고」 블록이 그대로 딸려 오고, 모델은 그것을 이 공고의
  // 내용으로 읽는다 — 엉뚱한 마감일이 자격 조건에 들어가는 경로다.
  // 마커 안의 공백은 실제 페이지에서 줄바꿈으로 갈라져 온다. 그대로 indexOf 하면
  // 안 걸리므로 공백 자리를 느슨하게 맞춘다.
  for (const marker of BODY_END_MARKERS) {
    const pattern = new RegExp(marker.split(/\s+/).map(escapeRegExp).join('\\s*'));
    const found = text.search(pattern);
    if (found > 0) {
      text = text.slice(0, found);
    }
  }

  return text.trim();
}

// ------------------------------------------------------------------ 적재

async function push({ serviceToken, apiBase }, items, collectedAt) {
  const body = JSON.stringify({ sourceSite: SOURCE_SITE, collectedAt, items });
  const response = await fetch(`${apiBase}/internal/v1/support-programs:batch`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${serviceToken}`,
      'Content-Type': 'application/json',
    },
    body,
  });
  if (!response.ok) {
    throw new Error(`적재 ${response.status} — ${(await response.text()).slice(0, 300)}`);
  }
  return (await response.json()).data;
}

// ------------------------------------------------------------------ 본체

/** 목록 한 줄을 적재 항목으로 바꾼다. 본문은 아직 없다. */
function toItem(row) {
  const externalId = row.pblancId ?? row.seq;
  const sourceUrl = row.pblancUrl ?? row.link;
  if (!externalId || !sourceUrl) {
    return null;
  }
  const period = parsePeriod(row.reqstBeginEndDe ?? row.reqstDt);
  return {
    externalId: String(externalId),
    title: String(row.pblancNm ?? row.title ?? '').trim(),
    sourceUrl: String(sourceUrl),
    organizer: String(row.excInsttNm ?? row.jrsdInsttNm ?? '').trim() || null,
    // 요약은 목록에 있다. <p> 로 감싸여 오므로 태그를 걷는다.
    rawSummary:
      decodeEntities(String(row.bsnsSumryCn ?? row.description ?? '').replace(/<[^>]+>/g, ' '))
        .replace(/\s+/g, ' ')
        .trim() || null,
    // 출처가 고친 시각. 이게 그대로면 서버가 본문을 다시 달라고 하지 않는다.
    sourceUpdatedAt: parseStamp(row.updtPnttm ?? row.creatPnttm),
    applyStart: period.start,
    applyEnd: period.end,
    // 1단계에서는 비운다. 서버가 needBody 로 필요한 것만 알려 준다.
    rawText: null,
    // 적재에는 안 보낸다. 본문을 받을 순서를 정하는 데만 쓴다.
    hashtags: String(row.hashtags ?? row.hashTags ?? ''),
  };
}

/** 적재에 보낼 모양. 순서 정하기용 필드는 뺀다. */
function forIngest({ hashtags, ...item }) {
  void hashtags;
  return item;
}

/**
 * 본문을 받을 순서를 정한다.
 *
 * <b>예산이 중간에 끊겨도 가장 쓸모 있는 것부터 채워지게 한다.</b> 마감이 가까운 공고는
 * 지금 안 읽으면 읽을 기회가 사라지고, 기간이 없는 공고(「예산 소진시까지」·「상시」가
 * 1,526건 중 916건)는 내일 읽어도 그대로 있다.
 */
export function byUrgency(a, b) {
  const deadline = (item) => (item.applyEnd ? Date.parse(item.applyEnd) : Number.MAX_SAFE_INTEGER);
  return deadline(a) - deadline(b);
}

/** 지역 태그가 맞거나 「전국」이면 남긴다. 태그가 없으면 판단할 근거가 없으므로 남긴다. */
function inRegion(item) {
  if (!REGION) {
    return true;
  }
  const tags = item.hashtags;
  return !tags || tags.includes('전국') || tags.includes(REGION);
}

async function main() {
  const settings = config();
  const collectedAt = new Date().toISOString();
  const raw = await fetchList(settings.apiKey);
  const items = raw.map(toItem).filter(Boolean);
  console.log(`목록 ${items.length}건`);

  if (items.length === 0) {
    console.warn('⚠ 목록이 비었습니다. 응답 모양이 또 바뀌었는지 확인하세요.');
    return;
  }

  if (DRY_RUN) {
    // 적재를 못 하니 서버에 물어볼 수도 없다. 첫 건만 본문까지 받아 모양을 보여 준다.
    const sample = { ...items[0], rawText: await fetchBody(items[0].sourceUrl) };
    console.log('[dry-run] 적재하지 않음 — 첫 건만 본문을 받아 봤습니다');
    console.log(JSON.stringify({ ...sample, rawText: `${(sample.rawText ?? '').slice(0, 300)}…` }, null, 2));
    return;
  }

  // --- 1단계: 목록만 보낸다. 서버가 본문이 필요한 것을 알려 준다 ---
  const first = await pushAll(settings, items.map(forIngest), collectedAt);
  console.log(
    `1단계 — 신규 ${first.created} · 갱신 ${first.updated} · 그대로 ${first.unchanged} · ` +
      `구조화큐 ${first.queued} · 본문필요 ${first.needBody.length}`,
  );

  if (first.needBody.length === 0) {
    console.log('상세 페이지를 받을 것이 없습니다.');
    return;
  }

  // --- 2단계: 그중 일부만 상세 페이지를 받아 다시 보낸다 ---
  //
  // **여기서 거는 수가 곧 LLM 비용이다.** 지역으로 좁히고, 마감 임박순으로 정렬한 뒤,
  // 상한까지만 자른다. 남은 것은 다음 실행에서 이어 받는다 — 서버가 needBody 로 계속
  // 알려 주므로 어디까지 했는지 기억할 필요가 없다.
  const wanted = new Set(first.needBody);
  const queue = items.filter((row) => wanted.has(row.externalId));
  const eligible = queue.filter(inRegion).sort(byUrgency);
  const target = eligible.slice(0, MAX_STRUCTURE);

  // 0 이면 여기서 끝낸다. **적재는 이미 1단계에서 끝났고** 남은 것은 본문 수집과
  // 구조화뿐이라 돈이 드는 일만 건너뛰는 셈이다.
  if (MAX_STRUCTURE === 0) {
    console.log(
      `2단계 — 건너뜁니다 (--max-structure 0). 본문 ${queue.length}건을 받지 않았고 ` +
        '구조화도 걸지 않았습니다 · 비용 $0',
    );
    // 이 말을 안 해 주면 「적재는 됐는데 화면이 비어 있다」로 헤맨다.
    console.log(
      '   ⚠ 구조화되지 않은 공고는 지원 제도 목록(17.1)에 나오지 않습니다. ' +
        '화면용 데이터가 필요하면 tools/support-program-collector/seed-sample.sql 을 쓰세요.',
    );
    return;
  }

  if (target.length < queue.length) {
    const byRegion = queue.length - eligible.length;
    const byCap = eligible.length - target.length;
    console.log(
      `   본문 ${queue.length}건 중 ${target.length}건만 받습니다` +
        (REGION ? ` · 지역(${REGION}) 밖 ${byRegion}건 제외` : '') +
        (byCap ? ` · 상한(${MAX_STRUCTURE}) 초과 ${byCap}건은 다음 실행` : ''),
    );
  }

  const withBody = [];
  for (const item of target) {
    const body = await fetchBody(item.sourceUrl);
    await sleep(DETAIL_DELAY_MS);
    if (body) {
      withBody.push({ ...forIngest(item), rawText: body });
    }
    process.stdout.write(`\r상세 ${withBody.length}/${target.length}`);
  }
  process.stdout.write('\n');

  const second = await pushAll(settings, withBody, collectedAt);
  console.log(`2단계 — 본문 ${withBody.length}건 전달 · 구조화큐 ${second.queued}`);

  const queued = first.queued + second.queued;
  const remaining = queue.length - target.length;
  if (remaining > 0) {
    console.log(`남은 ${remaining}건은 다음 실행에서 이어 받습니다.`);
  }
  // 구조화 큐 수가 곧 LLM 비용이다.
  //
  // **이 경고는 이상 징후가 아니라 정상 동작이다.** 오래 「원문이 통째로 바뀐 게
  // 아닌지 보세요」라고 안내했는데 틀린 말이었다 — 1단계가 **본문을 이미 가진
  // 미구조화 공고를 상한·지역과 무관하게** 큐에 걸기 때문이다(아래 `--max-structure`
  // 주석). 2026-09-17 실측에서 상한 40 으로 돌렸는데 169건이 걸렸다.
  //
  // **금액은 찍지 않는다.** 단가를 여기 박으면 `structuring_model` 이 바뀌는 날
  // 조용히 틀려진다 — 이 경고가 방금 고친 거짓말과 같은 종류가 된다. 수집기는
  // 어느 모델이 쓰이는지 모르므로 **건수만 말하고 단가는 비용표로 넘긴다.**
  if (queued > MAX_STRUCTURE) {
    console.warn(`⚠ 구조화 ${queued}건 — 상한(${MAX_STRUCTURE})보다 많습니다.`);
    console.warn('   상한은 새로 본문을 받는 것에만 걸립니다. 본문을 이미 가진');
    console.warn('   미구조화 공고는 1단계에서 상한·지역과 무관하게 재투입됩니다.');
    console.warn(`   과금은 이 ${queued}건에 비례합니다 — 단가는 README 비용표를 보세요.`);
  }
}

/** 상한(200건)에 맞춰 나눠 보내고 합계를 낸다. */
async function pushAll(settings, items, collectedAt) {
  const totals = { received: 0, created: 0, updated: 0, unchanged: 0, queued: 0, needBody: [] };
  for (let at = 0; at < items.length; at += BATCH_SIZE) {
    const result = await push(settings, items.slice(at, at + BATCH_SIZE), collectedAt);
    for (const key of ['received', 'created', 'updated', 'unchanged', 'queued']) {
      totals[key] += result[key] ?? 0;
    }
    totals.needBody.push(...(result.needBody ?? []));
  }
  return totals;
}

// import 만 해서는 아무 일도 일어나지 않는다. 테스트가 extractBody 를 쓸 수 있어야 한다.
const runDirectly = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (runDirectly) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
