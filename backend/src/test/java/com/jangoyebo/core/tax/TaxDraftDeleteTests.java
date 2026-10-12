package com.jangoyebo.core.tax;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신고서 초안 삭제 (명세 밖 — 구현현황 4장).
 *
 * <p><b>목록은 있는데 지우는 길이 없었다.</b> 기간이나 서식을 잘못 고른 초안이 영영 남아,
 * 프론트에서 「초안 제거를 못했다」는 회신이 왔다.
 *
 * <p><b>집계 중인 것을 지우면 안 된다.</b> 워커가 이어서 집계를 시도하고 「초안이 없다」로
 * 실패하는데, 화면에는 지운 초안 대신 <b>실패한 작업</b>이 남아 무엇이 잘못됐는지 알 수 없다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TaxDraftDeleteTests {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private TaxDraftService service;
    @Autowired private TaxDraftAggregator aggregator;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "taxdelete-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, phone)
                VALUES (?, 'x', '정유선', '010-1234-5678') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business
                    (owner_user_id, name, registration_number_masked, industry_code)
                VALUES (?, '유선상회', '123-**-**789', '552305') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    // --- 픽스처 --------------------------------------------------------------

    private void tx(String category, String amount) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, tax_review_status)
                VALUES (?, ?, ?, 'FIN_API', ?, DATE '2026-06-15', 'WITHDRAWAL', ?, ?, ?,
                        'CONFIRMED', 'EVIDENCE_CONFIRMED')
                """, businessId, accountId, categoryId, Timestamp.from(Instant.now()),
                new BigDecimal(amount), category + " 거래", "dedup-" + System.nanoTime());
    }

    /** 집계까지 끝낸 초안. */
    private Long readyDraft() {
        tx("RENT", "1200000");
        TaxFilingDraft draft = service.request(businessId, userId, 2026, "SIMPLE_LEDGER",
                FROM, TO, null);
        aggregator.generate(draft.getId());
        return draft.getId();
    }

    /** 접수만 하고 집계를 돌리지 않은 초안 — 상태가 {@code PROCESSING} 이다. */
    private Long processingDraft() {
        return service.request(businessId, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null).getId();
    }

    private ResultActions remove(Long draftId, String bearer) throws Exception {
        return mockMvc.perform(delete("/api/v1/tax-drafts/" + draftId)
                .header("Authorization", "Bearer " + bearer));
    }

    private ResultActions remove(Long draftId) throws Exception {
        return remove(draftId, token);
    }

    private int countDrafts() {
        return jdbc.queryForObject("SELECT count(*) FROM core.tax_filing_draft WHERE business_id = ?",
                Integer.class, businessId);
    }

    // --- 삭제 ----------------------------------------------------------------

    @Test
    void removesTheDraftAndAnswersNoContent() throws Exception {
        Long draftId = readyDraft();

        remove(draftId).andExpect(status().isNoContent());

        assertThat(countDrafts()).isZero();
        mockMvc.perform(get("/api/v1/tax-drafts/" + draftId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    /** 목록에서도 사라져야 한다 — 상세만 404 면 화면에는 그대로 보인다. */
    @Test
    void takesItOutOfTheList() throws Exception {
        Long draftId = readyDraft();

        remove(draftId).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/tax-drafts?taxYear=2026")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    /**
     * <b>행을 실제로 지운다.</b> 증빙(13.5)이 소프트 삭제인 것과 다르다 — 저쪽은
     * {@code EVIDENCE_CORRECTION} 이 실사용 정확도의 유일한 원천이라 남겨야 하고, 초안에는
     * 그런 이유가 없다.
     */
    @Test
    void reallyDeletesTheRowRatherThanHidingIt() throws Exception {
        Long draftId = readyDraft();

        remove(draftId).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.tax_filing_draft WHERE id = ?",
                Integer.class, draftId)).isZero();
    }

    /** 작업 이력은 초안과 별개다 — 함께 지우면 「무슨 일이 있었나」가 사라진다. */
    @Test
    void keepsTheGenerationJob() throws Exception {
        Long draftId = readyDraft();
        Long jobId = jdbc.queryForObject(
                "SELECT job_id FROM core.tax_filing_draft WHERE id = ?", Long.class, draftId);

        remove(draftId).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.job WHERE id = ?",
                Integer.class, jobId)).isEqualTo(1);
    }

    // --- 막는 경우 -----------------------------------------------------------

    /**
     * <b>집계 중인 초안은 막는다.</b> 지우면 워커가 이어서 집계를 시도하고 「초안이 없다」로
     * 실패해, 화면에는 지운 초안 대신 실패한 작업이 남는다.
     */
    @Test
    void refusesWhileTheDraftIsStillBeingAggregated() throws Exception {
        Long draftId = processingDraft();

        remove(draftId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TAX_DRAFT_NOT_DELETABLE"));

        assertThat(countDrafts()).isEqualTo(1);
    }

    /** 집계가 끝나면 지울 수 있다 — 막는 것은 상태이지 초안 자체가 아니다. */
    @Test
    void allowsItOnceTheAggregationFinished() throws Exception {
        Long draftId = processingDraft();
        remove(draftId).andExpect(status().isConflict());

        aggregator.generate(draftId);

        remove(draftId).andExpect(status().isNoContent());
    }

    @Test
    void refusesAnotherBusiness() throws Exception {
        Long draftId = readyDraft();
        String otherEmail = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);

        remove(draftId, tokenProvider.createAccessToken(otherUser, otherEmail))
                .andExpect(status().isForbidden());

        assertThat(countDrafts()).isEqualTo(1);
    }

    @Test
    void answersNotFoundForAnUnknownDraft() throws Exception {
        remove(999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TAX_DRAFT_NOT_FOUND"));
    }

    /** 두 번 지워도 두 번째는 404 다. 이미 없는 것을 지웠다고 500 이 나면 안 된다. */
    @Test
    void answersNotFoundWhenDeletedTwice() throws Exception {
        Long draftId = readyDraft();

        remove(draftId).andExpect(status().isNoContent());
        remove(draftId).andExpect(status().isNotFound());
    }

    // --- 지운 뒤 ------------------------------------------------------------

    /**
     * <b>사장님이 적어 둔 값이 함께 사라진다.</b> 재생성은 직전 초안에서 {@code overrides} 를
     * 물려받는데, 마지막 초안을 지우면 물려받을 것이 없다 — 화면이 지우기 전에 한 번 물어야
     * 하는 이유다. 이 테스트는 그 손실을 <b>기록</b>한다.
     */
    @Test
    void losesTheUserEnteredValuesWithTheLastDraft() throws Exception {
        Long draftId = readyDraft();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/tax-drafts/" + draftId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"overrides":[{"fieldKey":"DEPRECIATION","value":500000}]}
                                """))
                .andExpect(status().isOk());

        remove(draftId).andExpect(status().isNoContent());

        TaxFilingDraft regenerated = service.request(businessId, userId, 2026, "SIMPLE_LEDGER",
                FROM, TO, null);
        assertThat(regenerated.getOverrides()).as("물려받을 직전 초안이 없다").isNullOrEmpty();
    }

    /**
     * <b>칸 정의가 없는 서식은 초안을 만들지 못한다.</b> {@code STANDARD_RATE} 는 지원 수준이
     * {@code DRAFT_OPTIONAL} 이지만 {@code sections} 가 비어 있어, 막지 않으면 항목 0개짜리
     * 초안이 생기고 화면은 그것을 「집계가 0원」으로 읽는다(프론트 회신 2026-09-18).
     *
     * <p>여기 있는 이유 — 지울 수 없는 초안이 애초에 생기지 않아야 한다.
     */
    @Test
    void refusesToCreateADraftForAFormWithNoFieldDefinitions() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/businesses/" + businessId + "/tax-drafts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"taxYear":2026,"formType":"STANDARD_RATE",
                                 "periodStart":"2026-01-01","periodEnd":"2026-12-31"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FORM_NOT_SUPPORTED"));

        assertThat(countDrafts()).isZero();
    }

    /** 초안이 여럿이면 지운 것만 없어진다. */
    @Test
    void leavesTheOtherDraftsAlone() throws Exception {
        Long first = readyDraft();
        Long second = readyDraft();

        remove(first).andExpect(status().isNoContent());

        assertThat(countDrafts()).isEqualTo(1);
        mockMvc.perform(get("/api/v1/tax-drafts/" + second)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
