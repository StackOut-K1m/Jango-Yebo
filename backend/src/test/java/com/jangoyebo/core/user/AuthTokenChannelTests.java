package com.jangoyebo.core.user;

import com.jangoyebo.NeedsDocker;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refresh Token 을 어느 채널로 주고받는가 (API 2.1 · 6.2).
 *
 * <p><b>왜 이 테스트가 있는가</b>: 앱은 {@code HttpOnly} 쿠키를 읽지 못해 Access Token 이
 * 만료되면 갱신할 방법이 없었다 — 30분마다 다시 로그인했다. 그래서 본문 채널을 열었는데,
 * 이때 <b>헤더만 보고 채널을 가르면 웹이 위험해진다</b>: XSS 스크립트가
 * {@code X-Client-Type: app} 을 붙여 쿠키로 갱신을 요청하면 Refresh Token 을 본문으로
 * 읽어 갈 수 있다.
 *
 * <p>그래서 규칙은 「받은 곳으로 돌려준다」이고, 아래 5개가 그 규칙 전체다. 마지막 두 개
 * (웹 회귀 · 쿠키+앱헤더)가 없으면 이 기능은 쿠키를 쓴 의미를 없애면서 통과한다.
 *
 * <p>Docker 가 필요하다(Testcontainers).
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class AuthTokenChannelTests {

    private static final String SIGNUP = "/api/v1/auth/signup";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";

    private static final String CLIENT_TYPE = "X-Client-Type";
    private static final String APP = "app";
    private static final String REFRESH_COOKIE = "refreshToken";
    /** 본문에 Refresh Token 이 실렸는지 보는 데 쓴다. 헤더의 {@code Set-Cookie} 와 섞이지 않게 따옴표까지 본다. */
    private static final String REFRESH_FIELD = "\"refreshToken\"";

    private static final String PASSWORD = "StrongPass!23";

    @Autowired
    private MockMvc mockMvc;

    private String email;

    @BeforeEach
    void signUp() throws Exception {
        email = "app-" + System.nanoTime() + "@test.local";
        mockMvc.perform(post(SIGNUP).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","name":"문성준"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
    }

    @Test
    void 앱_로그인은_Refresh_Token_을_본문으로_주고_쿠키를_내리지_않는다() throws Exception {
        MvcResult result = login(APP);

        assertThat(refreshTokenOf(result)).isNotBlank();
        assertThat(numberAt(result, "$.data.refreshExpiresIn")).isPositive();
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void 본문_토큰으로_갱신하면_새_토큰_두_개가_나오고_쓴_토큰은_막힌다() throws Exception {
        String first = refreshTokenOf(login(APP));

        MvcResult renewed = mockMvc.perform(refreshWithBody(first)).andExpect(status().isOk()).andReturn();
        assertThat(JsonPath.<String>read(bodyOf(renewed), "$.data.accessToken")).isNotBlank();
        assertThat(refreshTokenOf(renewed)).isNotBlank().isNotEqualTo(first);
        assertThat(renewed.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        // 회전 — 쓴 토큰은 즉시 폐기된다(공통규약 2.1).
        mockMvc.perform(refreshWithBody(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void 본문_토큰으로_로그아웃하면_그_토큰으로_갱신할_수_없다() throws Exception {
        String token = refreshTokenOf(login(APP));

        mockMvc.perform(post(LOGOUT).contentType(MediaType.APPLICATION_JSON)
                        .header(CLIENT_TYPE, APP)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(refreshWithBody(token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    /** 웹 회귀 — 헤더 없는 로그인과 쿠키 갱신은 지금 동작 그대로여야 한다. */
    @Test
    void 웹은_로그인도_갱신도_본문에_Refresh_Token_이_없다() throws Exception {
        MvcResult loggedIn = login(null);

        assertThat(bodyOf(loggedIn)).doesNotContain(REFRESH_FIELD);
        Cookie cookie = loggedIn.getResponse().getCookie(REFRESH_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();

        MvcResult renewed = mockMvc.perform(post(REFRESH).cookie(cookie))
                .andExpect(status().isOk()).andReturn();

        assertThat(bodyOf(renewed)).doesNotContain(REFRESH_FIELD);
        assertThat(renewed.getResponse().getCookie(REFRESH_COOKIE)).isNotNull();
    }

    /** 웹 회귀 — 본문 없는 로그아웃은 지금 프론트가 보내는 모양 그대로다. */
    @Test
    void 웹_로그아웃은_본문이_없어도_쿠키_토큰을_폐기한다() throws Exception {
        Cookie cookie = login(null).getResponse().getCookie(REFRESH_COOKIE);

        mockMvc.perform(post(LOGOUT).cookie(cookie)).andExpect(status().isNoContent());

        mockMvc.perform(post(REFRESH).cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    /**
     * 보안 — 이 테스트가 기능의 존재 이유를 지킨다.
     *
     * <p>쿠키로 온 요청에 본문으로 토큰을 주면, 웹 XSS 가 헤더 한 줄로 {@code HttpOnly} 를
     * 우회한다. 채널은 헤더가 아니라 <b>토큰이 온 곳</b>이 정한다.
     */
    @Test
    void 쿠키로_온_갱신은_앱_헤더가_붙어도_본문으로_토큰을_주지_않는다() throws Exception {
        Cookie cookie = login(null).getResponse().getCookie(REFRESH_COOKIE);

        MvcResult renewed = mockMvc.perform(post(REFRESH).cookie(cookie).header(CLIENT_TYPE, APP))
                .andExpect(status().isOk()).andReturn();

        assertThat(bodyOf(renewed)).doesNotContain(REFRESH_FIELD);
        assertThat(renewed.getResponse().getCookie(REFRESH_COOKIE)).isNotNull();
    }

    private MvcResult login(String clientType) throws Exception {
        MockHttpServletRequestBuilder request = post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD));
        if (clientType != null) {
            request.header(CLIENT_TYPE, clientType);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private MockHttpServletRequestBuilder refreshWithBody(String refreshToken) {
        return post(REFRESH).contentType(MediaType.APPLICATION_JSON)
                .header(CLIENT_TYPE, APP)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(refreshToken));
    }

    private static String refreshTokenOf(MvcResult result) throws Exception {
        return JsonPath.read(bodyOf(result), "$.data.refreshToken");
    }

    private static long numberAt(MvcResult result, String path) throws Exception {
        return JsonPath.<Number>read(bodyOf(result), path).longValue();
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
