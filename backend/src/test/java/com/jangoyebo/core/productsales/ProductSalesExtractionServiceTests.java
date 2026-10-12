package com.jangoyebo.core.productsales;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매출 인쇄물 추출 결과 영속화 (API 20.9 콜백 → {@code PRODUCT_SALES_EXTRACTION}).
 *
 * <p><b>본문은 합의 문서 부록 B 를 그대로 옮겼다</b> — {@code scripts.sample_callback
 * product-sales} 의 출력이고, 워커 쪽 {@code tests/ocr/test_callback_contract.py} 가 같은 본문을
 * 실제 콜백과 대조한다. 여기서 보는 것은 우리 구현의 내부가 아니라 <b>그 계약을 받아낼 수
 * 있는가</b> 다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class ProductSalesExtractionServiceTests {

    @Autowired private ProductSalesExtractionService service;
    @Autowired private ProductSalesExtractionRepository repository;
    @Autowired private JobService jobService;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long userId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, "ps-" + unique + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '카페') RETURNING id
                """, Long.class, userId);
    }

    private Job job(String captureType) {
        return jobService.enqueue(businessId, userId, JobType.PRODUCT_SALES_OCR,
                Map.of("captureType", captureType, "fileObjectKeys", List.of("product-sales/1-1.jpg")));
    }

    /** 부록 B 본문. 여기 없는 필드는 계약에도 없다. */
    private Map<String, Object> callback() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("productName", "카페라떼");
        row.put("quantity", 47.0);
        row.put("grossAmount", 163000);
        row.put("discountAmount", 1050);
        row.put("netAmount", 147227);
        row.put("vatAmount", 14723);
        row.put("billedAmount", 161950);
        row.put("share", 0.24);
        row.put("confidence", 0.91);

        Map<String, Object> printedTotal = new LinkedHashMap<>();
        printedTotal.put("quantity", 139.0);
        printedTotal.put("grossAmount", 690000);
        printedTotal.put("discountAmount", 19550);
        printedTotal.put("netAmount", null);
        printedTotal.put("vatAmount", null);
        printedTotal.put("billedAmount", 670450);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "COMPLETED");
        data.put("modelName", "stub");
        data.put("modelVersion", null);
        data.put("promptVersion", null);
        data.put("pipeline", "STUB");
        data.put("period", Map.of("start", "2022-12-01", "end", "2022-12-14", "source", "HEADER"));
        data.put("columnsDetected", List.of("productName", "quantity", "grossAmount",
                "discountAmount", "netAmount", "vatAmount", "billedAmount", "share"));
        data.put("items", List.of(row));
        data.put("printedTotal", printedTotal);
        data.put("warnings", List.of("3행 금액을 읽지 못했습니다", "STUB 파이프라인 — 고정 응답입니다."));
        data.put("validation", Map.of("rowArithmeticConsistent", true,
                "printedTotalConsistent", true, "inconsistentRows", List.of(),
                "needsReview", false, "reviewReasons", List.of(), "issues", List.of()));
        return data;
    }

    @Test
    void storesTheExtractionRecord() {
        Job job = job("PRINTOUT");

        Long id = service.apply(job, callback());

        ProductSalesExtraction saved = repository.findById(id).orElseThrow();
        assertThat(saved.getBusinessId()).isEqualTo(businessId);
        assertThat(saved.getPeriodStart()).isEqualTo(LocalDate.of(2022, 12, 1));
        assertThat(saved.getPeriodEnd()).isEqualTo(LocalDate.of(2022, 12, 14));
        assertThat(saved.getPeriodSource()).isEqualTo("HEADER");
        assertThat(saved.getPipeline()).isEqualTo("STUB");
        assertThat(saved.getColumnsDetected()).contains("billedAmount", "share");
        assertThat(saved.getPrintedTotal()).containsEntry("billedAmount", 670450);
        assertThat(saved.getValidation()).containsEntry("needsReview", false);
        assertThat(saved.needsReview()).isFalse();
    }

    /**
     * <b>상품 행은 저장하지 않는다</b>(14.7). 사용자가 확인한 뒤 14.4 가 쓴다 —
     * 여기서 쓰면 오인식이 확인 없이 구성비가 된다.
     */
    @Test
    void doesNotWriteAnyProductRows() {
        service.apply(job("PRINTOUT"), callback());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.product_sales_period WHERE business_id = ?
                """, Integer.class, businessId)).isZero();
    }

    /**
     * 콜백에 {@code businessId} 가 없다(합의 ⑦). Job 이 곧 사업장이라 거기서 얻는다 —
     * {@code captureType} 도 요청에만 있어 Job 파라미터에서 되찾는다.
     */
    @Test
    void takesBusinessAndCaptureTypeFromTheJob() {
        Long id = service.apply(job("SCREEN"), callback());

        ProductSalesExtraction saved = repository.findById(id).orElseThrow();
        assertThat(saved.getCaptureType()).isEqualTo("SCREEN");
        assertThat(saved.getJobId()).isNotNull();
    }

    /** 스위퍼가 회수해 재실행하면 같은 Job 의 콜백이 두 번 온다. 두 행이 되면 안 된다. */
    @Test
    void isIdempotentForTheSameJob() {
        Job job = job("PRINTOUT");

        Long first = service.apply(job, callback());
        Long again = service.apply(job, callback());

        assertThat(again).isEqualTo(first);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.product_sales_extraction WHERE business_id = ?
                """, Integer.class, businessId)).isEqualTo(1);
    }

    /**
     * 요구 1 — 헤더에 기간이 없으면 <b>null 이 정상</b>이다. 촬영 시각으로 채우면 월중에
     * 뽑은 반달치가 그 달 전체가 돼 오류 없이 틀린 답이 나온다.
     */
    @Test
    void leavesThePeriodEmptyWhenTheHeaderHadNone() {
        Map<String, Object> data = callback();
        data.remove("period");

        ProductSalesExtraction saved =
                repository.findById(service.apply(job("PRINTOUT"), data)).orElseThrow();

        assertThat(saved.getPeriodStart()).isNull();
        assertThat(saved.getPeriodEnd()).isNull();
        assertThat(saved.getPeriodSource()).isNull();
    }

    /** ⑤ 인쇄된 구성비는 대조용이다 — 저장 컬럼이 없고, 어긋나도 저장을 막지 않는다. */
    @Test
    void keepsSavingEvenWhenThePrintedShareDisagrees() {
        Map<String, Object> data = callback();
        @SuppressWarnings("unchecked")
        Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) ((List<?>) data.get("items")).get(0));
        row.put("share", 0.90);          // 인쇄 값이 우리 계산(1.0)과 크게 어긋난다
        data.put("items", List.of(row));

        assertThat(service.apply(job("PRINTOUT"), data)).isNotNull();
    }
}
