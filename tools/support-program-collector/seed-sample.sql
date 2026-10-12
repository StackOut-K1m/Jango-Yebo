-- 지원 제도(P1-15) 화면 개발용 샘플 공고 — **LLM 호출 0회 · $0**
--
-- ## 왜 있나
--
-- 수집 cron 이 서버에 붙기 전까지 공고가 0건이라 프론트가 볼 데이터가 없다
-- (S15P21C105-227). 그렇다고 `npm run collect:programs` 를 돌리라고 하기엔 무겁다 —
-- 기업마당 API 키·백엔드·Postgres·**AI 워커**가 다 떠 있어야 하고, 구조화는 공고당
-- 약 $0.0066 씩 **실제로 돈이 나간다.**
--
-- 이 파일은 **Postgres 만 있으면 된다.**
--
--   docker exec -i jango-pg-local psql -U jango -d jango \
--     -f - < tools/support-program-collector/seed-sample.sql
--
-- 여러 번 돌려도 안전하다 — `uk_sp_source` 로 upsert 한다.
--
-- ## 이 파일을 Flyway 마이그레이션으로 옮기지 마라
--
-- 운영 DB 에 샘플 공고가 들어가면 **사장님에게 가짜 지원금이 보인다.** 손으로 부르는
-- 파일로 둔 이유가 그것이다. 시연 서버에도 넣지 않는다.
--
-- ## 지울 때
--
--   DELETE FROM core.support_program WHERE source_name = 'SAMPLE';   -- 합성분만
--
-- 실수집 공고는 `BIZINFO` 라 위 문장으로는 안 지워진다. 1부(실데이터)까지 지우려면
-- `source_name IN ('BIZINFO', 'SAMPLE')` 로 하되 **로컬에서만** 해라.

BEGIN;

-- ════════════════════════════════════════════════════════════════════════════
--  1부 · 실데이터 — 2026-09-17 기업마당 수집 + Haiku 구조화 결과 그대로
-- ════════════════════════════════════════════════════════════════════════════
--
-- **손대지 않았다.** 셋 다 `expected_disbursement_days` 가 비어 있고
-- `requiredDocuments` 도 0건인데 **그게 실측상 「대부분의 경우」다** — 공고 32건 중
-- 지급 시기를 적은 것은 1건(3%)뿐이었다.
--
-- 화면은 이 모양을 **먼저** 다룰 수 있어야 한다. 「공백 전 도착 가능」 배지를 기본
-- false 로 그리면 말한 적 없는 것을 단정하게 된다(구현현황 3.40).
--
-- 두 번째·세 번째의 `eligibility` 에는 `"maxAnnualRevenue": null` 처럼 **키는 있고
-- 값이 null** 인 칸이 있다. 17.2 는 이 JSONB 를 그대로 내보내므로 화면도 그 모양을
-- 그대로 받는다.
--
-- 마감일이 2026-10-06/07 로 박혀 있다. 그 날짜가 지나면 기본 목록에서 빠지므로
-- 그때는 2부의 `CURRENT_DATE + n` 짜리를 쓰거나 날짜만 밀어서 쓴다.

INSERT INTO core.support_program
    (source_name, external_id, title, organizer, source_url,
     support_type, status, application_start_date, application_end_date,
     expected_disbursement_days, eligibility, raw_summary)
VALUES
    ('BIZINFO', 'PBLN_000000000126547',
     '[전북] 군산시 2026년 4차 우수농산물 공동상표 포장재 디자인 지원사업 신청 공고',
     '기초자치단체',
     'https://www.bizinfo.go.kr/sii/siia/selectSIIA200Detail.do?pblancId=PBLN_000000000126547',
     'SUBSIDY', 'OPEN', '2026-09-15', '2026-10-06', NULL,
     '{"regions": ["전북"], "industries": ["전 업종"]}'::jsonb,
     '군산시 로컬푸드 지역인증 및 공동상표 「새들군산」 사용을 유도하여 관내 농산물에 대한 소비자 신뢰도를 높이고 농업인의 포장재 제작 비용 부담을 경감하기 위해 다음과 같이 공고하오니 사업희망자께서는 사업을 신청하여 주시기 바랍니다. ☞ 군산시 로컬푸드 인증을 받았거나 농특산물 공동상표 「새들군산」을 사용 중인 개인, 개인사업자, 작목반, 법인 ☞ 포장재, 동판, 완충재, 포장재 부착용 라벨지 등 제작 비용 지원 - 개인(농가, 개인사업자) 최대 2,500천원, 단체(작목반, 법인) 최대 7,500천원 지원'),
    ('BIZINFO', 'PBLN_000000000126546',
     '[전북] 군산시 2026년 2차 우수농산물 공동 포장재 디자인 지원사업 신청 공고',
     '기초자치단체',
     'https://www.bizinfo.go.kr/sii/siia/selectSIIA200Detail.do?pblancId=PBLN_000000000126546',
     'SUBSIDY', 'OPEN', '2026-09-15', '2026-10-06', NULL,
     '{"regions": ["군산시"], "excludes": [], "industries": [], "maxAnnualRevenue": null, "requiredDocuments": [], "maxYearsInBusiness": null, "minYearsInBusiness": null, "uncertainConditions": ["지원 대상: 관내 생산 농산물·가공식품의 상표 또는 포장재 디자인을 개발(개선)을 희망하는 농업인, 개인사업자, 작목반, 농업법인", "구체적 자격 조건(영농 경력, 업력 등)은 첨부 공고문 참조"]}'::jsonb,
     '군산시 우수농산물ㆍ가공식품의 개성 있는 상표를 개발하여 특색을 강조하고 공동상표와 로컬푸드에 대한 관심도를 제고하고자 다음과 같이 공고하오니 사업 희망자께서는 사업을 신청하여 주시기 바랍니다. ☞ 관내 생산 농산물ㆍ가공식품의 상표 또는 포장재 디자인을 개발(개선)을 희망하는 농업인, 개인사업자, 작목반, 농업법인 ☞ 개소당 최대 3,600천원 지원 - 농산물ㆍ가공식품의 상표 또는 포장재 디자인 개발 및 개선 비용 지원'),
    ('BIZINFO', 'PBLN_000000000126545',
     '2027년 수출컨소시엄 사업 주관단체 및 과제 모집 공고',
     '중소기업중앙회',
     'https://www.bizinfo.go.kr/sii/siia/selectSIIA200Detail.do?pblancId=PBLN_000000000126545',
     'SUBSIDY', 'OPEN', '2026-09-16', '2026-10-07', NULL,
     '{"regions": [], "excludes": [], "industries": [], "maxAnnualRevenue": null, "requiredDocuments": [], "maxYearsInBusiness": null, "minYearsInBusiness": null, "uncertainConditions": ["본문에 지원 자격 조건이 구체적으로 기술되지 않음. 첨부파일(2027년_수출컨소시엄_사업_주관단체_및_과제_모집_공고.hwpx) 참조 필요", "지원대상: 업종별 협·단체, 수출유관기관, 민간전문기업 등", "중소기업 간 업종별 컨소시엄을 구성하여 공동 판로개척 과제(해외전시회, 수출상담회 등)를 수행하는 주관단체 및 과제 모집"]}'::jsonb,
     '중소벤처기업의 해외시장 개척 활동 지원을 위한 「수출컨소시엄 사업」 (일반) 주관단체 및 과제 모집을 다음과 같이 공고합니다. ☞ 업종별 협ㆍ단체, 수출유관기관, 민간전문기업 등 ☞ 중소기업 간 업종별 컨소시엄을 구성하여 공동 판로개척 과제(해외전시회, 수출상담회 등)를 수행하는데 소요되는 공동경비 지원'),

-- ════════════════════════════════════════════════════════════════════════════
--  2부 · 합성 fixture — **실데이터가 아니다**
-- ════════════════════════════════════════════════════════════════════════════
--
-- 화면이 다뤄야 하는데 위 3건에 없는 경우를 채운다. 출처를 `SAMPLE` 로 두어
-- 실수집 공고(`BIZINFO`)와 한눈에 갈린다.
--
--   SELECT source_name, count(*) FROM core.support_program GROUP BY 1;
--
-- **시연에 쓰지 마라.** 존재하지 않는 지원금이다. 기관명에 「샘플」을 붙여 둔 것도
-- 화면에 실수로 떠도 바로 보이게 하려는 것이다.

    -- 전국·전 업종 — 프로필이 비어도 1.0 이 나오는 경로(S15P21C105-240)
    ('SAMPLE', 'SAMPLE-001',
     '[샘플] 2026년 전국 소상공인 경영안정 바우처',
     '샘플중소벤처기업부', 'https://example.test/sample/001',
     'VOUCHER', 'OPEN', CURRENT_DATE - 10, CURRENT_DATE + 45, NULL,
     '{"regions": ["전국"], "industries": ["전 업종"], "excludes": [],
       "requiredDocuments": []}'::jsonb,
     '전국 소상공인을 대상으로 경영안정 바우처를 지원한다. (샘플 데이터)'),

    -- 지급 시기가 있는 것 — canArriveBeforeGap·timingNote 가 실제로 채워진다.
    -- 실데이터에서는 3% 만 이렇다. 배지의 「가능」 상태를 보려면 이걸 쓴다.
    ('SAMPLE', 'SAMPLE-002',
     '[샘플] 광주광역시 소상공인 긴급 경영안정자금 (즉시 집행)',
     '샘플광주광역시', 'https://example.test/sample/002',
     'FUNDING', 'OPEN', CURRENT_DATE - 5, CURRENT_DATE + 30, 3,
     '{"regions": ["광주"], "industries": ["요식업 포함 전 업종"],
       "maxAnnualRevenue": 300000000, "minYearsInBusiness": 1,
       "excludes": ["휴·폐업자", "국세 체납자"], "requiredDocuments": []}'::jsonb,
     '신청 후 약 3영업일 내 집행한다. (샘플 데이터)'),

    -- 제출 서류가 있는 것 — 17.2 에 서류가 붙고 17.4 사전이 발급 링크·수수료를
    -- 채운다. 워커는 issueUrl·fee 를 **일부러 비워** 보내고 사전이 채운다(구현현황 3.38).
    -- 임대차계약서는 발급받는 게 아니라 갖고 있는 서류라 issueUrl 이 끝까지 빈다.
    ('SAMPLE', 'SAMPLE-003',
     '[샘플] 전통시장 점포 환경개선 지원사업',
     '샘플소상공인시장진흥공단', 'https://example.test/sample/003',
     'SUBSIDY', 'OPEN', CURRENT_DATE - 3, CURRENT_DATE + 21, 30,
     '{"regions": ["광주", "전남"], "industries": ["도소매업", "요식업"],
       "maxAnnualRevenue": 100000000, "excludes": [],
       "requiredDocuments": [
         {"documentName": "사업자등록증명", "issuer": "홈택스", "method": "온라인 즉시 발급"},
         {"documentName": "부가가치세과세표준증명", "issuer": "홈택스", "method": "온라인 즉시 발급"},
         {"documentName": "임대차계약서 사본", "issuer": null, "method": "본인 보관분 제출"}
       ]}'::jsonb,
     '점포 간판·인테리어 개선 비용의 일부를 지원한다. (샘플 데이터)'),

    -- 확인 필요 없음 — uncertainConditions 가 없으면 needsVerification = false 다.
    -- 실데이터 3건 중 둘이 true 라 false 경로를 보려면 이게 필요하다.
    ('SAMPLE', 'SAMPLE-004',
     '[샘플] 청년 소상공인 창업 초기 임차료 지원',
     '샘플광주광역시', 'https://example.test/sample/004',
     'SUBSIDY', 'OPEN', CURRENT_DATE - 1, CURRENT_DATE + 60, 14,
     '{"regions": ["광주"], "industries": ["전 업종"],
       "maxYearsInBusiness": 3, "excludes": [], "requiredDocuments": []}'::jsonb,
     '업력 3년 이하 청년 창업자의 임차료를 지원한다. (샘플 데이터)'),

    -- 마감 임박(D-3) — 기본 정렬이 DEADLINE 이라 목록 맨 위에 온다
    ('SAMPLE', 'SAMPLE-005',
     '[샘플] 마감 임박 — 소상공인 디지털 전환 바우처',
     '샘플중소벤처기업부', 'https://example.test/sample/005',
     'VOUCHER', 'OPEN', CURRENT_DATE - 30, CURRENT_DATE + 3, NULL,
     '{"regions": ["전국"], "industries": ["전 업종"], "excludes": [],
       "uncertainConditions": ["온라인 판매 실적 요건의 산정 기준"],
       "requiredDocuments": []}'::jsonb,
     '온라인 판매 전환 비용을 바우처로 지원한다. (샘플 데이터)'),

    -- 금리가 있는 융자 — interestRate 표시 경로. 실데이터 3건은 전부 SUBSIDY 다
    ('SAMPLE', 'SAMPLE-006',
     '[샘플] 소상공인 정책자금 일반경영안정자금 (융자)',
     '샘플소상공인시장진흥공단', 'https://example.test/sample/006',
     'FUNDING', 'OPEN', CURRENT_DATE - 20, CURRENT_DATE + 90, 45,
     '{"regions": ["전국"], "industries": ["전 업종"],
       "maxAnnualRevenue": 1000000000, "minYearsInBusiness": 1,
       "excludes": ["국세 체납자"], "requiredDocuments": []}'::jsonb,
     '운전자금을 연 3.51% 로 융자한다. (샘플 데이터)'),

    -- 마감된 공고 — 기본 목록에서 빠지고 includeClosed=true 에서만 나온다
    ('SAMPLE', 'SAMPLE-007',
     '[샘플] 마감된 공고 — 2026년 상반기 판로개척 지원',
     '샘플광주광역시', 'https://example.test/sample/007',
     'SUBSIDY', 'CLOSED', CURRENT_DATE - 120, CURRENT_DATE - 30, NULL,
     '{"regions": ["광주"], "industries": ["전 업종"], "excludes": [],
       "requiredDocuments": []}'::jsonb,
     '상반기에 마감된 공고다. (샘플 데이터)'),

    -- 구조화 실패분 — eligibility 가 비면 **어느 API 에도 안 나온다**
    -- (17.1 검색이 eligibility <> '{}' 로 거른다). 목록이 비어 보일 때
    -- 「적재는 됐는데 구조화가 안 된 것」을 구분하려면 이 행이 있어야 한다.
    ('SAMPLE', 'SAMPLE-008',
     '[샘플] 아직 구조화되지 않은 공고 (목록에 안 나오는 게 정상)',
     '샘플기관', 'https://example.test/sample/008',
     NULL, 'OPEN', NULL, CURRENT_DATE + 30, NULL,
     '{}'::jsonb,
     NULL)

ON CONFLICT (source_name, external_id) DO UPDATE SET
    title                      = EXCLUDED.title,
    organizer                  = EXCLUDED.organizer,
    source_url                 = EXCLUDED.source_url,
    support_type               = EXCLUDED.support_type,
    status                     = EXCLUDED.status,
    application_start_date     = EXCLUDED.application_start_date,
    application_end_date       = EXCLUDED.application_end_date,
    expected_disbursement_days = EXCLUDED.expected_disbursement_days,
    eligibility                = EXCLUDED.eligibility,
    raw_summary                = EXCLUDED.raw_summary,
    updated_at                 = now();

-- 금리는 융자 하나에만 붙인다. 위 INSERT 의 열 목록을 늘리지 않으려고 따로 둔다.
UPDATE core.support_program
   SET interest_rate = 3.5100, max_amount = 70000000
 WHERE source_name = 'SAMPLE' AND external_id = 'SAMPLE-006';

-- 지원 한도도 몇 건에 넣어 둔다. 금액 포맷터(만원 단위 축약)를 볼 수 있어야 한다.
UPDATE core.support_program SET max_amount = 3000000
 WHERE source_name = 'SAMPLE' AND external_id IN ('SAMPLE-001', 'SAMPLE-004');
UPDATE core.support_program SET max_amount = 5000000
 WHERE source_name = 'SAMPLE' AND external_id IN ('SAMPLE-002', 'SAMPLE-003');

COMMIT;

-- 넣은 뒤 확인. structured 가 17.1 목록에 실제로 나오는 수다.
SELECT source_name,
       count(*)                                    AS total,
       count(*) FILTER (WHERE eligibility <> '{}') AS structured,
       count(*) FILTER (WHERE status = 'OPEN')     AS open
  FROM core.support_program
 GROUP BY source_name
 ORDER BY source_name;
