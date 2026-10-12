package com.jangoyebo.core.report;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.core.ai.BriefingGenerationHandler;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobRepository;
import com.jangoyebo.core.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 브리핑 문장 — 워커 접수와 콜백 반영 (API 20.7 · P1-17).
 *
 * <p><b>문장이 없어도 브리핑은 살아 있어야 한다.</b> 지표만으로 정상 산출물이라, 워커가
 * 안 떠 있거나 실패해도 브리핑이 통째로 사라지면 안 된다.
 *
 * <p><b>{@code PARTIAL} 판정은 워커가 한다.</b> 환각 숫자를 잡는 한국어 수치 파서가 거기
 * 있다 — 백엔드는 그 판정을 기록할 뿐이고, 여기서 보는 것은 <b>기록이 맞는가</b>다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class BriefingSentenceTests {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 7);
    private static final LocalDate TO = LocalDate.of(2026, 9, 13);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BriefingService service;
    @Autowired private BriefingGenerationHandler handler;
    @Autowired private JobRepository jobs;
    @Autowired private com.jangoyebo.core.ai.AiResultApplier applier;

    private Long userId;
    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선')
                RETURNING id
                """, Long.class, "sentence-" + unique + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account (business_id, provider, bank_code, account_number_encrypted,
                                          account_number_hash, account_number_masked, name,
                                          status, sync_status, current_balance, balance_as_of)
                VALUES (?, 'FINOPENAPI', '004', 'enc', ?, '004-**-1234', '주계좌',
                        'CONNECTED', 'SUCCESS', 5000000, now()) RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    // --- 픽스처 --------------------------------------------------------------

    private void tx(String category, String flow, String amount, LocalDate date) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status)
                VALUES (?, ?, ?, 'FIN_API', ?, ?, ?, ?, ?, ?, 'CONFIRMED')
                """, businessId, accountId, categoryId, Timestamp.from(Instant.now()), date,
                flow, new BigDecimal(amount), category + " 거래", "dedup-" + System.nanoTime());
    }

    private Report briefing() {
        tx("CASH_SALES", "DEPOSIT", "2000000", FROM.plusDays(1));
        tx("RENT", "WITHDRAWAL", "1200000", FROM.plusDays(2));
        return service.generate(businessId, ReportType.WEEKLY, FROM, TO).orElseThrow();
    }

    private Job sentenceJob(Long briefingId) {
        return jobs.findAll().stream()
                .filter(job -> job.getJobType() == JobType.BRIEFING_GENERATION)
                .filter(job -> job.getParams() != null
                        && String.valueOf(job.getParams().get("briefingId"))
                                .equals(String.valueOf(briefingId)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("브리핑 %d 의 문장 작업이 없다"
                        .formatted(briefingId)));
    }

    private Map<String, Object> row(Long briefingId) {
        return jdbc.queryForMap(
                "SELECT headline, narrative, status FROM core.report WHERE id = ?", briefingId);
    }

    /** 워커가 보내는 콜백 본문. {@code status} 는 워커가 붙인다. */
    private void callback(Long jobId, String status, String headline, String content) {
        applier.applyResult(jobId, new com.jangoyebo.core.ai.AiCallbackDtos.ResultRequest(
                true, Map.of("status", status, "headline", headline, "content", content), null));
    }

    // --- 접수 ----------------------------------------------------------------

    /** 지표를 저장하면 문장 작업이 함께 걸린다 — 아무도 걸지 않으면 문장이 영영 안 온다. */
    @Test
    void queuesASentenceJobWhenMetricsAreStored() {
        Report report = briefing();

        assertThat(sentenceJob(report.getId())).isNotNull();
    }

    /**
     * <b>워커에는 값 있는 지표만 보낸다.</b> 워커의 {@code value} 는 필수라, 「안전선 최초
     * 하회일」처럼 날짜만 있는 줄을 그대로 보내면 요청이 통째로 거절된다.
     */
    @Test
    void leavesOutMetricsThatHaveNoValue() {
        Report report = briefing();
        jdbc.update("""
                UPDATE core.report
                   SET metrics = metrics || '[{"label":"안전선 최초 하회일",
                                               "sourceRef":{"type":"FORECAST"}}]'::jsonb
                 WHERE id = ?
                """, report.getId());

        List<Map<String, Object>> sent = service.snapshot(report.getId()).metrics().stream()
                .filter(metric -> metric.get("value") != null)
                .toList();

        assertThat(sent).isNotEmpty()
                .noneSatisfy(metric -> assertThat(metric.get("label")).isEqualTo("안전선 최초 하회일"));
    }

    /**
     * <b>쓸 지표가 하나도 없으면 워커를 부르지 않는다.</b> 워커가 {@code min_length=1} 이라
     * 거절하기도 하고, 무엇보다 쓸 문장이 없다.
     */
    @Test
    void refusesToCallTheWorkerWithNothingToWriteAbout() {
        Report report = briefing();
        jdbc.update("UPDATE core.report SET metrics = '[]'::jsonb WHERE id = ?", report.getId());

        assertThatThrownBy(() -> handler.handle(sentenceJob(report.getId())))
                .isInstanceOf(BusinessException.class);
    }

    // --- 콜백 반영 -----------------------------------------------------------

    @Test
    void putsTheSentenceOnTheBriefing() {
        Report report = briefing();
        Job job = sentenceJob(report.getId());

        callback(job.getId(), "COMPLETED", "정산 입금이 줄었어요", "이번 주 입금이 지난주보다 적습니다.");

        Map<String, Object> saved = row(report.getId());
        assertThat(saved.get("headline")).isEqualTo("정산 입금이 줄었어요");
        assertThat(saved.get("narrative")).isEqualTo("이번 주 입금이 지난주보다 적습니다.");
        assertThat(saved.get("status")).isEqualTo("COMPLETED");
    }

    /**
     * <b>워커가 문장 일부를 버렸다는 사실을 기록한다.</b> 같은 검사를 백엔드에 한 벌 더 두지
     * 않는다 — 둘이 갈리면 어느 쪽이 옳은지 알 수 없다.
     */
    @Test
    void recordsThatTheWorkerDroppedSomeSentences() {
        Report report = briefing();
        Job job = sentenceJob(report.getId());

        callback(job.getId(), "PARTIAL", "정산 입금이 줄었어요", "이번 주 입금이 적습니다.");

        assertThat(row(report.getId()).get("status")).isEqualTo("PARTIAL");
    }

    /**
     * <b>문장이 통째로 비면 실패로 남긴다.</b> 빈 제목·본문을 저장하면 화면이 「문장이 있다」로
     * 읽고 빈 칸을 그린다.
     */
    @Test
    void marksItFailedWhenEverySentenceWasDropped() {
        Report report = briefing();
        Job job = sentenceJob(report.getId());

        callback(job.getId(), "PARTIAL", "", "");

        Map<String, Object> saved = row(report.getId());
        assertThat(saved.get("status")).isEqualTo("FAILED");
        assertThat(saved.get("headline")).isNull();
    }

    /**
     * <b>지표는 그대로 남는다.</b> 명세가 「생성 실패 시 지표만이라도 반환한다」고 정했다 —
     * 문장이 실패했다고 브리핑이 없어지면 안 된다.
     */
    @Test
    void keepsTheMetricsWhenTheSentenceFails() {
        Report report = briefing();

        service.markFailed(report.getId());

        assertThat(row(report.getId()).get("status")).isEqualTo("FAILED");
        assertThat(service.snapshot(report.getId()).metrics()).isNotEmpty();
    }

    /** 같은 기간을 다시 돌리면 문장 작업도 다시 걸린다 — 새 숫자에는 새 문장이 필요하다. */
    @Test
    void asksForANewSentenceWhenMetricsAreRefreshed() {
        Report report = briefing();
        callback(sentenceJob(report.getId()).getId(), "COMPLETED", "지난 문장", "지난 본문");

        tx("CASH_SALES", "DEPOSIT", "500000", FROM.plusDays(3));
        service.generate(businessId, ReportType.WEEKLY, FROM, TO);

        assertThat(row(report.getId()).get("status")).isEqualTo("PENDING");
        long queued = jobs.findAll().stream()
                .filter(job -> job.getJobType() == JobType.BRIEFING_GENERATION)
                .filter(job -> job.getParams() != null
                        && String.valueOf(job.getParams().get("briefingId"))
                                .equals(String.valueOf(report.getId())))
                .count();
        assertThat(queued).as("문장 작업이 다시 걸려야 한다").isEqualTo(2);
    }
}
