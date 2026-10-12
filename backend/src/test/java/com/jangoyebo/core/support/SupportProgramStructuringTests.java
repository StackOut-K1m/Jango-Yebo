package com.jangoyebo.core.support;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구조화 콜백 반영 (명세 20.6 → 17.1·17.2, P1-15).
 *
 * <p><b>서비스는 자격을 판정하지 않는다.</b> 워커가 준 조건을 그대로 나르고, 「이 사업장이
 * 대상인가」는 17.3 규칙 엔진이 정한다. 여기서 보는 것은 <b>그대로 나르는가</b>와
 * <b>모집 상태를 날짜로 정하는가</b> 둘이다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@Transactional
class SupportProgramStructuringTests {

    @Autowired private SupportProgramStructuringService structuring;
    @Autowired private SupportProgramRepository programs;
    @Autowired private JobService jobService;

    /** 워커 결과가 그대로 들어가고, 필수 서류는 eligibility 안에 함께 담긴다 (ERD 8장). */
    @Test
    void appliesWhatTheWorkerSent() {
        SupportProgram program = programs.saveAndFlush(collected());
        Job job = enqueue(program);

        structuring.apply(job, Map.of(
                "supportType", "SUBSIDY",
                "maxAmount", 3_000_000,
                "applyStart", "2026-09-01",
                "applyEnd", "2026-12-31",
                "eligibility", Map.of(
                        "regions", List.of("광주"),
                        "excludes", List.of("휴·폐업자"),
                        "uncertainConditions", List.of()),
                "requiredDocuments", List.of(
                        Map.of("documentName", "사업자등록증명", "issuer", "홈택스"))));
        programs.flush();

        SupportProgram saved = programs.findById(program.getId()).orElseThrow();
        assertThat(saved.getSupportType()).isEqualTo("SUBSIDY");
        assertThat(saved.getMaxAmount()).isEqualByComparingTo("3000000");
        assertThat(saved.getApplicationEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(saved.isStructured()).isTrue();
        assertThat(saved.needsVerification()).isFalse();
        assertThat(saved.getEligibility()).containsKey("requiredDocuments");
    }

    /**
     * 확신 못 한 조건이 하나라도 있으면 「기관 확인 필요」다 (17.1 {@code needsVerification}).
     *
     * <p>이 목록이 이 기능의 안전장치다 — 모델이 애매한 자리를 채워 버리면 사장님이 자격이
     * 있다고 읽고 서류를 준비한 뒤 거절당한다.
     */
    @Test
    void flagsUncertainConditions() {
        SupportProgram program = programs.saveAndFlush(collected());

        structuring.apply(enqueue(program), Map.of(
                "supportType", "FUNDING",
                "eligibility", Map.of(
                        "regions", List.of("광주"),
                        "uncertainConditions", List.of("공동사업자 신청 가능 여부"))));

        assertThat(program.needsVerification()).isTrue();
    }

    /**
     * <b>모집 상태는 날짜 비교로 정한다.</b> 워커가 보내지 않고, 보내게 해서도 안 된다 —
     * 「오늘」이 모델의 시점에 달리면 어제 만든 결과가 오늘 틀린다(도메인 규칙 2).
     */
    @Test
    void decidesRecruitmentStatusFromDates() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

        assertThat(statusFor(today.minusDays(30), today.minusDays(1))).isEqualTo("CLOSED");
        assertThat(statusFor(today.plusDays(7), today.plusDays(30))).isEqualTo("UPCOMING");
        assertThat(statusFor(today.minusDays(7), today.plusDays(7))).isEqualTo("OPEN");
    }

    /**
     * 기간을 모르면 {@code UNKNOWN} 이다.
     *
     * <p>상시모집이라 비었을 수도, 구조화가 못 읽었을 수도 있는데 <b>둘 다 「열려 있다」고
     * 단정할 근거가 아니다.</b>
     */
    @Test
    void doesNotAssumeOpenWhenThePeriodIsUnknown() {
        assertThat(statusFor(null, null)).isEqualTo("UNKNOWN");
    }

    /**
     * 계약이 메워졌다 — 금리와 지급 소요일이 들어온다 (2026-09-16).
     *
     * <p>{@code interestRate}·{@code expectedDisbursementDays} 는 17.1·17.2 에 있는데 20.6
     * 목록에 없어 오래 비어 있었다. 이제 워커가 보낸다.
     *
     * <p>{@code rawSummary} 는 여전히 여기서 안 채운다 — <b>워커 몫이 아니라 수집기 몫</b>이다
     * (기업마당 {@code bsnsSumryCn}, {@code V24}).
     */
    @Test
    void fillsTheFieldsTheContractNowCarries() {
        SupportProgram program = programs.saveAndFlush(collected());

        structuring.apply(enqueue(program), Map.of(
                "supportType", "FUNDING",
                "interestRate", new java.math.BigDecimal("2.5"),
                "expectedDisbursementDays", 30,
                "eligibility", Map.of("regions", List.of("광주"))));

        assertThat(program.getInterestRate())
                .as("연 % 다 — 대출 쪽(V23)과 같은 단위")
                .isEqualByComparingTo("2.5");
        assertThat(program.getExpectedDisbursementDays()).isEqualTo(30);
    }

    /**
     * <b>지급 소요일은 대개 비어 있고, 그것이 맞는 결과다.</b>
     *
     * <p>실제 공고 32건 중 지급 시기를 말한 것이 <b>1건</b>이라(「선정 후 N일」은 0건) 워커는
     * 원문에 일수가 적힌 경우에만 채운다. 이 테스트가 지키는 것은 「비어도 된다」가 아니라
     * <b>「비었다고 여기서 기본값을 만들지 않는다」</b>다 — 17.3 이 이 값으로 「지원금이
     * 정산 공백 전에 도착한다」를 말하므로, 지어낸 30 은 사장님이 오지 않을 돈을 기다리며
     * 다른 자금 대책을 접게 만든다.
     */
    @Test
    void doesNotInventADisbursementDelayWhenTheAnnouncementIsSilent() {
        SupportProgram program = programs.saveAndFlush(collected());

        structuring.apply(enqueue(program), Map.of(
                "supportType", "SUBSIDY",
                "eligibility", Map.of("regions", List.of("광주"))));

        assertThat(program.getExpectedDisbursementDays()).isNull();
        assertThat(program.getInterestRate()).isNull();
    }

    /** 소수 일수는 워커가 만들어 낸 값이지 공고에 적힌 값이 아니다. 받지 않는다. */
    @Test
    void refusesAFractionalDisbursementDelay() {
        SupportProgram program = programs.saveAndFlush(collected());

        structuring.apply(enqueue(program), Map.of(
                "supportType", "SUBSIDY",
                "expectedDisbursementDays", 30.5,
                "eligibility", Map.of("regions", List.of("광주"))));

        assertThat(program.getExpectedDisbursementDays()).isNull();
    }


    /**
     * <b>워커는 모르는 조건을 {@code null} 로 보낸다.</b>
     *
     * <p>{@code Eligibility} 의 {@code maxAnnualRevenue}·{@code minYearsInBusiness} 는 공고에
     * 없으면 {@code null} 이라, 실제 콜백은 거의 항상 {@code null} 을 하나는 담고 있다.
     * {@code Map.copyOf} 는 그걸 받으면 <b>NPE 를 던진다</b> — 그러면 바깥 트랜잭션이
     * rollback-only 가 되고 워커는 500 을 받아 재시도하며, 같은 예외가 또 나서
     * <b>구조화 결과가 영영 저장되지 않는다.</b> 실호출에서 이 경로로 3건이 전부 막혔다.
     *
     * <p>{@code Map.of(...)} 로 쓴 다른 테스트들은 null 을 담을 수 없어 이걸 못 잡는다.
     */
    @Test
    void acceptsTheNullsTheWorkerActuallySends() {
        SupportProgram program = programs.saveAndFlush(collected());

        java.util.Map<String, Object> eligibility = new java.util.LinkedHashMap<>();
        eligibility.put("regions", List.of());
        eligibility.put("industries", List.of());
        eligibility.put("maxAnnualRevenue", null);
        eligibility.put("minYearsInBusiness", null);
        eligibility.put("maxYearsInBusiness", null);
        eligibility.put("excludes", List.of());
        eligibility.put("uncertainConditions", List.of("stub 응답이라 공고를 읽지 않았습니다."));

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("supportType", "OTHER");
        result.put("maxAmount", null);
        result.put("applyStart", null);
        result.put("applyEnd", null);
        result.put("eligibility", eligibility);
        result.put("requiredDocuments", List.of());

        structuring.apply(enqueue(program), result);
        programs.flush();

        SupportProgram saved = programs.findById(program.getId()).orElseThrow();
        assertThat(saved.isStructured()).isTrue();
        assertThat(saved.needsVerification()).isTrue();
        assertThat(saved.getEligibility()).containsKey("maxAnnualRevenue");
    }

    // ------------------------------------------------------------------ 도우미

    private String statusFor(LocalDate start, LocalDate end) {
        SupportProgram program = programs.saveAndFlush(collected());
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("supportType", "SUBSIDY");
        result.put("eligibility", Map.of("regions", List.of("광주")));
        if (start != null) {
            result.put("applyStart", start.toString());
        }
        if (end != null) {
            result.put("applyEnd", end.toString());
        }
        structuring.apply(enqueue(program), result);
        return program.getStatus();
    }

    /** 공고 구조화 작업에는 사업장이 없다 (V21). */
    private Job enqueue(SupportProgram program) {
        return jobService.enqueue(null, null, JobType.PROGRAM_STRUCTURING,
                Map.of("programId", program.getId()));
    }

    private SupportProgram collected() {
        return SupportProgram.collected("BIZINFO", "PBLN_" + System.nanoTime(),
                "2026년 광주 소상공인 임대료 특별지원", "https://www.bizinfo.go.kr/x",
                "광주광역시", "공고 원문", "사업개요 한 줄", null, Instant.now());
    }
}
