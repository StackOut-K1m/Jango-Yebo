package com.jangoyebo.core.evidence;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 증빙 업로드 (API 13.1).
 *
 * <p>여기서 확인하는 것은 응답 모양만이 아니다 — <b>파일이 실제로 합의한 키로 디스크에
 * 쓰였는가</b>가 핵심이다. 워커는 그 키로만 파일을 찾고, 못 찾으면 202 를 주기 전에 400 으로
 * 끝낸다. 키가 어긋나면 <b>모든 추출이 조용히 실패</b>한다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceUploadTests {

    /** 실제 디렉터리에 쓴다. 임시 폴더라 테스트가 끝나면 사라진다. */
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
        String email = "up-" + unique + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '업로더') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '증빙 사업장') RETURNING id
                """, Long.class, userId);
    }

    private ResultActions upload(MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/v1/businesses/{id}/evidences", businessId)
                .file(file)
                .header("Authorization", "Bearer " + token));
    }

    private MockMultipartFile jpeg(String name) {
        return new MockMultipartFile("file", name, "image/jpeg", "가짜JPEG바이트".getBytes());
    }

    @Test
    void acceptsTheFileAndQueuesExtraction() throws Exception {
        upload(jpeg("영수증_0831.jpg"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.evidenceId").isNumber())
                .andExpect(jsonPath("$.data.ocrStatus").value("QUEUED"))
                .andExpect(jsonPath("$.data.ocrJobId").isNumber())
                .andExpect(jsonPath("$.data.fileName").value("영수증_0831.jpg"))
                // 종류를 안 주면 OTHER 로 두고 추출이 추정한다.
                .andExpect(jsonPath("$.data.evidenceType").value("OTHER"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.job
                 WHERE business_id = ? AND job_type = 'EVIDENCE_EXTRACTION'
                """, Integer.class, businessId)).isEqualTo(1);
    }

    /**
     * <b>이 테스트가 이 파일의 이유다.</b> 키가 합의 ⑧-1 형식이어야 하고, 그 경로에 파일이
     * 실제로 있어야 한다. 워커는 이 키만 보고 읽는다.
     */
    @Test
    void writesTheFileAtTheAgreedKey() throws Exception {
        upload(jpeg("영수증.jpg")).andExpect(status().isAccepted());

        String key = jdbc.queryForObject("""
                SELECT storage_key FROM core.evidence WHERE business_id = ?
                """, String.class, businessId);

        assertThat(key).isEqualTo("evidences/%d/%d.jpg".formatted(businessId,
                jdbc.queryForObject("SELECT id FROM core.evidence WHERE business_id = ?",
                        Long.class, businessId)));
        assertThat(storageRoot.resolve(key))
                .as("행만 있고 파일이 없으면 워커가 400 으로 끝낸다")
                .exists();
        assertThat(Files.readString(storageRoot.resolve(key))).isEqualTo("가짜JPEG바이트");
    }

    /** Job 이 워커에 넘길 파라미터. 키가 여기와 저장 위치 양쪽에서 같아야 한다. */
    @Test
    void passesTheKeyToTheExtractionJob() throws Exception {
        upload(jpeg("영수증.jpg")).andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("""
                SELECT params ->> 'fileObjectKey' FROM core.job
                 WHERE business_id = ? AND job_type = 'EVIDENCE_EXTRACTION'
                """, String.class, businessId))
                .isEqualTo(jdbc.queryForObject("""
                        SELECT storage_key FROM core.evidence WHERE business_id = ?
                        """, String.class, businessId));
    }

    /** 확장자는 선언된 MIME 에서 뽑는다 — 파일명을 믿으면 `.jpg` 로 올린 실행 파일이 그대로 저장된다. */
    @Test
    void takesTheExtensionFromMimeNotFilename() throws Exception {
        upload(new MockMultipartFile("file", "받침.exe", "image/png", "PNG".getBytes()))
                .andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("""
                SELECT storage_key FROM core.evidence WHERE business_id = ?
                """, String.class, businessId)).endsWith(".png");
    }

    @Test
    void rejectsUnsupportedTypes() throws Exception {
        upload(new MockMultipartFile("file", "메모.txt", "text/plain", "안녕".getBytes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_FILE_UNSUPPORTED"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.evidence WHERE business_id = ?
                """, Integer.class, businessId))
                .as("거절했으면 행도 파일도 남지 않는다").isZero();
    }

    @Test
    void rejectsFilesOverTenMegabytes() throws Exception {
        upload(new MockMultipartFile("file", "큰그림.jpg", "image/jpeg",
                new byte[10 * 1024 * 1024 + 1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_FILE_UNSUPPORTED"));
    }

    /** 남의 사업장에는 못 올린다. */
    @Test
    void rejectsUploadToSomeoneElsesBusiness() throws Exception {
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 가게') RETURNING id
                """, Long.class, otherUser);

        mockMvc.perform(multipart("/api/v1/businesses/{id}/evidences", otherBusiness)
                        .file(jpeg("영수증.jpg"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /** 업로드와 동시에 거래를 연결한다(13.1 선택 필드). */
    @Test
    void linksToATransactionWhenAsked() throws Exception {
        Long accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '999', 'enc', ?, '999-***-**01', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "h-" + System.nanoTime());
        Long transactionId = jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, source_type, occurred_at, transaction_date,
                     flow_type, amount, description, deduplication_key)
                VALUES (?, ?, 'CSV', now(), current_date, 'WITHDRAWAL', 1000, '테스트', ?)
                RETURNING id
                """, Long.class, businessId, accountId, "d-" + System.nanoTime());

        mockMvc.perform(multipart("/api/v1/businesses/{id}/evidences", businessId)
                        .file(jpeg("영수증.jpg"))
                        .param("transactionId", String.valueOf(transactionId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.linkedTransactionId").value(transactionId));

        assertThat(jdbc.queryForObject("""
                SELECT match_status FROM core.transaction_evidence WHERE transaction_id = ?
                """, String.class, transactionId)).isEqualTo("LINKED");
    }
}
