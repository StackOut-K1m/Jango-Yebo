package com.jangoyebo.core.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Page<Report> findByBusinessIdAndReportTypeOrderByPeriodStartDesc(
            Long businessId, ReportType reportType, Pageable pageable);

    Page<Report> findByBusinessIdOrderByPeriodStartDesc(Long businessId, Pageable pageable);

    /** {@code uk_report_period} 와 같은 키다. 같은 기간을 다시 돌리면 이 행을 갈아 끼운다. */
    Optional<Report> findByBusinessIdAndReportTypeAndPeriodStart(
            Long businessId, ReportType reportType, LocalDate periodStart);
}
