package com.jangoyebo.core.productsales;

import com.jangoyebo.core.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 매출 인쇄물 추출 결과를 영속화한다 (API 20.9 콜백 → {@code PRODUCT_SALES_EXTRACTION},
 * 합의 ④ · 「승인 후 순서」 2번).
 *
 * <p><b>상품 행은 저장하지 않는다.</b> 14.7 의 결정("결과는 후보이며 저장하지 않는다")이
 * 그대로다 — 행은 사용자가 검토·확정한 뒤 14.4 가 {@code PRODUCT_SALES_PERIOD} 에 쓴다.
 * 행은 그때까지 {@code JOB.result} 에 있고 클라이언트가 Job 폴링으로 읽는다.
 *
 * <p>그럼에도 추출 <b>기록</b>을 남기는 이유는 수명이다. Job 은 90일 뒤 정리 대상이라
 * (공통규약 23장) 거기 두면 검산 근거가 Job 과 함께 사라진다. 사용자가 저장한 구성비가
 * 나중에 틀린 것으로 드러났을 때 "모델이 그때 무엇을 읽었고 검산이 무엇을 걸렀나"에
 * 답할 수 있어야 한다.
 *
 * <p><b>여기서 던지지 않는다.</b> {@link com.jangoyebo.core.ai.AiResultApplier} 가 감싸고 있지만
 * 이유는 같다 — 콜백은 이미 워커가 성공으로 보고한 결과이고, 저장 실패로 Job 을 되돌리면
 * 워커가 같은 결과를 무한히 다시 보낸다.
 */
@Service
public class ProductSalesExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ProductSalesExtractionService.class);

    /**
     * 인쇄된 구성비와 우리 계산이 이만큼 넘게 벌어지면 로그로 남긴다.
     *
     * <p>워커 실측 열 정확도가 95.0%(n=1,051)이고 인쇄물은 보통 소수 한 자리(24.2%)까지만
     * 찍으므로, 반올림만으로 0.5%p 가까이 벌어질 수 있다. 그보다 넉넉히 잡는다.
     */
    private static final BigDecimal SHARE_TOLERANCE = new BigDecimal("0.01");

    private final ProductSalesExtractionRepository repository;

    public ProductSalesExtractionService(ProductSalesExtractionRepository repository) {
        this.repository = repository;
    }

    /**
     * 콜백 {@code data} 를 저장한다.
     *
     * @param job  {@code PRODUCT_SALES_OCR} Job. <b>사업장은 여기서만 나온다</b> — 콜백에
     *             {@code businessId} 가 없다(합의 ⑦)
     * @return 저장한 추출의 id. 저장하지 않았으면 {@code null}
     */
    @Transactional
    public Long apply(Job job, Map<String, Object> data) {
        // 같은 Job 의 콜백이 두 번 오면(스위퍼 회수 뒤 재실행 등) 두 행이 된다.
        var existing = repository.findByJobId(job.getId());
        if (existing.isPresent()) {
            log.info("작업 {} 의 매출 인쇄물 추출은 이미 저장돼 있다 — 건너뛴다", job.getId());
            return existing.get().getId();
        }

        Map<String, Object> period = asMap(data.get("period"));
        ProductSalesExtraction extraction = repository.save(ProductSalesExtraction.of(
                job.getBusinessId(),
                job.getId(),
                dateOf(period, "start"),
                dateOf(period, "end"),
                asString(period == null ? null : period.get("source")),
                captureTypeOf(job),
                asString(data.get("modelName")) == null ? "unknown" : asString(data.get("modelName")),
                asString(data.get("modelVersion")),
                asString(data.get("promptVersion")),
                asString(data.get("pipeline")) == null ? "VLM" : asString(data.get("pipeline")),
                asString(data.get("status")) == null ? "COMPLETED" : asString(data.get("status")),
                stringsOf(data.get("columnsDetected")),
                asMap(data.get("printedTotal")),
                asMap(data.get("validation")),
                stringsOf(data.get("warnings"))));

        crossCheckPrintedShare(data);

        // 품목명·금액을 로그에 남기지 않는다(명세 20.1). 행 수와 판정만 남긴다.
        log.info("작업 {} 매출 인쇄물 추출 저장 — extractionId={} pipeline={} 기간={}~{} 검토필요={}",
                job.getId(), extraction.getId(), extraction.getPipeline(),
                extraction.getPeriodStart(), extraction.getPeriodEnd(), extraction.needsReview());
        return extraction.getId();
    }

    /**
     * ⑤ 인쇄된 구성비는 <b>저장하지 않고 대조에만 쓴다.</b>
     *
     * <p>저장하면 {@code billed_amount} 와 {@code share} 두 곳에서 같은 사실이 관리되고 반드시
     * 갈라진다. 대신 여기서 한 번 대조한다 — 우리가 계산한 구성비와 인쇄된 값이 다르면
     * <b>금액 오독이거나 분모 선택이 틀린 것</b>이다.
     *
     * <p>⚠ 이 대조는 <b>행 밀림을 잡지 못한다.</b> 구성비도 밀린 숫자 뭉치에 같이 실려 오기
     * 때문이다(워커 실측 0/57). 금액 오독·분모 오선택만 잡는다.
     */
    private void crossCheckPrintedShare(Map<String, Object> data) {
        List<Map<String, Object>> items = rowsOf(data.get("items"));
        if (items.isEmpty()) {
            return;
        }
        BigDecimal denominator = items.stream()
                .map(this::shareBasisOf)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (denominator.signum() == 0) {
            return;
        }

        int compared = 0;
        int mismatched = 0;
        BigDecimal worst = BigDecimal.ZERO;
        for (Map<String, Object> row : items) {
            BigDecimal printed = decimalOf(row.get("share"));
            BigDecimal basis = shareBasisOf(row);
            if (printed == null || basis == null) {
                continue;
            }
            compared++;
            BigDecimal gap = basis.divide(denominator, 4, RoundingMode.HALF_UP)
                    .subtract(printed).abs();
            if (gap.compareTo(SHARE_TOLERANCE) > 0) {
                mismatched++;
                worst = worst.max(gap);
            }
        }
        if (mismatched > 0) {
            log.warn("인쇄된 구성비와 계산값이 {}/{}행에서 어긋난다(최대 {}%p) — "
                            + "금액 오독이거나 분모 선택이 틀렸을 수 있다. 저장은 그대로 진행한다",
                    mismatched, compared, worst.movePointRight(2).setScale(1, RoundingMode.HALF_UP));
        }
    }

    /** 구성비 분모는 {@code COALESCE(billedAmount, grossAmount)} 다(ERD §1.9). */
    private BigDecimal shareBasisOf(Map<String, Object> row) {
        BigDecimal billed = decimalOf(row.get("billedAmount"));
        return billed != null ? billed : decimalOf(row.get("grossAmount"));
    }

    /**
     * 촬영 유형. 요청에만 있고 콜백에는 없어 Job 파라미터에서 되찾는다.
     *
     * <p>못 찾으면 {@code PRINTOUT} 이다 — 14.7 의 1차 입력 경로가 감열 인쇄물이고,
     * {@code NOT NULL} 이라 비울 수 없다.
     */
    private String captureTypeOf(Job job) {
        Map<String, Object> params = job.getParams();
        String raw = params == null ? null : asString(params.get("captureType"));
        if (raw == null) {
            return "PRINTOUT";
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"SCREEN".equals(upper) && !"PRINTOUT".equals(upper)) {
            log.warn("작업 {} 의 captureType 이 \"{}\" 다 — PRINTOUT 으로 저장한다", job.getId(), raw);
            return "PRINTOUT";
        }
        return upper;
    }

    /** 헤더에 기간이 없으면 null 이고 그것이 정상이다(요구 1). 촬영 시각으로 채우지 않는다. */
    private LocalDate dateOf(Map<String, Object> period, String key) {
        String raw = period == null ? null : asString(period.get(key));
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            log.warn("기간 \"{}\" 을 읽을 수 없다 — 비워 둔다", raw);
            return null;
        }
    }

    private String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private List<String> stringsOf(Object value) {
        if (!(value instanceof List<?> raw)) {
            return null;
        }
        return raw.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .toList();
    }

    /** JSON 숫자는 Integer·Long·Double 로 온다. 금액을 double 로 두면 반올림이 샌다. */
    private BigDecimal decimalOf(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        return value instanceof Number n ? new BigDecimal(n.toString()) : null;
    }
}
