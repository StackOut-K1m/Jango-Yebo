package com.jangoyebo.core.support;

import com.jangoyebo.core.support.DocumentGuideDtos.RequiredDocument;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 공고 검색·상세 응답 (17.1 · 17.2). */
public final class SupportProgramQueryDtos {

    private SupportProgramQueryDtos() {
    }

    /**
     * 목록 한 줄 (17.1).
     *
     * <p><b>{@code sourceUrl} 과 {@code collectedAt} 을 항상 함께 낸다.</b> 17.1 이 원문 링크와
     * 수집일 병기를 못박았다 — 구조화 결과를 사용자가 확인할 방법이 원문뿐이고, 공고는
     * 수집 이후에 바뀔 수 있다.
     *
     * <p><b>{@code matchScore} 가 없다.</b> 17.1 예시에는 있지만 이 경로에 사업장이 없다
     * ({@code /support-programs}). 점수를 내려면 지역·업종·매출·업력이 필요하고 그것은
     * 사업장에 딸린 값이라, 사업장 하나를 가정하면 다사업장 전제(도메인 규칙 7)가 깨진다.
     * 맞춤 점수는 17.3 {@code /businesses/{businessId}/support-programs/recommendations} 가 낸다.
     */
    public record ListItem(Long programId,
                           String title,
                           String organizer,
                           String sourceSite,
                           String sourceUrl,
                           Instant collectedAt,
                           String supportType,
                           BigDecimal maxAmount,
                           BigDecimal interestRate,
                           LocalDate applyStart,
                           LocalDate applyEnd,
                           String status,
                           EligibilitySummary eligibilitySummary,
                           boolean needsVerification) {

        static ListItem from(SupportProgram program) {
            return new ListItem(
                    program.getId(),
                    program.getTitle(),
                    program.getOrganizer(),
                    program.getSourceName(),
                    program.getSourceUrl(),
                    program.getCollectedAt(),
                    program.getSupportType(),
                    program.getMaxAmount(),
                    program.getInterestRate(),
                    program.getApplicationStartDate(),
                    program.getApplicationEndDate(),
                    program.getStatus(),
                    EligibilitySummary.from(program.getEligibility()),
                    program.needsVerification());
        }
    }

    /**
     * 목록에 얹는 자격 요약 (17.1 {@code eligibilitySummary}).
     *
     * <p>목록에서는 판단의 실마리만 주고 전체 조건은 상세(17.2)에서 본다.
     */
    public record EligibilitySummary(List<String> regions,
                                     List<String> industries,
                                     Object maxAnnualRevenue,
                                     List<String> notes) {

        static EligibilitySummary from(Map<String, Object> eligibility) {
            return new EligibilitySummary(
                    Eligibility.textList(eligibility, "regions"),
                    Eligibility.textList(eligibility, "industries"),
                    eligibility.get("maxAnnualRevenue"),
                    // 업력 조건을 사람이 읽는 한 줄로 바꾼 것이 notes 다. 지금은 구조화가
                    // 따로 주지 않아 제외 대상을 그대로 보여 준다 — 「휴·폐업자 제외」처럼
                    // 목록에서 바로 걸러 낼 수 있는 정보가 여기 들어 있다.
                    Eligibility.textList(eligibility, "excludes"));
        }
    }

    /**
     * 상세 (17.2). 목록 필드에 조건 전체와 필요 서류를 더한다.
     *
     * <p>{@code rawSummary} 는 <b>수집기가 채운다</b> — 기업마당 목록 API 의
     * {@code bsnsSumryCn} 을 받아 적는다(V24). 모델이 만든 문장이 아니다.
     *
     * <p><b>{@code expectedDisbursementDays} 는 아직 비어 있다.</b> 17.2 에는 있는데
     * <b>20.6 워커 출력 목록에 없고</b>, 공고 원문에서만 나올 수 있는 값이라 백엔드가
     * 지어낼 수 없다. 계약을 고쳐야 채워진다 — 이게 비면 17.3 의
     * {@code canArriveBeforeGap} 을 계산할 수 없다.
     *
     * <p><b>{@code requiredDocuments} 는 저장된 값 그대로가 아니다.</b> 워커는 이름·발급처·
     * 발급방법까지만 담고 {@code issueUrl}·{@code fee} 를 비워 보낸다 — 공고 본문의 URL 을
     * 그대로 실으면 죽은 링크가 사장님에게 나가기 때문이다. 그 두 칸은 서류 사전(17.4)이
     * 채운다({@link DocumentGuideService#attachGuides}).
     */
    public record Detail(ListItem summary,
                         Map<String, Object> eligibility,
                         String rawSummary,
                         Integer expectedDisbursementDays,
                         List<RequiredDocument> requiredDocuments) {

        static Detail from(SupportProgram program, List<RequiredDocument> requiredDocuments) {
            return new Detail(
                    ListItem.from(program),
                    program.getEligibility(),
                    program.getRawSummary(),
                    program.getExpectedDisbursementDays(),
                    requiredDocuments);
        }
    }
}
