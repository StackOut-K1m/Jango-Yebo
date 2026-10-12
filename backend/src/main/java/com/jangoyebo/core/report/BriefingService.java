package com.jangoyebo.core.report;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 주간·월간 브리핑 (API 15.2 · P1-17).
 *
 * <p><b>지표를 먼저 저장하고 문장은 나중에 얹는다.</b> 명세가 「생성 실패 시 지표만이라도
 * 반환한다」고 정했으므로, 문장이 없는 브리핑도 화면에 나갈 수 있는 산출물이다. 그래서 이
 * 서비스는 <b>AI 없이 완결된다</b> — 워커 연결은 그 위에 얹힌다.
 */
@Service
public class BriefingService implements BriefingSource {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);

    private final ReportRepository reports;
    private final BriefingMetricsReader metricsReader;
    private final BusinessGuard guard;
    private final JobService jobs;
    private final Clock clock;

    BriefingService(ReportRepository reports, BriefingMetricsReader metricsReader,
                    BusinessGuard guard, JobService jobs, Clock clock) {
        this.reports = reports;
        this.metricsReader = metricsReader;
        this.guard = guard;
        this.jobs = jobs;
        this.clock = clock;
    }

    /**
     * 한 기간의 지표를 내고 저장한다.
     *
     * <p><b>지표가 하나도 없으면 만들지 않는다.</b> 거래도 예측도 없는 새 사업장에 빈 브리핑을
     * 남기면 목록이 빈 카드로 찬다 — 안전선 추천값·패턴 알림과 같은 원칙이다(근거가 없으면
     * 0 이 아니라 안 낸다).
     *
     * <p><b>같은 기간을 다시 돌리면 갈아 끼운다</b>({@code uk_report_period}). 배치가 재시도돼도
     * 두 벌이 생기지 않고, <b>옛 문장은 지워진다</b> — 지난 숫자로 쓴 문장이 새 숫자 옆에
     * 남으면 둘이 어긋난 채로 읽힌다.
     *
     * @return 만들어졌거나 갱신된 브리핑. 지표가 없으면 {@link Optional#empty()}
     */
    @Transactional
    public Optional<Report> generate(Long businessId, ReportType type, LocalDate from,
                                     LocalDate to) {
        List<Map<String, Object>> metrics = metricsReader.read(businessId, from, to);
        if (metrics.isEmpty()) {
            log.debug("브리핑 건너뜀 — 사업장 {} {} {}~{} 지표 0건", businessId, type, from, to);
            return Optional.empty();
        }

        Report report = reports.findByBusinessIdAndReportTypeAndPeriodStart(businessId, type, from)
                .map(existing -> {
                    existing.refreshMetrics(metrics, clock.instant());
                    return existing;
                })
                .orElseGet(() -> reports.save(
                        Report.ofMetrics(businessId, type, from, to, metrics, clock.instant())));
        reports.flush();   // id 가 있어야 Job 파라미터에 담는다
        requestSentence(report);
        return Optional.of(report);
    }

    /**
     * 문장 생성을 큐에 건다 (20.7).
     *
     * <p><b>실패해도 브리핑을 되돌리지 않는다.</b> 지표는 이미 저장됐고 그것만으로 정상
     * 산출물이다 — 워커가 안 떠 있다고 브리핑이 통째로 없어지면 안 된다. 다음 주 배치가
     * 다시 건다.
     *
     * <p><b>{@code enqueueIfIdle} 이 아니다.</b> 그쪽은 (사업장, 종류)로만 막아서, 주간과
     * 월간이 같은 날 겹치는 매월 1일 월요일에 <b>둘 중 하나가 문장을 못 받는다.</b> 브리핑은
     * 행마다 문장이 따로 필요하므로 매번 건다.
     */
    private void requestSentence(Report report) {
        try {
            jobs.enqueue(report.getBusinessId(), null, JobType.BRIEFING_GENERATION,
                    Map.of("briefingId", report.getId()));
        } catch (RuntimeException e) {
            log.warn("브리핑 {} 문장 생성을 걸지 못했다 — 지표는 남는다", report.getId(), e);
        }
    }

    // --- 워커 통로 (20.7) ----------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Snapshot snapshot(Long briefingId) {
        Report report = find(briefingId);
        return new Snapshot(report.getBusinessId(), report.getReportType().name(),
                report.getMetrics());
    }

    @Override
    @Transactional
    public void applySentence(Long briefingId, String headline, String content, boolean partial) {
        find(briefingId).applySentence(headline, content, partial);
    }

    @Override
    @Transactional
    public void markFailed(Long briefingId) {
        find(briefingId).markSentenceFailed();
    }

    private Report find(Long briefingId) {
        return reports.findById(briefingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
    }

    /** 목록 (15.2). {@code periodType} 이 없으면 주간·월간을 섞어 최신순으로 준다. */
    @Transactional(readOnly = true)
    public Page<Report> list(Long businessId, ReportType type, Pageable pageable) {
        guard.assertOwnsBusiness(businessId);
        return type == null
                ? reports.findByBusinessIdOrderByPeriodStartDesc(businessId, pageable)
                : reports.findByBusinessIdAndReportTypeOrderByPeriodStartDesc(
                        businessId, type, pageable);
    }

    /**
     * 상세 (15.2). 직접 경로라 소유권을 명시적으로 본다 — 남의 것이면 {@code 403} 이다
     * ({@code /tax-drafts/{id}}·{@code /exports/{id}} 와 같은 규칙).
     */
    @Transactional(readOnly = true)
    public Report get(Long briefingId) {
        Report report = reports.findById(briefingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        guard.assertOwned(report);
        return report;
    }
}
