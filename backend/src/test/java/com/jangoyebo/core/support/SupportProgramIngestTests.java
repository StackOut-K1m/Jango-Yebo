package com.jangoyebo.core.support;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.ai.AiProperties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공고 배치 적재 (명세 20.8, P1-15).
 *
 * <p><b>여기서 지키려는 것은 LLM 비용이다.</b> 매일 1,400건을 전량 받는데 구조화는 처음 보는
 * 것과 원문이 바뀐 것에만 걸어야 한다. 안 가르면 같은 공고를 매일 다시 읽는다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class SupportProgramIngestTests {

    private static final String PATH = "/internal/v1/support-programs:batch";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiProperties aiProperties;

    /** 처음 보는 공고는 저장되고 구조화 큐에 들어간다. */
    @Test
    void storesNewProgramsAndQueuesStructuring() throws Exception {
        String id = unique();

        ingest(body(id, "광주 소상공인 임대료 특별지원", "공고 원문입니다. 지원 대상은 ..."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.queued").value(1));

        assertThat(jobCountFor(id)).isEqualTo(1);
    }

    /**
     * <b>원문이 그대로면 다시 읽지 않는다.</b>
     *
     * <p>매일 전량 upsert 라 이 판정이 곧 비용이다. 제목이 정정돼도 자격 조건은 그대로이므로
     * 다시 읽을 이유가 아니다.
     */
    @Test
    void doesNotQueueAgainWhenNothingChanged() throws Exception {
        String id = unique();
        ingest(body(id, "원래 제목", "공고 원문"));
        markStructured(id);

        ingest(body(id, "제목만 정정됨", "공고 원문"))
                .andExpect(jsonPath("$.data.unchanged").value(1))
                .andExpect(jsonPath("$.data.queued").value(0));

        assertThat(jobCountFor(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT title FROM core.support_program WHERE external_id = ?", String.class, id))
                .as("다시 읽지는 않아도 제목 정정은 반영한다")
                .isEqualTo("제목만 정정됨");
    }

    /** 원문이 바뀌면 다시 읽는다 — 공고가 연장·정정되는 일이 실제로 있다. */
    @Test
    void queuesAgainWhenTheRawTextChanged() throws Exception {
        String id = unique();
        ingest(body(id, "제목", "공고 원문"));
        markStructured(id);

        ingest(body(id, "제목", "공고 원문 — 신청 기간이 연장되었습니다"))
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.queued").value(1));

        assertThat(jobCountFor(id)).isEqualTo(2);
    }

    /**
     * <b>요약만 보고 자격 조건을 만들게 하지 않는다.</b>
     *
     * <p>기업마당 목록 API 는 사업개요(요약)만 주고 원문은 상세 페이지에 있다. 요약으로
     * 구조화하면 모델은 본문을 다 봤다고 여기고 {@code uncertainConditions} 를 비워 돌려준다 —
     * 사장님은 자격이 있다고 읽고 서류를 준비한 뒤 기관에서 거절당한다.
     */
    @Test
    void storesButDoesNotQueueWhenThereIsNoRawText() throws Exception {
        String id = unique();

        ingest(body(id, "원문 없는 공고", null))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.queued").value(0))
                .andExpect(jsonPath("$.data.needBody[0]").value(id));

        assertThat(jobCountFor(id)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM core.support_program WHERE external_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    /**
     * <b>요약은 모델이 만들지 않는다.</b> 기업마당이 {@code bsnsSumryCn} 으로 주는 것을
     * 수집기가 받아 적고, 17.2 상세가 그대로 낸다 (V23).
     *
     * <p>원문이 없어 구조화를 못 건 공고도 요약은 남는다 — 서로를 대신하지 않는다.
     */
    @Test
    void keepsTheSummaryEvenWhenThereIsNoRawText() throws Exception {
        String id = unique();

        ingest(body(id, "원문 없는 공고", null))
                .andExpect(jsonPath("$.data.queued").value(0));

        assertThat(jdbc.queryForObject(
                "SELECT raw_summary FROM core.support_program WHERE external_id = ?",
                String.class, id))
                .isEqualTo("사업개요 한 줄입니다.");
    }

    /**
     * 구조화 작업에는 <b>사업장이 없다</b> (V21).
     *
     * <p>아무 사업장이나 골라 넣었으면 그 사장님의 Job 목록에 남의 작업이 뜬다.
     */
    @Test
    void queuesAGlobalJobWithNoBusiness() throws Exception {
        String id = unique();
        ingest(body(id, "공고", "원문"));

        assertThat(jdbc.queryForObject("""
                SELECT business_id IS NULL FROM core.job
                 WHERE job_type = 'PROGRAM_STRUCTURING' AND params ->> 'programId' = ?
                """, Boolean.class, programId(id).toString())).isTrue();
    }

    /** 서비스 토큰이 없으면 닿지 않는다 — 내부 경로다. */
    @Test
    void rejectsRequestsWithoutTheServiceToken() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(unique(), "공고", "원문")))
                .andExpect(status().isUnauthorized());
    }

    /** 빈 묶음은 거절한다 — 수집기가 아무것도 못 받은 것을 성공으로 기록하면 안 된다. */
    @Test
    void rejectsAnEmptyBatch() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + aiProperties.serviceToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "sourceSite": "BIZINFO", "collectedAt": "2026-09-15T00:30:00Z",
                                  "items": [] }
                                """))
                .andExpect(status().isBadRequest());
    }


    /**
     * <b>출처가 안 고쳤으면 본문을 다시 달라고 하지 않는다.</b>
     *
     * <p>이 판정이 곧 남의 서버 부하다 — 없으면 매일 1,526건의 상세 페이지를 전부 받아야
     * 한다. 기업마당 {@code updtPnttm} 이 그 근거다(V24).
     */
    @Test
    void doesNotAskForTheBodyAgainWhenTheSourceDidNotChange() throws Exception {
        String id = unique();
        String stamp = "2026-09-14T15:55:52+09:00";

        // 1단계: 목록만 → 본문이 필요하다
        ingest(listOnly(id, "공고", stamp))
                .andExpect(jsonPath("$.data.needBody[0]").value(id));

        // 2단계: 본문을 채워 보낸다 → 구조화가 걸린다
        ingest(withBody(id, "공고", stamp, "공고 원문"))
                .andExpect(jsonPath("$.data.queued").value(1));
        markStructured(id);

        // 다음 날: 수정일자가 그대로면 아무것도 필요 없다
        ingest(listOnly(id, "공고", stamp))
                .andExpect(jsonPath("$.data.unchanged").value(1))
                .andExpect(jsonPath("$.data.queued").value(0))
                .andExpect(jsonPath("$.data.needBody").isEmpty());
    }

    /** 출처가 고쳤으면 본문을 다시 받아야 한다 — 공고는 연장·정정된다. */
    @Test
    void asksForTheBodyAgainWhenTheSourceChanged() throws Exception {
        String id = unique();
        ingest(withBody(id, "공고", "2026-09-14T15:55:52+09:00", "공고 원문"));
        markStructured(id);

        ingest(listOnly(id, "공고", "2026-09-20T09:00:00+09:00"))
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.needBody[0]").value(id));
    }

    /**
     * <b>목록만 보낸 요청이 이미 받아 둔 원문을 지우지 않는다.</b>
     *
     * <p>수집기는 1단계에서 {@code rawText} 를 비워 보낸다. 그걸 「본문이 없어졌다」로 읽고
     * 덮으면 <b>매일 전건을 다시 긁게 된다</b> — 이 변경으로 아끼려던 것이 그대로 사라진다.
     */
    @Test
    void aListOnlyPushDoesNotWipeTheStoredBody() throws Exception {
        String id = unique();
        ingest(withBody(id, "공고", "2026-09-14T15:55:52+09:00", "받아 둔 원문"));

        ingest(listOnly(id, "공고", "2026-09-14T15:55:52+09:00"));

        assertThat(jdbc.queryForObject(
                "SELECT raw_text FROM core.support_program WHERE external_id = ?", String.class, id))
                .isEqualTo("받아 둔 원문");
    }

    /**
     * <b>출처가 적어 둔 신청기간이 정본이다.</b>
     *
     * <p>기업마당이 {@code reqstBeginEndDe} 로 기간을 직접 주는데 그걸 두고 원문에서 다시
     * 뽑게 하면 틀릴 자리를 하나 더 만드는 셈이다. 상태도 이 기간으로 정해진다.
     */
    @Test
    void takesThePeriodFromTheSourceAndDecidesStatus() throws Exception {
        String id = unique();
        LocalDate start = LocalDate.now().minusDays(3);
        LocalDate end = LocalDate.now().plusDays(10);

        ingest("{ \"sourceSite\": \"BIZINFO\", \"collectedAt\": \"2026-09-15T00:30:00Z\","
                + " \"items\": [{ \"externalId\": \"" + id + "\", \"title\": \"기간 있는 공고\","
                + " \"sourceUrl\": \"https://www.bizinfo.go.kr/x\","
                + " \"applyStart\": \"" + start + "\", \"applyEnd\": \"" + end + "\" }] }");

        assertThat(jdbc.queryForObject("""
                SELECT status FROM core.support_program WHERE external_id = ?
                """, String.class, id)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("""
                SELECT application_end_date FROM core.support_program WHERE external_id = ?
                """, LocalDate.class, id)).isEqualTo(end);
    }

    // ------------------------------------------------------------------ 도우미

    private org.springframework.test.web.servlet.ResultActions ingest(String body) throws Exception {
        return mockMvc.perform(post(PATH)
                .header("Authorization", "Bearer " + aiProperties.serviceToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String body(String externalId, String title, String rawText) {
        String raw = rawText == null ? "" : ", \"rawText\": \"" + rawText + "\"";
        return "{ \"sourceSite\": \"BIZINFO\", \"collectedAt\": \"2026-09-15T00:30:00Z\","
                + " \"items\": [{ \"externalId\": \"" + externalId + "\","
                + " \"title\": \"" + title + "\","
                + " \"sourceUrl\": \"https://www.bizinfo.go.kr/x\","
                + " \"rawSummary\": \"사업개요 한 줄입니다.\","
                + " \"organizer\": \"광주광역시\"" + raw + " }] }";
    }

    /** 1단계 — 목록만. rawText 가 없다. */
    private String listOnly(String externalId, String title, String sourceUpdatedAt) {
        return item(externalId, title, sourceUpdatedAt, null);
    }

    /** 2단계 — 상세 페이지에서 받아 온 본문을 실어 보낸다. */
    private String withBody(String externalId, String title, String sourceUpdatedAt,
                            String rawText) {
        return item(externalId, title, sourceUpdatedAt, rawText);
    }

    private String item(String externalId, String title, String sourceUpdatedAt, String rawText) {
        String raw = rawText == null ? "" : ", \"rawText\": \"" + rawText + "\"";
        return "{ \"sourceSite\": \"BIZINFO\", \"collectedAt\": \"2026-09-15T00:30:00Z\","
                + " \"items\": [{ \"externalId\": \"" + externalId + "\","
                + " \"title\": \"" + title + "\","
                + " \"sourceUrl\": \"https://www.bizinfo.go.kr/x\","
                + " \"sourceUpdatedAt\": \"" + sourceUpdatedAt + "\","
                + " \"rawSummary\": \"사업개요 한 줄입니다.\"" + raw + " }] }";
    }

    private String unique() {
        return "PBLN_" + System.nanoTime();
    }

    private Long programId(String externalId) {
        return jdbc.queryForObject(
                "SELECT id FROM core.support_program WHERE external_id = ?", Long.class, externalId);
    }

    /** 구조화가 끝난 상태로 만든다 — 그래야 「원문이 그대로면 안 건다」를 볼 수 있다. */
    private void markStructured(String externalId) {
        jdbc.update("""
                UPDATE core.support_program
                   SET eligibility = '{"regions": ["광주"]}'::jsonb, support_type = 'SUBSIDY'
                 WHERE external_id = ?
                """, externalId);
    }

    private Integer jobCountFor(String externalId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM core.job
                 WHERE job_type = 'PROGRAM_STRUCTURING' AND params ->> 'programId' = ?
                """, Integer.class, programId(externalId).toString());
    }
}
