package com.jangoyebo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway 마이그레이션 검증.
 *
 * <p>P0 베이스라인(V1)이 실제 PostgreSQL 16에서 적용되는지, 시드(V2)가 들어가는지 확인한다.
 * DDL 오타·제약 충돌은 여기서 잡힌다 — 운영 배포는 부팅 시 Flyway가 돌므로
 * 마이그레이션이 깨지면 앱이 아예 뜨지 않는다.
 *
 * <p><b>Docker가 필요하다.</b> Testcontainers로 postgres:16-alpine 을 띄운다.
 */
@NeedsDocker
@SpringBootTest
class MigrationTests {

    /** P0 범위 20개. USER 는 예약어라 users 로 생성한다. */
    private static final List<String> CORE_TABLES = List.of(
            "users", "business", "account",
            // P1-08 에서 P0 로 승격(아키텍처 §11). forecast 가 읽지만 소유는 core 다 — V5
            "safety_balance_policy",
            "transaction_category", "transaction", "transaction_classification_history",
            "evidence", "transaction_evidence",
            "settlement", "settlement_transaction",
            "job", "alert");

    private static final List<String> FORECAST_TABLES = List.of(
            "recurring_pattern", "planned_transaction", "recurring_pattern_transaction",
            "forecast_run", "daily_balance_forecast", "forecast_setting",
            "scenario", "scenario_adjustment");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appliesAllMigrations() {
        // version 이 null 인 행은 Flyway 가 스키마(core·forecast)를 만든 기록이다.
        // 마이그레이션이 아니므로 제외한다.
        List<String> applied = jdbc.queryForList("""
                SELECT version FROM core.flyway_schema_history
                 WHERE success = true AND version IS NOT NULL
                 ORDER BY installed_rank
                """, String.class);

        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23",
                "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37");
    }

    /**
     * What-if 파라미터는 <b>8종</b>이다 (API 11.1).
     *
     * <p>{@code MATERIAL_COST_CHANGE} 는 ERD v1.16 에서 빠졌다({@code V29}). P1-06 시세가
     * 사라진 뒤 남은 {@code rate} 만으로는 <b>무엇에 곱하는지가 명세에 없었고</b>, 새로
     * 정의해도 패턴으로 안 잡힌 재료비가 잔여 흐름 축에 섞여 분류로 골라낼 수 없다.
     *
     * <p>제약을 스키마 쪽에서 굳혀 둔다. 시나리오(11장) 구현이 선 뒤로는 서비스 쪽
     * {@code ScenarioAdjustmentType} 도 같은 8종을 들고 있으므로, 이 테스트는 <b>둘이
     * 갈라지는 것</b>을 잡는 자리가 됐다 — enum 에 값을 더하고 제약을 안 고치면 INSERT 가
     * 터지고, 반대면 스키마가 받아 주는 값을 자바가 모른다.
     */
    @Test
    void scenarioAdjustmentTypeHasEightKinds() {
        String def = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_sa_type'
                """, String.class);

        assertThat(def)
                .as("재료비 변화는 범위에서 빠졌다 (SRS v1.11)")
                .doesNotContain("MATERIAL_COST_CHANGE");
        assertThat(def)
                .as("나머지 8종은 그대로다 — 특히 대체재인 상품 매출 변화")
                .contains("SETTLEMENT_DELAY")
                .contains("PRODUCT_SALES_CHANGE")
                .contains("LOAN_EXECUTION");
    }

    /**
     * V32 — 저장한 시나리오가 예정 거래를 붙잡지 않는다.
     *
     * <p>여기서 보는 것은 <b>삭제가 성공하는가</b>다. {@code V1} 은 {@code fk_sa_planned} 에
     * {@code ON DELETE} 절을 두지 않아, 시나리오가 예정 거래를 가리키면 그 예정 거래를
     * 지우는 모든 경로(9.4 삭제·패턴 회차 재생성·달력 변경·계좌 purge)가 외래키에 막혀
     * {@code 500} 이 된다. 제약 정의만 보지 않고 실제로 지워 본다 — 정의가 맞아도 인덱스나
     * 트리거가 끼면 결과가 달라질 수 있고, 여기서 확인하려는 것은 정의가 아니라 행동이다.
     *
     * <p>{@code CASCADE} 가 아니라 {@code SET NULL} 이므로 <b>조정 행은 남는다.</b> 남은
     * 행이 있어야 조회가 「대상이 삭제되어 반영하지 않았다」를 말할 수 있다.
     */
    @Test
    void scenarioAdjustmentTargetIsNulledWhenPlannedTransactionIsDeleted() {
        long businessId = insertBusinessWithOwner();
        long accountId = insertAccount(businessId);
        long plannedId = insertPlanned(businessId, accountId, null);
        Long userId = jdbc.queryForObject(
                "SELECT owner_user_id FROM core.business WHERE id = ?", Long.class, businessId);
        Long scenarioId = jdbc.queryForObject("""
                INSERT INTO forecast.scenario (business_id, created_by, name, days)
                VALUES (?, ?, '지출일 변경', 30) RETURNING id
                """, Long.class, businessId, userId);
        jdbc.update("""
                INSERT INTO forecast.scenario_adjustment
                       (scenario_id, adjustment_type, target_planned_transaction_id, changed_date)
                VALUES (?, 'EXPENSE_DATE_SHIFT', ?, current_date + 5)
                """, scenarioId, plannedId);

        assertThatNoException()
                .as("시나리오는 가정이지 원장이 아니다 — 예정 거래 삭제를 막지 않는다")
                .isThrownBy(() -> jdbc.update(
                        "DELETE FROM forecast.planned_transaction WHERE id = ?", plannedId));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT target_planned_transaction_id FROM forecast.scenario_adjustment WHERE scenario_id = ?",
                scenarioId);
        assertThat(row.get("target_planned_transaction_id"))
                .as("조정 행은 남고 대상만 비워진다 — 남아야 경고를 만들 수 있다")
                .isNull();
    }

    /**
     * V21 — 지원사업 공고 (P1-15).
     *
     * <p>여기서 보는 것은 <b>수집기가 실제로 보낼 모양이 들어가는가</b>다. 기업마당 응답에는
     * 자격·금액·종류가 아예 없고(그건 구조화의 결과다) <b>신청 기간도 선택 항목</b>이라
     * 상시모집 공고는 비어서 온다. ERD 를 곧이곧대로 NOT NULL 로 옮겼으면 첫 수집에서
     * 전부 반려됐다.
     */
    @Test
    void supportProgramAcceptsWhatTheCollectorActuallySends() {
        assertThatNoException().isThrownBy(() -> jdbc.update("""
                INSERT INTO core.support_program (source_name, external_id, title, source_url)
                VALUES ('BIZINFO', ?, '2026년 광주 소상공인 임대료 특별지원',
                        'https://www.bizinfo.go.kr/sii/siia/selectSIIA200Detail.do?pblancId=PBLN_1')
                """, "PBLN_" + System.nanoTime()));

        // 같은 출처의 같은 공고는 두 번 들어가지 않는다 — 매일 전량을 받아 upsert 한다.
        String duplicated = "PBLN_" + System.nanoTime();
        jdbc.update("""
                INSERT INTO core.support_program (source_name, external_id, title, source_url)
                VALUES ('BIZINFO', ?, '중복', 'https://example.test/1')
                """, duplicated);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO core.support_program (source_name, external_id, title, source_url)
                VALUES ('BIZINFO', ?, '중복', 'https://example.test/1')
                """, duplicated))
                .hasMessageContaining("uk_sp_source");

        // 다른 출처가 같은 공고를 자기 ID 로 실어도 막히지 않는다(기업마당·중소벤처24 중복).
        assertThatNoException().isThrownBy(() -> jdbc.update("""
                INSERT INTO core.support_program (source_name, external_id, title, source_url)
                VALUES ('SMES', ?, '중복', 'https://example.test/1')
                """, duplicated));

        // 뒤집힌 기간은 막는다. 한쪽만 없는 것은 막지 않는다.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO core.support_program
                    (source_name, external_id, title, source_url,
                     application_start_date, application_end_date)
                VALUES ('BIZINFO', ?, '뒤집힘', 'https://example.test/2',
                        DATE '2026-09-30', DATE '2026-09-01')
                """, "PBLN_" + System.nanoTime()))
                .hasMessageContaining("ck_sp_period");
    }

    /** 공고는 사업장 공용 데이터다 — 사업장 컬럼이 생기면 수집·구조화 비용이 사업장 수만큼 는다. */
    @Test
    void supportProgramIsNotOwnedByABusiness() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'core' AND table_name = 'support_program'
                   AND column_name = 'business_id'
                """, String.class))
                .isEmpty();
    }

    /**
     * V23 대출·신용. <b>기본값 두 개가 이 기능의 안전장치다.</b>
     *
     * <p>둘 다 틀려도 앱은 잘 뜨고 테스트도 통과한다 — 대신 사장님 화면에서 조용히 사고가 난다.
     * 그래서 스키마 쪽 회귀선으로 못박는다.
     */
    @Test
    void loanSchemaKeepsItsTwoSafetyDefaults() {
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname = 'core'
                   AND tablename IN ('loan', 'loan_repayment', 'loan_product', 'credit_snapshot')
                """, String.class))
                .containsExactlyInAnyOrder("loan", "loan_repayment", "loan_product", "credit_snapshot");

        assertThat(columnDefault("loan", "repayment_cycle"))
                .as("금융망 대출은 개설 다음날부터 매일 빠진다. MONTHLY 로 두면 그날 절벽이 생기고 "
                        + "매일 판정되는 자동이체 실패를 못 맞춘다")
                .contains("DAILY");

        assertThat(columnDefault("loan_product", "recommendable"))
                .as("금융망에 대출 상품 삭제 API 가 없고 팀원 누구나 상품을 만들 수 있다. "
                        + "기본이 켜짐이면 남의 테스트 상품이 「이 대출을 받으세요」로 나간다")
                .isEqualTo("false");

        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_lr_due'
                """, String.class))
                .as("수동 등록 대출은 installment_no 가 null 이다. 기본 UNIQUE 는 null 을 서로 "
                        + "다른 값으로 봐서 같은 날짜 회차가 몇 번이든 들어간다")
                .contains("NULLS NOT DISTINCT");

        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'core' AND table_name = 'loan_repayment'
                """, String.class))
                .as("금융망은 회차 금액(paymentBalance) 하나만 준다 — 원금·이자로 쪼개면 "
                        + "우리가 지어낸 비율이 저장된다")
                .contains("amount")
                .doesNotContain("principal_amount", "interest_amount");
    }

    private String columnDefault(String table, String column) {
        return jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                 WHERE table_schema = 'core' AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }

    /**
     * V34 카탈로그 시드. <b>위 테스트의 기본값 FALSE 와 짝이다.</b>
     *
     * <p>{@code LOAN_SYNC} 가 {@code recommendable} 을 갱신 대상에서 빼기 때문에 동기화만으로는
     * 추천 화면이 영영 빈다. 켜는 일을 사람 손(psql)에 맡기면 그 단계가 빠졌을 때
     * <b>오류 없이 조용히 빈 화면</b>이 된다 — 로그에도 안 남는다. 그래서 우리가 등록한
     * 카탈로그만큼은 마이그레이션으로 켜 둔다.
     *
     * <p>행 수와 켜진 수가 같은지를 함께 보는 것이 요점이다. 마이그레이션이 카탈로그
     * <b>바깥</b>의 상품까지 건드리면 여기서 걸린다.
     */
    @Test
    void seedsCatalogLoanProductsAsRecommendable() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.loan_product
                """, Integer.class))
                .as("scripts/seed/loan-products.json 의 12건만 심는다")
                .isEqualTo(12);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.loan_product WHERE recommendable
                """, Integer.class))
                .as("심은 것은 전부 켜진 채여야 한다 — 꺼진 채로 심으면 아무 일도 안 한 것과 같다")
                .isEqualTo(12);

        assertThat(jdbc.queryForList("""
                SELECT DISTINCT min_grade FROM core.loan_product WHERE recommendable
                """, String.class))
                .as("「등급이 오르면 더 싼 상품이 열린다」(16.2)를 화면에서 말하려면 A~E 가 다 있어야 한다")
                .containsExactlyInAnyOrder("A", "B", "C", "D", "E");
    }

    /**
     * V8 이 만든 매출 인쇄물 테이블. <b>합의 ①·②가 여기서 지켜지는지 본다.</b>
     *
     * <p>ERD 를 그대로 따랐으면 {@code gross_amount NOT NULL} 이 <b>58mm 인쇄물을 전부
     * 반려했다</b> — 실측 90표본 중 30표본·행 33.3% 가 그 열이 없다. `quantity` 도 같다.
     * 스키마 쪽 회귀선이라 서비스 테스트와 따로 둔다.
     */
    @Test
    void productSalesAcceptsPrintoutsWithoutAGrossColumn() {
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname = 'core'
                   AND tablename IN ('product_sales_extraction', 'product_sales_period')
                """, String.class))
                .containsExactlyInAnyOrder("product_sales_extraction", "product_sales_period");

        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'core' AND table_name = 'product_sales_period'
                   AND column_name IN ('gross_amount', 'quantity') AND is_nullable = 'NO'
                """, String.class))
                .as("58mm 감열지에는 총매출·수량 열이 없다. NN 을 걸면 정상 인쇄물이 반려된다")
                .isEmpty();

        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_ps_period_share_basis'
                """, String.class))
                .as("이 테이블의 존재 이유가 구성비다 — 분모가 될 값이 하나는 있어야 한다")
                .contains("billed_amount")
                .contains("gross_amount");

        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_ps_extraction_pipeline'
                """, String.class))
                .as("STUB 이 빠지면 API 키 없이 워커를 잇는 경로가 막힌다")
                .contains("STUB");
    }

    /** 실측이 확인한 것 — 58mm 행(결제금액만 있는 행)이 실제로 들어간다. */
    @Test
    void insertsA58mmRowThatHasOnlyBilledAmount() {
        long businessId = insertBusinessWithOwner();
        jdbc.update("""
                INSERT INTO core.product_sales_period
                    (business_id, period_start, period_end, product_name, product_key,
                     billed_amount, source_type, checksum_status)
                VALUES (?, DATE '2026-08-01', DATE '2026-08-31', '카페라떼', '카페라떼',
                        161950, 'OCR', 'MISMATCHED')
                """, businessId);

        assertThat(jdbc.queryForObject("""
                SELECT gross_amount IS NULL AND quantity IS NULL
                  FROM core.product_sales_period WHERE business_id = ?
                """, Boolean.class, businessId)).isTrue();

        // 반대로 분모가 될 값이 하나도 없으면 거부돼야 한다.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO core.product_sales_period
                    (business_id, period_start, period_end, product_name, product_key,
                     quantity, source_type, checksum_status)
                VALUES (?, DATE '2026-08-01', DATE '2026-08-31', '이름만', '이름만',
                        3, 'OCR', 'UNAVAILABLE')
                """, businessId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * V6 가 만든 증빙 추출 테이블. <b>합의 ⑤ 세 건이 여기서 지켜지는지 본다.</b>
     *
     * <p>워커는 {@code pipeline=STUB} 을 쓰고 {@code modelVersion} 을 항상 null 로 보내는데,
     * ERD 를 그대로 따랐으면 CHECK 위반과 NOT NULL 위반으로 <b>모든 INSERT 가 깨졌다.</b>
     * 스키마 쪽 회귀선이라 엔티티 테스트와 따로 둔다 — 마이그레이션을 되돌리면 여기가 먼저 운다.
     */
    @Test
    void evidenceExtractionAcceptsWhatTheWorkerActuallySends() {
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables
                 WHERE schemaname = 'core'
                   AND tablename IN ('item', 'evidence_extraction', 'evidence_item',
                                     'evidence_correction')
                """, String.class))
                .containsExactlyInAnyOrder("item", "evidence_extraction", "evidence_item",
                        "evidence_correction");

        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_extraction_pipeline'
                """, String.class))
                .as("STUB 이 빠지면 백엔드·워커를 API 키 없이 잇는 유일한 경로가 막힌다")
                .contains("STUB");

        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_schema = 'core' AND table_name = 'evidence_extraction'
                   AND column_name = 'model_version'
                """, String.class))
                .as("워커는 항상 null 을 보낸다 — ERD 의 NN 을 따르면 전부 깨진다")
                .isEqualTo("YES");

        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'core' AND table_name = 'evidence_extraction'
                   AND column_name = 'prompt_version'
                """, String.class))
                .as("재현성의 실제 키다. 프롬프트 v1→v2 에서 상호명 정확도가 80.8%→97.9% 로 움직였다")
                .hasSize(1);
    }

    /**
     * 암호화 컬럼이 실제 암호문을 담을 수 있는지 확인한다.
     *
     * <p><b>일반화된 회귀선이다.</b> {@code BUSINESS.registration_number} 가 {@code VARCHAR(64)} 였고
     * 암호문은 hex 88자라 어떤 값도 들어가지 않았다(V4). 평문 크기로 컬럼을 잡는 실수는
     * 다음에 암호화 컬럼을 추가할 때 그대로 반복되므로, 개별 컬럼이 아니라 <b>목록</b>을 검사한다.
     *
     * <p>암호화 컬럼을 추가하면 아래 표에 한 줄을 넣는다. 넣지 않으면 이 테스트는 통과하지만
     * 그건 검사 대상이 아니어서지 안전해서가 아니다.
     *
     * <p>길이 공식: {@code hex(IV 16 + 평문 + GCM 태그 16)} = {@code 2 × (평문 + 32)}.
     * 가장 긴 대상은 금융망 {@code userKey}(UUID 36자)라 100자다.
     */
    @Test
    void encryptedColumnsFitCipherText() {
        // 컬럼 -> 그 컬럼에 들어갈 가장 긴 평문
        Map<String, String> targets = Map.of(
                "core.business.registration_number", "123-45-67890",
                "core.account.account_number_encrypted", "1101234567890123456789012345678901",
                "core.users.fin_user_key", "00000000-0000-0000-0000-000000000000");

        targets.forEach((column, plainText) -> {
            int cipherLength = 2 * (plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32);
            Integer declared = jdbc.queryForObject("""
                    SELECT character_maximum_length FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = ? AND column_name = ?
                    """, Integer.class, column.split("\\.")[0], column.split("\\.")[1], column.split("\\.")[2]);

            assertThat(declared)
                    .as("%s — 평문 %d자의 암호문은 %d자다", column, plainText.length(), cipherLength)
                    .isNotNull()
                    .isGreaterThanOrEqualTo(cipherLength);
        });
    }

    /** 실패한 마이그레이션이 남아 있으면 안 된다. */
    @Test
    void hasNoFailedMigrations() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM core.flyway_schema_history WHERE success = false", Integer.class);

        assertThat(failed).isZero();
    }

    @Test
    void createsAllP0Tables() {
        assertThat(tablesIn("core")).containsAll(CORE_TABLES);
        assertThat(tablesIn("forecast")).containsAll(FORECAST_TABLES);

        // P0 범위는 21개다(V1 19 + V5 안전선 1 + V7 예측 옵션 1). 늘어나면 P1 테이블이
        // 섞여 들어간 것이다 — P1 은 해당 기능 착수 시 별도 마이그레이션으로 넣는다.
        assertThat(CORE_TABLES.size() + FORECAST_TABLES.size()).isEqualTo(21);
    }

    @Test
    void seedsTransactionCategories() {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM core.transaction_category", Integer.class);
        Integer deposits = jdbc.queryForObject(
                "SELECT count(*) FROM core.transaction_category WHERE flow_type = 'DEPOSIT'", Integer.class);

        // API 8.1 — 입금 7 + 출금 16
        assertThat(total).isEqualTo(23);
        assertThat(deposits).isEqualTo(7);
    }

    /**
     * 중복 수집 방지가 실제로 걸리는지 확인한다. 인수 조건
     * "CSV 거래를 업로드하면 중복 없이 계좌·사업장에 귀속된다"의 DB 레벨 보증이다.
     */
    @Test
    void rejectsDuplicateExternalTransaction() {
        long businessId = insertBusinessWithOwner();
        long accountId = insertAccount(businessId);

        insertTransaction(businessId, accountId, "TX-1");

        assertThatThrownBy(() -> insertTransaction(businessId, accountId, "TX-1"))
                .hasMessageContaining("uk_tx_external");
    }

    /**
     * 명세 예시 `engine-1.2/timesfm-2.5-200m`(27자)가 실제로 들어가는지 확인한다.
     *
     * <p>이 컬럼은 `VARCHAR(20)` 이었다. 예측 실행(P0-07)을 붙이는 첫날 터졌을 결함이고,
     * 길이 변경은 배포 후 금지 목록(타입 변경)에 해당해 되돌리기 비싸다.
     */
    @Test
    void acceptsSpecifiedModelVersion() {
        long businessId = insertBusinessWithOwner();

        assertThatNoException().isThrownBy(
                () -> insertForecastRun(businessId, "engine-1.2/timesfm-2.5-200m"));
    }

    /** ERD v1.9 잔여 흐름 축. 모델을 쓰지 않은 실행은 0 이어야 한다. */
    @Test
    void residualColumnsDefaultToZero() {
        long runId = insertForecastRun(insertBusinessWithOwner(), "engine-1.2/none");

        jdbc.update("""
                INSERT INTO forecast.daily_balance_forecast
                    (forecast_run_id, forecast_date, balance_min, balance_expected, balance_max, confidence)
                VALUES (?, current_date, 100, 200, 300, 0.8)
                """, runId);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT residual_deposit, residual_withdrawal
                  FROM forecast.daily_balance_forecast WHERE forecast_run_id = ?
                """, runId);

        assertThat((BigDecimal) row.get("residual_deposit")).isEqualByComparingTo("0");
        assertThat((BigDecimal) row.get("residual_withdrawal")).isEqualByComparingTo("0");
    }

    /**
     * 카드 정산 cadence (설계노트 G-2, V5).
     *
     * <p>'영업일마다 D+n 입금'은 {@code WEEKLY}·{@code MONTHLY}·{@code INTERVAL} 어느 것으로도
     * 표현되지 않는다. 요식업 입금의 큰 축이라 <b>등록조차 되지 않으면 예측에서 통째로 빠진다.</b>
     */
    @Test
    void acceptsBusinessDailyCadence() {
        long businessId = insertBusinessWithOwner();

        assertThatNoException().isThrownBy(() -> jdbc.update("""
                INSERT INTO forecast.recurring_pattern
                       (business_id, name, flow_type, cadence_type,
                        expected_amount_min, expected_amount_max, confidence)
                VALUES (?, '카드 정산', 'DEPOSIT', 'BUSINESS_DAILY', 100000, 300000, 0.8)
                """, businessId));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO forecast.recurring_pattern
                       (business_id, name, flow_type, cadence_type,
                        expected_amount_min, expected_amount_max, confidence)
                VALUES (?, '오타', 'DEPOSIT', 'DAILY', 1, 2, 0.5)
                """, businessId))
                .hasMessageContaining("ck_rp_cadence");
    }

    /**
     * 안전선은 사업장당 1행이다(ERD §1.6, V5). 강제 수단이 UNIQUE 제약뿐이라 확인한다.
     *
     * <p>"설정 안 함"은 {@code amount = 0} 이 아니라 <b>행이 없는 것</b>으로 표현한다 —
     * API 12.1 이 미설정 시 {@code amount: null} 이고 위험 등급을 0원 기준으로만 판정한다.
     */
    @Test
    void enforcesOneSafetyLinePerBusiness() {
        long businessId = insertBusinessWithOwner();
        jdbc.update("INSERT INTO core.safety_balance_policy (business_id, amount) VALUES (?, 2000000)",
                businessId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO core.safety_balance_policy (business_id, amount) VALUES (?, 3000000)",
                businessId))
                .hasMessageContaining("uk_sbp_business");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO core.safety_balance_policy (business_id, amount, method) VALUES (?, 1, 'AUTO')",
                insertBusinessWithOwner()))
                .hasMessageContaining("ck_sbp_method");
    }

    /**
     * API 8.7 종합소득세 검토 상태. 기본값과 CHECK 이 함께 걸려야 값 집합이 실제로 고정된다.
     * 둘 다 배포 후에는 추가·변경이 금지되므로 여기서 굳힌다.
     */
    @Test
    void taxReviewStatusDefaultsAndIsConstrained() {
        long businessId = insertBusinessWithOwner();
        insertTransaction(businessId, insertAccount(businessId), "TX-TAX-1");

        String status = jdbc.queryForObject("""
                SELECT tax_review_status FROM core.transaction WHERE external_transaction_id = ?
                """, String.class, "TX-TAX-1");
        assertThat(status).isEqualTo("UNREVIEWED");

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE core.transaction SET tax_review_status = 'NOT_A_STATUS'
                 WHERE external_transaction_id = ?
                """, "TX-TAX-1"))
                .hasMessageContaining("ck_tx_tax_review");
    }

    /**
     * V3. 원시 INSERT 로 만든 행도 0 이어야 한다 — nullable 로 뒀다면 스위퍼가
     * 상한을 비교하는 지점에서 터진다.
     */
    @Test
    void jobAttemptCountDefaultsToZero() {
        long businessId = insertBusinessWithOwner();
        jdbc.update("INSERT INTO core.job (business_id, job_type) VALUES (?, 'ACCOUNT_SYNC')", businessId);

        Integer attempts = jdbc.queryForObject(
                "SELECT attempt_count FROM core.job WHERE business_id = ?", Integer.class, businessId);

        assertThat(attempts).isZero();
    }

    /**
     * V21 — 사업장이 없는 작업 (API 20.6 공고 구조화).
     *
     * <p>공고는 사업장 공용 데이터다. {@code business_id} 가 NOT NULL 이면 <b>아무 사업장이나
     * 골라 넣어야 하고, 그러면 그 사장님의 Job 목록에 남의 작업이 뜬다.</b>
     *
     * <p>FK 는 그대로다 — 비우는 것은 되지만 <b>없는 사업장을 가리키는 것은 여전히 막힌다.</b>
     */
    @Test
    void acceptsAGlobalJobWithNoBusiness() {
        assertThatNoException().isThrownBy(() -> jdbc.update(
                "INSERT INTO core.job (business_id, job_type) VALUES (NULL, 'PROGRAM_STRUCTURING')"));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO core.job (business_id, job_type) VALUES (?, 'PROGRAM_STRUCTURING')",
                -1L))
                .as("비울 수는 있어도 없는 사업장을 가리킬 수는 없다")
                .hasMessageContaining("fk_job_business");
    }

    /**
     * API 2.7의 9종. ERD §1.4는 PRODUCT_SALES_OCR 이 빠진 7종이라 API 를 기준으로 삼았다.
     *
     * <p>{@code PROGRAM_STRUCTURING} 은 V21 에서 늘었다 — <b>20.6 요청 본문이 {@code jobId} 를
     * 요구하는데 그 Job 을 만들 타입이 네 곳 모두에 없었다.</b>
     */
    @Test
    void acceptsAllSpecifiedJobTypes() {
        long businessId = insertBusinessWithOwner();
        List<String> types = List.of(
                "ACCOUNT_SYNC", "TRANSACTION_IMPORT", "TRANSACTION_CLASSIFICATION",
                "EVIDENCE_EXTRACTION", "EXPORT_GENERATION", "TAX_DRAFT_GENERATION",
                "PRODUCT_SALES_OCR", "LOAN_SYNC", "PROGRAM_STRUCTURING");

        types.forEach(type ->
                jdbc.update("INSERT INTO core.job (business_id, job_type) VALUES (?, ?)", businessId, type));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM core.job WHERE business_id = ?", Integer.class, businessId);
        assertThat(count).isEqualTo(types.size());
    }

    /** API 11.1의 9종. v1.8에서 PRODUCT_SALES_CHANGE 가 옛 MENU_PRICE_CHANGE 를 대체했다. */
    @Test
    void acceptsProductSalesChangeAdjustment() {
        long businessId = insertBusinessWithOwner();
        Long userId = jdbc.queryForObject(
                "SELECT owner_user_id FROM core.business WHERE id = ?", Long.class, businessId);
        Long scenarioId = jdbc.queryForObject("""
                INSERT INTO forecast.scenario (business_id, created_by, name, days)
                VALUES (?, ?, '상품 구성비 변경', 30) RETURNING id
                """, Long.class, businessId, userId);

        assertThatNoException().isThrownBy(() -> jdbc.update("""
                INSERT INTO forecast.scenario_adjustment (scenario_id, adjustment_type, percentage_value)
                VALUES (?, 'PRODUCT_SALES_CHANGE', 0.1)
                """, scenarioId));
    }

    /**
     * V14 — 예정 거래 자동 반영 (API 9.6).
     *
     * <p>여기서 보는 것은 <b>돈이 두 번 나가지 않는 근거</b> 둘이다. 자동 반영이 기본으로
     * 꺼져 있는가, 그리고 같은 패턴의 같은 회차가 두 번 기록될 수 없는가.
     */
    @Test
    void autoExecutionIsOffByDefaultAndOneExecutionPerPatternCycle() {
        long businessId = insertBusinessWithOwner();
        long accountId = insertAccount(businessId);

        assertThat(jdbc.queryForObject("""
                SELECT auto_execute_planned FROM core.account WHERE id = ?
                """, Boolean.class, accountId))
                .as("실사용자 계좌에 자동으로 켜지는 경로가 있으면 안 된다")
                .isFalse();

        Long patternId = jdbc.queryForObject("""
                INSERT INTO forecast.recurring_pattern
                       (business_id, account_id, name, flow_type, cadence_type,
                        expected_amount_min, expected_amount_max, confidence, status)
                VALUES (?, ?, '김영자', 'WITHDRAWAL', 'MONTHLY', 900000, 900000, 0.9, 'CONFIRMED')
                RETURNING id
                """, Long.class, businessId, accountId);

        long first = insertPlanned(businessId, accountId, patternId);
        long second = insertPlanned(businessId, accountId, patternId);

        assertThatNoException().isThrownBy(() -> insertExecution(businessId, accountId, patternId, first));

        // 달력 재생성이 같은 회차의 예정 거래를 새 id 로 다시 만들어도 돈은 한 번이다.
        assertThatThrownBy(() -> insertExecution(businessId, accountId, patternId, second))
                .hasMessageContaining("uk_pe_pattern_due");
    }

    private long insertPlanned(long businessId, long accountId, Long patternId) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                       (business_id, account_id, source_type, recurring_pattern_id, name,
                        expected_date, original_expected_date, flow_type,
                        amount_expected, amount_min, amount_max)
                VALUES (?, ?, 'PATTERN', ?, '김영자', current_date, current_date, 'WITHDRAWAL',
                        900000, 900000, 900000)
                RETURNING id
                """, Long.class, businessId, accountId, patternId);
    }

    private void insertExecution(long businessId, long accountId, Long patternId, long plannedId) {
        jdbc.update("""
                INSERT INTO forecast.planned_execution
                       (planned_transaction_id, business_id, account_id, recurring_pattern_id,
                        due_date, flow_type, amount, summary, status)
                VALUES (?, ?, ?, ?, current_date, 'WITHDRAWAL', 900000, '김영자', 'EXECUTED')
                """, plannedId, businessId, accountId, patternId);
    }

    // --- helpers -------------------------------------------------------------

    private List<String> tablesIn(String schema) {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, schema);
    }

    private long insertBusinessWithOwner() {
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '테스트') RETURNING id
                """, Long.class, "owner-" + System.nanoTime() + "@test.local");
        return jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '테스트 사업장') RETURNING id
                """, Long.class, userId);
    }

    private long insertAccount(long businessId) {
        return jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
    }

    private long insertForecastRun(long businessId, String modelVersion) {
        return jdbc.queryForObject("""
                INSERT INTO forecast.forecast_run
                    (business_id, base_at, start_date, end_date, model_version, opening_balances, basis)
                VALUES (?, now(), current_date, current_date + 30, ?, '[]'::jsonb, '{}'::jsonb)
                RETURNING id
                """, Long.class, businessId, modelVersion);
    }

    private void insertTransaction(long businessId, long accountId, String externalId) {
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type,
                     occurred_at, transaction_date, flow_type, amount)
                VALUES (?, ?, ?, 'FIN_API', now(), current_date, 'DEPOSIT', 10000)
                """, businessId, accountId, externalId);
    }
}
