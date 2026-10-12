package com.jangoyebo.core.productsales;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 매출현황 인쇄물 OCR 등록 (API 14.7, P1-22).
 *
 * <p><b>확인하는 것은 응답 모양이 아니라 파일과 Job 이 맞물리는가</b>다. 워커는 우리가 만든
 * 키로만 파일을 찾고, 못 찾으면 202 를 주기 전에 400 으로 끝낸다 — 그 400 은
 * {@code < 500} 이라 재시도로 낫지 않는다. 키가 어긋나면 <b>모든 추출이 조용히 실패</b>한다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ProductSalesOcrTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("jango.storage.root", () -> storageRoot.toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "ps-ocr-" + unique + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '카페') RETURNING id
                """, Long.class, userId);
    }

    private MockMultipartFile jpeg(String name) {
        return new MockMultipartFile("files", name, "image/jpeg", ("가짜JPEG-" + name).getBytes());
    }

    private ResultActions upload(String captureType, MockMultipartFile... files) throws Exception {
        var request = multipart("/api/v1/businesses/{id}/product-sales/ocr", businessId);
        for (MockMultipartFile file : files) {
            request = request.file(file);
        }
        if (captureType != null) {
            request = (org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder)
                    request.param("captureType", captureType);
        }
        return mockMvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private Long jobId() {
        return jdbc.queryForObject("""
                SELECT id FROM core.job WHERE business_id = ? AND job_type = 'PRODUCT_SALES_OCR'
                """, Long.class, businessId);
    }

    @Test
    void acceptsPhotosAndQueuesExtraction() throws Exception {
        upload("PRINTOUT", jpeg("1.jpg"), jpeg("2.jpg"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").isNumber())
                .andExpect(jsonPath("$.data.jobType").value("PRODUCT_SALES_OCR"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));
    }

    /**
     * <b>이 테스트가 이 파일의 이유다.</b> 키가 합의 ⑧-1 형식이어야 하고 그 경로에 파일이
     * 실제로 있어야 한다. 여러 장이라 <b>순번</b>이 붙는다 — 워커가 장 경계를 이어 붙일 때
     * 그 순서를 쓴다.
     */
    @Test
    void writesEveryFileAtTheAgreedKey() throws Exception {
        upload("PRINTOUT", jpeg("first.jpg"), jpeg("second.jpg"))
                .andExpect(status().isAccepted());

        Long jobId = jobId();
        assertThat(storageRoot.resolve("product-sales/%d/%d-1.jpg".formatted(businessId, jobId)))
                .exists()
                .hasContent("가짜JPEG-first.jpg");
        assertThat(storageRoot.resolve("product-sales/%d/%d-2.jpg".formatted(businessId, jobId)))
                .exists()
                .hasContent("가짜JPEG-second.jpg");
    }

    /** Job params 가 워커 요청의 재료다. 키가 여기와 저장 위치 양쪽에서 같아야 한다. */
    @Test
    void putsTheKeysIntoJobParams() throws Exception {
        upload("SCREEN", jpeg("1.jpg")).andExpect(status().isAccepted());

        Long jobId = jobId();
        assertThat(jdbc.queryForObject("""
                SELECT params ->> 'captureType' FROM core.job WHERE id = ?
                """, String.class, jobId)).isEqualTo("SCREEN");
        assertThat(jdbc.queryForObject("""
                SELECT params -> 'fileObjectKeys' ->> 0 FROM core.job WHERE id = ?
                """, String.class, jobId))
                .isEqualTo("product-sales/%d/%d-1.jpg".formatted(businessId, jobId));
    }

    /** 촬영 유형을 안 주면 인쇄물이다 — 14.7 이 1차 경로로 못박은 것이 감열 인쇄물이다. */
    @Test
    void defaultsCaptureTypeToPrintout() throws Exception {
        upload(null, jpeg("1.jpg")).andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("""
                SELECT params ->> 'captureType' FROM core.job WHERE id = ?
                """, String.class, jobId())).isEqualTo("PRINTOUT");
    }

    /**
     * 이미 등록된 상품명을 함께 넘긴다. 워커가 이 목록에 <b>스냅되도록 유도</b>해 표기
     * 흔들림과 오인식을 줄인다 — 월 단위 입력은 중간 검토가 없어 이 스냅이 더 중요하다.
     */
    @Test
    void passesAlreadyRegisteredProductNamesToTheWorker() throws Exception {
        mockMvc.perform(post("/api/v1/businesses/{id}/product-sales", businessId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "periodStart": "2026-11-01", "periodEnd": "2026-11-30",
                                  "items": [ { "productName": "카페라떼", "billedAmount": 100 } ]
                                }
                                """))
                .andExpect(status().isOk());

        upload("PRINTOUT", jpeg("1.jpg")).andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("""
                SELECT params -> 'knownProducts' ->> 0 FROM core.job WHERE id = ?
                """, String.class, jobId()))
                .as("정규화 키가 아니라 인쇄된 원문이어야 모델이 스냅할 수 있다")
                .isEqualTo("카페라떼");
    }

    /** 첫 등록이면 스냅할 대상이 없다. 빈 목록이 정상이고 추출은 된다. */
    @Test
    void sendsAnEmptyKnownProductListOnTheFirstUpload() throws Exception {
        upload("PRINTOUT", jpeg("1.jpg")).andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("""
                SELECT jsonb_array_length(params -> 'knownProducts') FROM core.job WHERE id = ?
                """, Integer.class, jobId())).isZero();
    }

    @Test
    void rejectsAnUnknownCaptureType() throws Exception {
        upload("VIDEO", jpeg("1.jpg")).andExpect(status().isBadRequest());
    }

    /**
     * <b>형식 검증이 Job 생성보다 먼저다.</b> 만든 뒤에 걸리면 아무것도 안 한 Job 이 목록에
     * 남고, 사장님은 그것이 실패한 추출인지 알 수 없다.
     */
    @Test
    void leavesNoJobWhenAFileIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/v1/businesses/{id}/product-sales/ocr", businessId)
                        .file(jpeg("1.jpg"))
                        .file(new MockMultipartFile("files", "표.xlsx",
                                "application/vnd.ms-excel", "엑셀".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.job WHERE business_id = ?
                """, Integer.class, businessId)).isZero();
    }

    /** 여러 장 곱하기 80행이면 워커의 출력 상한과 스위퍼 임계값에 걸린다. */
    @Test
    void rejectsTooManyFiles() throws Exception {
        MockMultipartFile[] files = new MockMultipartFile[11];
        for (int i = 0; i < files.length; i++) {
            files[i] = jpeg(i + ".jpg");
        }

        upload("PRINTOUT", files).andExpect(status().isBadRequest());
    }

    /** 남의 사업장에는 올릴 수 없다. */
    @Test
    void rejectsAnotherOwnersBusiness() throws Exception {
        String other = tokenProvider.createAccessToken(
                jdbc.queryForObject("""
                        INSERT INTO core.users (email, password_hash, name)
                        VALUES (?, 'x', '남') RETURNING id
                        """, Long.class, "ocr-other-" + System.nanoTime() + "@test.local"),
                "other@test.local");

        mockMvc.perform(multipart("/api/v1/businesses/{id}/product-sales/ocr", businessId)
                        .file(jpeg("1.jpg"))
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
    }
}
