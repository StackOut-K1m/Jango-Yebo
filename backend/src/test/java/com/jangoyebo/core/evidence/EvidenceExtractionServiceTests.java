package com.jangoyebo.core.evidence;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 증빙 추출 결과 영속화 (API 20.4 콜백 → {@code EVIDENCE_EXTRACTION}·{@code EVIDENCE_ITEM}).
 *
 * <p><b>본문은 합의 문서 부록 B 를 그대로 옮겼다.</b> 손으로 지어낸 예시가 아니라 워커 코드가
 * 조립하는 것을 찍은 값이고, 계약이 바뀌면 같이 바뀐다. 여기서 확인하는 것은 우리 구현의 내부가
 * 아니라 <b>그 계약을 우리가 받아낼 수 있는가</b> 다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class EvidenceExtractionServiceTests {

    @Autowired private EvidenceExtractionService service;
    @Autowired private EvidenceExtractionRepository extractionRepository;
    @Autowired private EvidenceItemRepository itemRepository;
    @Autowired private EvidenceRepository evidenceRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobService jobService;

    private Long evidenceId;
    private Long businessId;
    private Long userId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '증빙') RETURNING id
                """, Long.class, "ev-" + unique + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '증빙 사업장') RETURNING id
                """, Long.class, userId);
        evidenceId = jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, original_filename, storage_key, mime_type, file_size)
                VALUES (?, ?, '영수증.jpg', ?, 'image/jpeg', 1024) RETURNING id
                """, Long.class, businessId, userId, "evidences/" + businessId + "/" + unique + ".jpg");
    }


    /** 기본 모드로 접수된 Job. 파라미터에 extractionMode 가 없으면 STANDARD 다(20.4). */
    private Job job() {
        return jobService.enqueue(businessId, userId, JobType.EVIDENCE_EXTRACTION,
                Map.of("evidenceId", evidenceId, "fileObjectKey", "evidences/1.jpg"));
    }

    /** 재추출 Job. */
    private Job job(String extractionMode) {
        return jobService.enqueue(businessId, userId, JobType.EVIDENCE_EXTRACTION,
                Map.of("evidenceId", evidenceId, "fileObjectKey", "evidences/1.jpg",
                        "extractionMode", extractionMode));
    }

    /** 합의 문서 부록 B 의 본문. 값을 바꾸지 않는다. */
    private Map<String, Object> fixture() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "COMPLETED");
        data.put("modelName", "stub");
        data.put("modelVersion", null);
        data.put("promptVersion", null);
        data.put("pipeline", "STUB");
        data.put("evidenceId", evidenceId);
        data.put("supplierName", "서울우유 광주대리점");
        data.put("supplierBusinessNo", "124-81-00998");
        data.put("issuedDate", "2026-08-30");
        data.put("supplyAmount", 141818);
        data.put("taxableAmount", null);
        data.put("taxFreeAmount", null);
        data.put("vatAmount", 14182);
        data.put("discountAmount", null);
        data.put("totalAmount", 156000);
        data.put("items", List.of(
                itemRow("서울우유 1L", 40.0, "개", 2900, 116000,
                        Map.of("x", 0.12, "y", 0.31, "width", 0.55, "height", 0.04)),
                itemRow("바나나우유 240ml", 25.0, "개", 1600, 40000, null)));
        data.put("fieldConfidence", Map.of(
                "supplierName", 0.95, "supplierBusinessNo", 0.9, "issuedDate", 0.97,
                "totalAmount", 0.99, "vatAmount", 0.96, "items", 0.88));
        data.put("warnings", List.of("STUB 파이프라인 — 고정 응답입니다."));
        data.put("validation", Map.of(
                "amountsConsistent", true, "itemsSumConsistent", true,
                "businessNoValid", true, "dateValid", true,
                "needsReview", false, "reviewReasons", List.of(), "issues", List.of()));
        return data;
    }

    private Map<String, Object> itemRow(String name, Double qty, String unit,
                                        Integer unitPrice, Integer amount, Map<String, Object> box) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rawName", name);
        row.put("quantity", qty);
        row.put("unit", unit);
        row.put("unitPrice", unitPrice);
        row.put("amount", amount);
        row.put("discountAmount", null);
        row.put("boundingBox", box);
        return row;
    }

    @Test
    void storesTheExtractionAndItsItems() {
        Long extractionId = service.apply(job(), fixture());

        assertThat(extractionId).isNotNull();
        EvidenceExtraction saved = extractionRepository.findById(extractionId).orElseThrow();
        assertThat(saved.getPipeline()).isEqualTo("STUB");
        assertThat(saved.getStatus()).isEqualTo("COMPLETED");
        assertThat(saved.isCurrent()).isTrue();

        List<EvidenceItem> items = itemRepository.findByExtractionIdOrderByIdAsc(extractionId);
        assertThat(items).hasSize(2);
        assertThat(items.getFirst().getRawName()).isEqualTo("서울우유 1L");
        assertThat(items.getFirst().getAmount()).isEqualByComparingTo("116000");
        assertThat(items.getFirst().getSourceCoordinates()).containsKey("x");
        assertThat(items.get(1).getSourceCoordinates())
                .as("VLM 은 좌표를 안 준다 — null 이 정상이다")
                .isNull();
    }

    /**
     * ⑤ 워커는 {@code modelVersion} 을 항상 null 로 보내고 {@code pipeline=STUB} 을 쓴다.
     * ERD 의 NN·CHECK 를 그대로 따랐으면 여기서 INSERT 가 깨졌다.
     */
    @Test
    void acceptsNullModelVersionAndStubPipeline() {
        Long extractionId = service.apply(job(), fixture());

        EvidenceExtraction saved = extractionRepository.findById(extractionId).orElseThrow();
        assertThat(saved.getModelVersion()).isNull();
        assertThat(saved.getPipeline()).isEqualTo("STUB");
    }

    /** ① 헤더 확장 필드와 validation 은 컬럼이 아니라 header_data 에 통째로 남는다. */
    @Test
    @SuppressWarnings("unchecked")
    void keepsHeaderFieldsAndValidationInJsonb() {
        Long extractionId = service.apply(job(), fixture());

        Map<String, Object> header =
                extractionRepository.findById(extractionId).orElseThrow().getHeaderData();
        assertThat(header).containsKeys("supplierName", "totalAmount", "taxableAmount",
                "fieldConfidence", "warnings", "validation");
        assertThat((Map<String, Object>) header.get("validation")).containsEntry("needsReview", false);
        assertThat(header)
                .as("품목과 실행 속성은 전용 자리가 있다 — 중복 저장하면 정본이 둘이 된다")
                .doesNotContainKeys("items", "pipeline", "modelName", "status");
    }

    /**
     * 사용자가 종류를 안 골랐으면(= {@code OTHER}) 모델 판정을 쓴다. 계약 v1.21 이
     * {@code evidenceType} 을 넣기 전에는 항상 null 이라 종류가 영영 {@code OTHER} 로 남았다.
     */
    @Test
    void fillsTheTypeWhenTheUserDidNotChoose() {
        Map<String, Object> data = fixture();
        data.put("evidenceType", "TAX_INVOICE");

        service.apply(job(), data);

        assertThat(evidenceRepository.findById(evidenceId).orElseThrow().getEvidenceType())
                .isEqualTo(EvidenceType.TAX_INVOICE);
    }

    /**
     * <b>사용자가 고른 종류는 모델이 덮지 못한다.</b> 도메인 규칙 4 — 세무 값을 자동으로
     * 확정하지 않는다. 판별 정확도도 아직 측정되지 않았다(AI 2번 회신, 2026-09-10).
     *
     * <p>이 테스트가 없으면 업로드에서 {@code TAX_INVOICE} 를 고른 증빙에 모델이
     * {@code RECEIPT} 를 내는 순간 사용자 선택이 조용히 사라진다.
     */
    @Test
    void neverOverwritesATypeTheUserChose() {
        jdbc.update("UPDATE core.evidence SET evidence_type = 'TAX_INVOICE' WHERE id = ?",
                evidenceId);
        Map<String, Object> data = fixture();
        data.put("evidenceType", "RECEIPT");

        service.apply(job(), data);

        assertThat(evidenceRepository.findById(evidenceId).orElseThrow().getEvidenceType())
                .as("모델이 RECEIPT 를 내도 사용자가 고른 TAX_INVOICE 가 남아야 한다")
                .isEqualTo(EvidenceType.TAX_INVOICE);
    }

    /** 워커가 모르는 값을 보내면 거절하지 않고 기존 값을 유지한다 — 힌트일 뿐이다. */
    @Test
    void keepsTheTypeWhenTheWorkerSendsSomethingUnknown() {
        Map<String, Object> data = fixture();
        data.put("evidenceType", "받아본적없는종류");

        service.apply(job(), data);

        assertThat(evidenceRepository.findById(evidenceId).orElseThrow().getEvidenceType())
                .isEqualTo(EvidenceType.OTHER);
    }

    /** 발행일은 JSONB 안에만 두면 기간 필터가 인덱스를 못 탄다(부록 C). */
    @Test
    void copiesIssuedDateOntoTheEvidenceRow() {
        service.apply(job(), fixture());

        Evidence evidence = evidenceRepository.findById(evidenceId).orElseThrow();
        assertThat(evidence.getIssuedOn()).isEqualTo(LocalDate.of(2026, 8, 30));
        assertThat(evidence.getProcessingStatus())
                .as("추출이 끝나도 확정은 아니다 — 도메인 규칙 4")
                .isEqualTo(EvidenceStatus.EXTRACTED);
    }

    /** 재추출은 이전 것을 지우지 않고 내린다. 부분 유일 인덱스가 현재본을 하나로 강제한다. */
    @Test
    void supersedesThePreviousExtractionInsteadOfDeleting() {
        Long first = service.apply(job(), fixture());
        Long second = service.apply(job(), fixture());

        assertThat(extractionRepository.findById(first).orElseThrow().isCurrent()).isFalse();
        assertThat(extractionRepository.findById(second).orElseThrow().isCurrent()).isTrue();
        assertThat(extractionRepository.findByEvidenceIdAndCurrentTrue(evidenceId))
                .get().extracting(EvidenceExtraction::getId).isEqualTo(second);
    }

    /** ② 에누리는 양수로 받고 순액은 우리가 계산한다 — 음수 품목 행을 만들지 않는다. */
    @Test
    void treatsDiscountAsPositiveAndComputesNetAmount() {
        Map<String, Object> data = fixture();
        Map<String, Object> row = itemRow("우유", 10.0, "개", 2900, 29000, null);
        row.put("discountAmount", 3000);
        data.put("items", List.of(row));

        Long extractionId = service.apply(job(), data);

        EvidenceItem item = itemRepository.findByExtractionIdOrderByIdAsc(extractionId).getFirst();
        assertThat(item.getAmount()).isEqualByComparingTo("29000");
        assertThat(item.getDiscountAmount()).isEqualByComparingTo("3000");
        assertThat(item.netAmount()).isEqualByComparingTo("26000");
    }

    /**
     * 증빙이 지워진 뒤에 콜백이 오는 경우. FK 위반으로 터지면 호출부가 Job 을 되돌리게 되고,
     * 워커는 5xx 를 받아 낫지 않는 재시도를 반복한다.
     */
    @Test
    void skipsQuietlyWhenTheEvidenceIsGone() {
        Map<String, Object> data = fixture();
        data.put("evidenceId", 9_999_999L);

        assertThat(service.apply(job(), data)).isNull();
    }

    /** rawName 이 없는 행은 버린다. NOT NULL 위반으로 나머지 품목까지 잃지 않게. */
    @Test
    void dropsItemRowsWithoutAName() {
        Map<String, Object> data = fixture();
        data.put("items", List.of(
                itemRow("정상", 1.0, "개", 1000, 1000, null),
                itemRow(null, 1.0, "개", 1000, 1000, null)));

        Long extractionId = service.apply(job(), data);

        assertThat(itemRepository.findByExtractionIdOrderByIdAsc(extractionId)).hasSize(1);
    }

    /** 금액을 double 로 다루면 반올림이 샌다. 문자열을 거쳐 BigDecimal 로 만든다. */
    @Test
    void averagesFieldConfidenceWithoutFloatDrift() {
        Long extractionId = service.apply(job(), fixture());

        BigDecimal confidence =
                extractionRepository.findById(extractionId).orElseThrow().getConfidence();
        // (0.95 + 0.9 + 0.97 + 0.99 + 0.96 + 0.88) / 6 = 0.941666…
        assertThat(confidence).isEqualByComparingTo("0.942");
    }

    /**
     * 「재추출이 실제로 고쳤나」에 답하려면 어느 모드로 뽑았는지가 행에 남아야 한다.
     *
     * <p>{@code extractionMode} 는 <b>요청 필드라 콜백 본문에 없다.</b> Job 파라미터에만 있고
     * 그 Job 은 정리 배치가 지운다 — 지워지기 전에 추출 행으로 옮기는 것이 이 동작이다.
     */
    @Test
    void keepsTheExtractionModeFromTheJob() {
        Long extractionId = service.apply(job("THOROUGH"), fixture());

        assertThat(extractionRepository.findById(extractionId).orElseThrow().getExtractionMode())
                .isEqualTo("THOROUGH");
    }

    /**
     * 파라미터에 모드가 없으면 {@code STANDARD} 다 — 워커 기본값과 같다(20.4).
     *
     * <p>「모름」으로 두지 않는 이유는, 값을 안 보냈다는 것이 곧 기본 모드로 돌았다는 뜻이기
     * 때문이다. null 로 두면 V17 이전 행과 구분이 안 된다.
     */
    @Test
    void defaultsToStandardWhenTheJobDoesNotSayOtherwise() {
        Long extractionId = service.apply(job(), fixture());

        assertThat(extractionRepository.findById(extractionId).orElseThrow().getExtractionMode())
                .isEqualTo("STANDARD");
    }

}
