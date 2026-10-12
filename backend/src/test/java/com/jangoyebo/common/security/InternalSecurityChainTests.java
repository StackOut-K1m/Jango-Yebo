package com.jangoyebo.common.security;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 에서 org.springframework.boot.test.autoconfigure.web.servlet 에서 옮겨졌다.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /internal/**} 서비스 토큰 체인 검증.
 *
 * <p><b>이 테스트가 존재하는 이유</b>: 전용 체인이 없으면 워커 콜백이 사용자 체인에 걸려
 * {@code AUTH_TOKEN_INVALID} 를 받는다. 명세 21·22장이 요구하는 {@code INTERNAL_AUTH_FAILED}
 * 가 아닌데 <b>둘 다 401 이라 눈에 띄지 않고</b>, 워커는 4xx 에 즉시 포기해 결과가 유실된다.
 * 그래서 상태코드가 아니라 <b>{@code error.code} 를 단정</b>한다.
 *
 * <p>서비스 토큰은 {@code build.gradle} 의 {@code AI_SERVICE_TOKEN} 이다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class InternalSecurityChainTests {

    private static final String CALLBACK = "/internal/v1/ai-jobs/999999/result";
    private static final String VALID_TOKEN = "test-service-token";
    private static final String BODY = """
            {"success": true, "data": {"status": "COMPLETED"}, "error": null}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Test
    void rejectsMissingTokenWithInternalAuthFailed() throws Exception {
        mockMvc.perform(post(CALLBACK).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_AUTH_FAILED"));
    }

    @Test
    void rejectsWrongTokenWithInternalAuthFailed() throws Exception {
        mockMvc.perform(post(CALLBACK)
                        .header("Authorization", "Bearer not-the-service-token")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_AUTH_FAILED"));
    }

    /** 일반 사용자 토큰이 내부 API 를 열어서는 안 된다. */
    @Test
    void rejectsValidUserJwtOnInternalPath() throws Exception {
        String userToken = tokenProvider.createAccessToken(1L, "user@test.local");

        mockMvc.perform(post(CALLBACK)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_AUTH_FAILED"));
    }

    /** 인증을 통과하면 도메인 오류가 나온다 — 없는 작업이므로 404 다. */
    @Test
    void acceptsValidServiceToken() throws Exception {
        mockMvc.perform(post(CALLBACK)
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .header("X-Request-Id", "ai-job-999999-result")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
    }

    /** 내부 체인이 사용자 트리로 새면 안 된다. 서비스 토큰은 /api/v1 을 열지 못한다. */
    @Test
    void serviceTokenDoesNotOpenUserApi() throws Exception {
        mockMvc.perform(get("/api/v1/businesses")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    /** 체인을 추가하면서 기존 PUBLIC_PATHS 가 깨지지 않았는지 확인한다. */
    @Test
    void publicPathsRemainOpen() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "nobody@test.local", "password": "WrongPassw0rd!"}
                                """))
                .andExpect(status().isUnauthorized())
                // 인증 실패지 "토큰이 없다"가 아니다 — 경로 자체는 열려 있다는 뜻이다.
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }
}
