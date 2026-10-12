package com.jangoyebo.core.evidence;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import com.jangoyebo.core.storage.UploadStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 증빙 원본 보기 (API 13.1 {@code fileUrl}).
 *
 * <p><b>P0-10 의 반쪽이 비어 있었다.</b> 올리기·추출·검토·매칭은 다 되는데 원본을 볼 경로가
 * 없어, 검토 화면이 「추출된 값이 맞나」를 눈으로 대조할 수 없었다.
 *
 * <p><b>못 보는 경우를 갈라 답해야 한다.</b> 지운 증빙과, 기록은 있는데 파일이 없는 증빙은
 * 사장님이 할 수 있는 일이 다르다 — 앞은 아무것도, 뒤는 다시 올리기다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class EvidenceFileTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private UploadStorage storage;

    private Long userId;
    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "evidencefile-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선')
                RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, userId);
    }

    // --- 픽스처 --------------------------------------------------------------

    /** DB 행만 만든다. 파일은 {@link #writeFile} 이 따로 쓴다 — 둘이 어긋난 경우를 봐야 해서다. */
    private Long evidence(String fileName, String mimeType, boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, evidence_type, original_filename, storage_key,
                     mime_type, file_size, processing_status, deleted_at)
                VALUES (?, ?, 'RECEIPT', ?, ?, ?, 12, 'EXTRACTED', ?) RETURNING id
                """, Long.class, businessId, userId, fileName,
                "evidences/%d/%d.jpg".formatted(businessId, System.nanoTime()), mimeType,
                deleted ? Timestamp.from(Instant.now()) : null);
    }

    private Long evidence(String fileName) {
        return evidence(fileName, "image/jpeg", false);
    }

    private void writeFile(Long evidenceId, String content) {
        String key = jdbc.queryForObject(
                "SELECT storage_key FROM core.evidence WHERE id = ?", String.class, evidenceId);
        try (var out = storage.openWrite(key)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("증빙 파일을 쓰지 못했다", e);
        }
    }

    private ResultActions file(Long evidenceId, String bearer) throws Exception {
        return mockMvc.perform(get("/api/v1/evidences/" + evidenceId + "/file")
                .header("Authorization", "Bearer " + bearer));
    }

    private ResultActions file(Long evidenceId) throws Exception {
        return file(evidenceId, token);
    }

    private String otherUserToken() {
        String otherEmail = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, otherEmail);
        return tokenProvider.createAccessToken(otherUser, otherEmail);
    }

    // --- 원본 내주기 ---------------------------------------------------------

    @Test
    void servesTheStoredBytes() throws Exception {
        Long evidenceId = evidence("영수증.jpg");
        writeFile(evidenceId, "원본 바이트");

        byte[] body = file(evidenceId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("원본 바이트");
    }

    /**
     * <b>저장된 MIME 을 그대로 준다.</b> 확장자로 짐작하지 않는다 — 업로드 때도 선언된 MIME 에서
     * 확장자를 뽑았지 반대가 아니다.
     */
    @Test
    void answersWithTheStoredContentType() throws Exception {
        Long pdf = evidence("계약서.pdf", "application/pdf", false);
        writeFile(pdf, "%PDF-1.4");

        file(pdf).andExpect(header().string("Content-Type", "application/pdf"));
    }

    /**
     * <b>다운로드가 아니라 미리보기다.</b> {@code attachment} 면 검토 화면이 원본을 띄우려 할 때
     * 브라우저가 저장 대화상자를 연다.
     */
    @Test
    void marksTheResponseAsInlinePreview() throws Exception {
        Long evidenceId = evidence("영수증.jpg");
        writeFile(evidenceId, "원본 바이트");

        file(evidenceId).andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.startsWith("inline")));
    }

    // --- 못 보는 경우 --------------------------------------------------------

    /**
     * <b>지운 증빙과 파일이 없는 증빙을 갈라 답한다.</b> 둘 다 {@code EVIDENCE_NOT_FOUND} 로
     * 뭉개면 화면은 목록에서 지워야 할지 다시 올리라고 해야 할지 판단하지 못한다.
     */
    @Test
    void separatesADeletedEvidenceFromAMissingFile() throws Exception {
        Long deleted = evidence("지운영수증.jpg", "image/jpeg", true);
        writeFile(deleted, "원본 바이트");
        Long missing = evidence("사라진영수증.jpg");   // 파일을 쓰지 않는다

        file(deleted).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_NOT_FOUND"));
        file(missing).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_FILE_MISSING"));
    }

    @Test
    void refusesAnotherBusiness() throws Exception {
        Long evidenceId = evidence("영수증.jpg");
        writeFile(evidenceId, "원본 바이트");

        file(evidenceId, otherUserToken()).andExpect(status().isForbidden());
    }

    @Test
    void answersNotFoundForAnUnknownEvidence() throws Exception {
        file(999_999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_NOT_FOUND"));
    }

    // --- fileUrl -------------------------------------------------------------

    /**
     * <b>목록과 상세가 같은 경로를 준다.</b> 화면이 두 곳에서 다른 값을 받으면 미리보기가 한쪽
     * 화면에서만 열린다.
     */
    @Test
    void putsTheSameFileUrlInListAndDetail() throws Exception {
        Long evidenceId = evidence("영수증.jpg");
        writeFile(evidenceId, "원본 바이트");
        String expected = "/api/v1/evidences/" + evidenceId + "/file";

        mockMvc.perform(get("/api/v1/businesses/" + businessId + "/evidences")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].fileUrl").value(expected));

        mockMvc.perform(get("/api/v1/evidences/" + evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.fileUrl").value(expected));
    }

    /**
     * <b>파일이 실제로 없어도 {@code fileUrl} 은 나온다.</b> 목록은 저장소를 건드리지 않는다 —
     * 증빙 100건을 그리면서 파일 존재를 100번 확인하면 목록이 느려진다. 없다는 것은 그 경로를
     * 부를 때 알게 된다.
     */
    @Test
    void stillAdvertisesTheUrlWhenTheFileIsGone() throws Exception {
        Long evidenceId = evidence("사라진영수증.jpg");

        mockMvc.perform(get("/api/v1/evidences/" + evidenceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.fileUrl").exists());
        file(evidenceId).andExpect(status().isNotFound());
    }
}
