package com.jangoyebo.core.report;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 월별 지출·수익 리포트 (API 15.3 · P1-19).
 *
 * <p><b>이 표가 「무엇에 돈을 썼나」의 답이다.</b> 비중이나 증감이 틀리면 사장님이 엉뚱한
 * 항목을 줄이려 든다 — 그래서 여기서 보는 것은 모양이 아니라 <b>나누는 기준</b>이다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyReportTests {

    private static final YearMonth MONTH = YearMonth.of(2026, 8);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "monthly-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선')
                RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    // --- 픽스처 --------------------------------------------------------------

    private void tx(String category, String flow, String amount, LocalDate date,
                    String classificationStatus) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status)
                VALUES (?, ?, ?, 'FIN_API', ?, ?, ?, ?, ?, ?, ?)
                """, businessId, accountId, categoryId, Timestamp.from(Instant.now()), date,
                flow, new BigDecimal(amount), category + " 거래", "dedup-" + System.nanoTime(),
                classificationStatus);
    }

    private void tx(String category, String flow, String amount, LocalDate date) {
        tx(category, flow, amount, date, "CONFIRMED");
    }

    private ResultActions report(YearMonth month) throws Exception {
        return mockMvc.perform(
                get("/api/v1/businesses/" + businessId + "/monthly-reports/" + month)
                        .header("Authorization", "Bearer " + token));
    }

    private ResultActions report() throws Exception {
        return report(MONTH);
    }

    // --- 지출 ----------------------------------------------------------------

    @Test
    void groupsExpensesByCategoryWithShares() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "3000000", MONTH.atDay(5));
        tx("RENT", "WITHDRAWAL", "1000000", MONTH.atDay(1));

        report().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.total").value(4000000))
                .andExpect(jsonPath("$.data.expense.byCategory[0].category").value("MATERIALS"))
                .andExpect(jsonPath("$.data.expense.byCategory[0].amount").value(3000000))
                .andExpect(jsonPath("$.data.expense.byCategory[0].share").value(0.75));
    }

    /** 제안 상태의 분류가 틀려 있으면 비중이 엉뚱해지고 그 표가 곧 답이 된다. */
    @Test
    void countsOnlyConfirmedClassifications() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "3000000", MONTH.atDay(5));
        tx("RENT", "WITHDRAWAL", "9000000", MONTH.atDay(1), "SUGGESTED");

        report().andExpect(jsonPath("$.data.expense.total").value(3000000));
    }

    @Test
    void comparesWithThePreviousMonthAndTheSameMonthLastYear() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "1200000", MONTH.atDay(5));
        tx("MATERIALS", "WITHDRAWAL", "1000000", MONTH.minusMonths(1).atDay(5));
        tx("MATERIALS", "WITHDRAWAL", "800000", MONTH.minusYears(1).atDay(5));

        report().andExpect(jsonPath("$.data.expense.byCategory[0].momChangeRate").value(0.2))
                .andExpect(jsonPath("$.data.expense.byCategory[0].yoyChangeRate").value(0.5));
    }

    /**
     * <b>비교 대상이 없으면 증감률 키가 없다.</b> 첫 달에는 전월이 없고, 없던 항목이 생긴 것을
     * 「무한대로 늘었다」고 말할 수 없다.
     */
    @Test
    void omitsChangeRatesWhenThereIsNothingToCompareWith() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "1200000", MONTH.atDay(5));

        report().andExpect(jsonPath("$.data.expense.byCategory[0].momChangeRate").doesNotExist())
                .andExpect(jsonPath("$.data.expense.byCategory[0].yoyChangeRate").doesNotExist());
    }

    /**
     * <b>다음 달 경비 전망은 내지 않는다.</b> 명세가 모양만 적고 계산 방법을 정하지 않았다 —
     * 임의로 정의하면 그것이 화면에 「전망」으로 나간다(도메인 규칙 1).
     */
    @Test
    void doesNotInventANextMonthEstimate() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "1200000", MONTH.atDay(5));

        report().andExpect(jsonPath("$.data.expense.nextMonthEstimate").doesNotExist());
    }

    // --- 수익 ----------------------------------------------------------------

    @Test
    void splitsIncomeByChannel() throws Exception {
        tx("CARD_SETTLEMENT", "DEPOSIT", "6000000", MONTH.atDay(3));
        tx("CASH_SALES", "DEPOSIT", "2000000", MONTH.atDay(4));

        report().andExpect(jsonPath("$.data.income.total").value(8000000))
                .andExpect(jsonPath("$.data.income.byChannel[0].channel").value("CARD_SETTLEMENT"))
                .andExpect(jsonPath("$.data.income.byChannel[0].share").value(0.75));
    }

    /** 대출 실행·대표자 자금 투입은 입금이지만 매출이 아니다 — 14.2 와 같은 집합이다. */
    @Test
    void leavesNonSalesDepositsOutOfIncome() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "2000000", MONTH.atDay(4));
        tx("LOAN_DISBURSEMENT", "DEPOSIT", "10000000", MONTH.atDay(5));
        tx("OWNER_FUNDING", "DEPOSIT", "5000000", MONTH.atDay(6));

        report().andExpect(jsonPath("$.data.income.total").value(2000000));
    }

    /** 상품별 매출 등록이 없으면 `available: false` 이고 화면이 등록을 유도한다. */
    @Test
    void saysProductBreakdownIsUnavailableWithoutRegisteredSales() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "2000000", MONTH.atDay(4));

        report().andExpect(jsonPath("$.data.income.byProduct.available").value(false))
                .andExpect(jsonPath("$.data.income.byProduct.items").isEmpty());
    }

    // --- 눈에 띄는 변화 -------------------------------------------------------

    @Test
    void pointsOutCategoriesThatMovedALot() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "3000000", MONTH.atDay(5));
        tx("MATERIALS", "WITHDRAWAL", "1000000", MONTH.minusMonths(1).atDay(5));

        report().andExpect(jsonPath("$.data.highlights[0].type").value("EXPENSE_INCREASE"))
                .andExpect(jsonPath("$.data.highlights[0].category").value("MATERIALS"))
                .andExpect(jsonPath("$.data.highlights[0].description")
                        .value(org.hamcrest.Matchers.containsString("200%")))
                .andExpect(jsonPath("$.data.highlights[0].sourceRef.category").value("MATERIALS"));
    }

    /** 작은 금액의 큰 비율이 목록을 채우지 않게 한다 — 3천원이 두 배가 된 것은 소식이 아니다. */
    @Test
    void ignoresMovementsThatAreTooSmallToMatter() throws Exception {
        tx("SUPPLIES", "WITHDRAWAL", "6000", MONTH.atDay(5));
        tx("SUPPLIES", "WITHDRAWAL", "3000", MONTH.minusMonths(1).atDay(5));

        report().andExpect(jsonPath("$.data.highlights").isEmpty());
    }

    /** 확정 표현을 쓰지 않는다(도메인 규칙 1). 문구는 템플릿이라 LLM 을 부르지 않는다. */
    @Test
    void writesTheHighlightWithoutOrderingTheUser() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "3000000", MONTH.atDay(5));
        tx("MATERIALS", "WITHDRAWAL", "1000000", MONTH.minusMonths(1).atDay(5));

        report().andExpect(jsonPath("$.data.highlights[0].description")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("하세요"))));
    }

    // --- 마감 여부 -----------------------------------------------------------

    /** 마감된 달에는 `isPartial` 키가 없다 — 있으면 화면이 확정값을 잠정으로 읽는다. */
    @Test
    void leavesOutIsPartialForAClosedMonth() throws Exception {
        tx("MATERIALS", "WITHDRAWAL", "1200000", MONTH.atDay(5));

        report().andExpect(jsonPath("$.data.isPartial").doesNotExist());
    }

    /** 당월은 「지금까지」다. 말하지 않으면 월말 확정값으로 읽힌다. */
    @Test
    void marksTheCurrentMonthAsPartial() throws Exception {
        YearMonth thisMonth = YearMonth.now();
        tx("MATERIALS", "WITHDRAWAL", "1200000", thisMonth.atDay(1));

        report(thisMonth).andExpect(jsonPath("$.data.isPartial").value(true));
    }

    // --- 입력·권한 -----------------------------------------------------------

    @Test
    void refusesAMalformedMonth() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/monthly-reports/2026-13-99")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesAnotherBusiness() throws Exception {
        String otherEmail = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/monthly-reports/" + MONTH)
                        .header("Authorization", "Bearer "
                                + tokenProvider.createAccessToken(otherUser, otherEmail)))
                .andExpect(status().isForbidden());
    }

    /** 거래가 하나도 없는 달도 정상 응답이다 — 오류가 아니라 0 이다. */
    @Test
    void answersWithZeroesForAnEmptyMonth() throws Exception {
        report().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.total").value(0))
                .andExpect(jsonPath("$.data.income.total").value(0))
                .andExpect(jsonPath("$.data.highlights").isEmpty());
    }
}
