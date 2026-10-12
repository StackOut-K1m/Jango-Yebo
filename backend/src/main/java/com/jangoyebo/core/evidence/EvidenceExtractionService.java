package com.jangoyebo.core.evidence;

import com.jangoyebo.core.job.Job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 추출 결과를 증빙에 영속화한다 (API 20.4 콜백 → ERD {@code EVIDENCE_EXTRACTION}·
 * {@code EVIDENCE_ITEM}, 합의 문서 「승인 후 순서」 3번).
 *
 * <p>전에는 워커 결과가 {@code JOB.result} JSONB 에만 들어갔다. Job 은 90일 뒤 정리 대상이라
 * (공통규약 23장) 그대로 두면 <b>추출 결과가 Job 과 함께 사라진다.</b> 증빙은 사장님이 나중에
 * 다시 보는 자료이므로 Job 수명과 분리해야 한다.
 *
 * <p><b>여기서 던지지 않는다.</b> 콜백은 이미 워커가 성공으로 보고한 결과이고, 저장에 실패했다고
 * Job 을 {@code FAILED} 로 만들면 워커가 {@code < 500} 을 영구 실패로 보아 결과를 영영 잃는다
 * (§7.1 상태코드 정책). 저장이 안 되면 로그를 남기고 Job 결과는 그대로 둔다 —
 * {@code JOB.result} 에 원본이 남아 있어 나중에 다시 밀어 넣을 수 있다.
 */
@Service
public class EvidenceExtractionService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceExtractionService.class);

    /**
     * {@code header_data} 에서 빼는 것들. 나머지는 통째로 남긴다(합의 ①).
     *
     * <p>{@code items} 는 별도 테이블로 가고, 나머지 넷은 헤더가 아니라 <b>추출 실행의 속성</b>이라
     * 전용 컬럼이 있다. 중복 저장하면 어느 쪽이 정본인지 모르게 된다.
     */
    private static final List<String> NOT_HEADER =
            List.of("items", "modelName", "modelVersion", "promptVersion", "pipeline", "status");

    private final EvidenceRepository evidenceRepository;
    private final EvidenceExtractionRepository extractionRepository;
    private final EvidenceItemRepository itemRepository;

    public EvidenceExtractionService(EvidenceRepository evidenceRepository,
                                     EvidenceExtractionRepository extractionRepository,
                                     EvidenceItemRepository itemRepository) {
        this.evidenceRepository = evidenceRepository;
        this.extractionRepository = extractionRepository;
        this.itemRepository = itemRepository;
    }

    /**
     * 콜백 {@code data} 를 저장한다.
     *
     * @return 저장한 추출의 id. 저장하지 않았으면 {@code null}
     */
    @Transactional
    public Long apply(Job job, Map<String, Object> data) {
        Long evidenceId = asLong(data.get("evidenceId"));
        if (evidenceId == null) {
            log.warn("추출 결과에 evidenceId 가 없다 — 저장하지 않는다");
            return null;
        }
        Evidence evidence = evidenceRepository.findById(evidenceId).orElse(null);
        if (evidence == null) {
            // 증빙이 지워진 뒤에 콜백이 왔다. FK 위반으로 터지기 전에 여기서 끊는다.
            log.warn("증빙 {} 이 없다 — 추출 결과를 저장하지 않는다", evidenceId);
            return null;
        }

        // 재추출이면 이전 것을 내린다. 지우지 않는 이유는 이전 결과가 정확도 추적의 재료라서다.
        extractionRepository.findByEvidenceIdAndCurrentTrue(evidenceId)
                .ifPresent(EvidenceExtraction::supersede);
        extractionRepository.flush();   // 부분 유일 인덱스가 같은 트랜잭션 안에서 부딪히지 않게

        EvidenceExtraction extraction = extractionRepository.save(EvidenceExtraction.of(
                evidenceId,
                asString(data.get("modelName"), "unknown"),
                asString(data.get("modelVersion"), null),
                asString(data.get("promptVersion"), null),
                asString(data.get("pipeline"), "VLM"),
                asString(data.get("status"), "COMPLETED"),
                extractionModeOf(job),
                headerOf(data),
                overallConfidence(data)));

        List<EvidenceItem> items = itemsOf(extraction.getId(), data.get("items"));
        if (!items.isEmpty()) {
            itemRepository.saveAll(items);
        }

        // 종류·발행일은 추출로 채워질 수 있고, 상태는 EXTRACTED(사용자 확인 대기)로 간다.
        // 도메인 규칙 4 — 추출이 끝났다고 세무 값이 확정된 것은 아니다.
        evidence.applyExtraction(evidenceTypeOf(data), issuedDateOf(data), needsReviewOf(data));

        log.info("증빙 {} 추출 저장 — extractionId={} pipeline={} 품목 {}건",
                evidenceId, extraction.getId(), extraction.getPipeline(), items.size());
        return extraction.getId();
    }

    /**
     * 이 추출을 어느 모드로 요청했나.
     *
     * <p><b>콜백이 아니라 Job 에서 읽는다.</b> {@code extractionMode} 는 요청 필드라 워커가
     * 돌려주지 않는다. Job 이 지워지기 전에 추출 행으로 옮겨 두는 것이 이 메서드의 일이다.
     *
     * <p>파라미터에 없으면 {@code STANDARD} 다 — 워커 기본값과 같다(20.4). 「모름」으로
     * 두지 않는 이유는, 값을 안 보냈다는 것이 곧 기본 모드로 돌았다는 뜻이기 때문이다.
     */
    private String extractionModeOf(Job job) {
        Map<String, Object> params = job == null ? null : job.getParams();
        Object value = params == null ? null : params.get("extractionMode");
        return value instanceof String text && !text.isBlank() ? text : "STANDARD";
    }

    /** {@code items} 와 실행 속성만 빼고 그대로 넣는다. {@code validation}·{@code fieldConfidence} 포함. */
    private Map<String, Object> headerOf(Map<String, Object> data) {
        Map<String, Object> header = new LinkedHashMap<>(data);
        NOT_HEADER.forEach(header::remove);
        return header;
    }

    @SuppressWarnings("unchecked")
    private List<EvidenceItem> itemsOf(Long extractionId, Object raw) {
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                // rawName 이 없으면 행이 아니다. NOT NULL 위반으로 청크 전체가 롤백되는 것을 막는다.
                .filter(row -> asString(row.get("rawName"), null) != null)
                .map(row -> EvidenceItem.of(
                        extractionId,
                        asString(row.get("rawName"), null),
                        asDecimal(row.get("quantity")),
                        asString(row.get("unit"), null),
                        asDecimal(row.get("unitPrice")),
                        asDecimal(row.get("amount")),
                        asDecimal(row.get("discountAmount")),
                        asDecimal(row.get("supplyAmount")),
                        asDecimal(row.get("vatAmount")),
                        asMap(row.get("boundingBox"))))
                .toList();
    }

    /**
     * 전체 신뢰도. 계약에 단일 값이 없어 {@code fieldConfidence} 의 평균을 쓴다.
     *
     * <p>필드별 값이 정본이고 이건 목록 정렬용 요약이다 — {@code header_data} 에 원본이 남는다.
     *
     * <p>⚠ <b>이 값을 품질 게이트로 쓰지 않는다.</b> 신뢰도에는 판별력이 없다 — test 359장에서
     * 임계값 0.7 은 정밀도 23%, 0.85 는 25% 였고 <b>그 데이터의 기저 오류율이 16.7%</b> 다.
     * 무작위로 찍는 것과 다르지 않아 계약 v1.13 이 검토 게이트에서 뺐다. 검토 대상은
     * {@code validation.needsReview} 로만 가른다(합의 ③).
     *
     * <p>더 나쁜 것은 <b>회계에 영향을 주는 오류 6건(날짜 3·합계 3)을 결정적 검사가 0/6 잡는다</b>는
     * 점이다. 어떤 신호도 "여기만 보면 된다"는 뜻으로 쓸 수 없고, 모든 추출이
     * {@code EXTRACTED} 에서 사람의 확인을 기다리는 이유가 이것이다.
     */
    private BigDecimal overallConfidence(Map<String, Object> data) {
        Map<String, Object> byField = asMap(data.get("fieldConfidence"));
        if (byField == null || byField.isEmpty()) {
            return null;
        }
        List<BigDecimal> values = byField.values().stream()
                .map(this::asDecimal)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 3, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 발행일. {@code evidence.issued_on} 에도 넣는 이유는 기간 필터가 그 컬럼을 보기 때문이다
     * (합의 부록 C) — JSONB 안에 있으면 인덱스를 못 탄다.
     */
    private LocalDate issuedDateOf(Map<String, Object> data) {
        String raw = asString(data.get("issuedDate"), null);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            log.warn("발행일 \"{}\" 을 읽을 수 없다 — 비워 둔다", raw);
            return null;
        }
    }

    /**
     * 검산 판정을 목록 정렬 키로 복사한다 (13.2).
     *
     * <p>정본은 {@code header_data.validation} 이고 {@code evidence.needs_review} 는 사본이다.
     * 한 번 더 두는 이유는 JSONB 가 {@code ORDER BY}·{@code WHERE} 의 키가 못 되기 때문이다.
     *
     * <p><b>검산이 없으면 null 이다</b> — 계약에 {@code validation} 이 없던 시절의 결과나
     * 스텁이 그렇다. {@code false} 로 접으면 "판정 못 함"이 "통과"로 읽힌다.
     */
    private Boolean needsReviewOf(Map<String, Object> data) {
        Map<String, Object> validation = asMap(data.get("validation"));
        if (validation == null || !(validation.get("needsReview") instanceof Boolean flag)) {
            return null;
        }
        return flag;
    }

    /** 워커가 종류를 판정했으면 반영한다. 모르는 값이면 사용자가 고른 것을 유지한다. */
    private EvidenceType evidenceTypeOf(Map<String, Object> data) {
        String raw = asString(data.get("evidenceType"), null);
        if (raw == null) {
            return null;
        }
        try {
            return EvidenceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("워커가 모르는 증빙 종류 \"{}\" 를 보냈다 — 기존 값을 유지한다", raw);
            return null;
        }
    }

    private Long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private String asString(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    /** JSON 숫자는 Integer·Long·Double 로 온다. 금액을 double 로 두면 반올림이 새므로 문자열을 거친다. */
    private BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
