package com.jangoyebo.core.business;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사업장 등록 경로 (API 7.1).
 *
 * <p><b>이 테스트가 존재하는 이유</b>: 사업자등록번호를 보내면 500 이 나던 버그를
 * 테스트 76개가 하나도 잡지 못했다. 원인은 커버리지가 아니라 <b>층위</b>였다 —
 * {@code FieldEncryptor} 단위 테스트 9개는 전부 통과하는데, 그 출력(hex 88자)이
 * {@code VARCHAR(64)} 컬럼에 들어가지 않는다는 것은 <b>실제로 INSERT 를 해봐야만</b>
 * 드러난다. 그래서 여기서는 모킹 없이 컨트롤러 → DB 왕복을 그대로 태운다.
 *
 * <p>Docker 가 필요하다(Testcontainers). CI 범위 B 에서는 돌지 않으므로
 * <b>push 전 로컬 {@code ./gradlew test} 가 유일한 관문이다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class BusinessControllerTests {

    private static final String BUSINESSES = "/api/v1/businesses";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;
    private String token;

    @BeforeEach
    void signUpUser() {
        String email = "biz-" + System.nanoTime() + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '테스트') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
    }

    /**
     * 버그의 본체. 명세 형식 그대로 보내면 500 이 났다.
     *
     * <p>암호문 길이는 결정적이다 — hex(IV 16 + 평문 12 + 태그 16) = 88자.
     * 컬럼이 64자였으므로 <b>평문이 무엇이든</b> 들어가지 않았다(0자여야 딱 64자다).
     * 하이픈 유무와 무관했고, 하이픈 없는 값이 400 이던 것은 검증에서 먼저 끊겨
     * 암호화 경로를 아예 밟지 않았기 때문이다.
     */
    @Test
    void createsBusinessWithRegistrationNumber() throws Exception {
        mockMvc.perform(post(BUSINESSES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"온기식당","businessRegNo":"123-45-67890",
                                 "industryType":"FOOD_RESTAURANT","taxationType":"GENERAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.businessId").isNumber());
    }

    /**
     * 컬럼 폭만 고치면 500 은 사라지지만 응답에 <b>암호문 조각</b>이 나갔다 —
     * 마스킹이 암호문에 {@code substring} 을 걸고 있었고 복호화가 없었다.
     * 그래서 상태코드가 아니라 <b>값</b>을 단정한다.
     */
    @Test
    void masksRegistrationNumberFromPlainText() throws Exception {
        mockMvc.perform(post(BUSINESSES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"온기식당","businessRegNo":"123-45-67890",
                                 "industryType":"FOOD_RESTAURANT","taxationType":"GENERAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.businessRegNoMasked").value("***-**-67890"));
    }

    /** 평문이 DB 에 그대로 남지 않아야 한다(ERD 11장). 마스킹만 평문 조각을 갖는다. */
    @Test
    void storesRegistrationNumberEncrypted() throws Exception {
        mockMvc.perform(post(BUSINESSES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"온기식당","businessRegNo":"123-45-67890",
                                 "industryType":"FOOD_RESTAURANT","taxationType":"GENERAL"}
                                """))
                .andExpect(status().isCreated());

        String stored = jdbc.queryForObject("""
                SELECT registration_number FROM core.business WHERE owner_user_id = ?
                """, String.class, userId);

        assertThat(stored).isNotNull().doesNotContain("123-45-67890").doesNotContain("1234567890");
        // hex(IV 16 + 평문 12 + 태그 16) = 88자
        assertThat(stored).hasSize(88).matches("[0-9a-f]+");
    }

    /** 선택 입력이다(API 7.1 에 필수 표시가 없다). 없으면 마스킹도 없다. */
    @Test
    void allowsMissingRegistrationNumber() throws Exception {
        mockMvc.perform(post(BUSINESSES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"온기식당","industryType":"FOOD_RESTAURANT","taxationType":"GENERAL"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.businessRegNoMasked").doesNotExist());
    }

    @Test
    void rejectsMalformedRegistrationNumber() throws Exception {
        mockMvc.perform(post(BUSINESSES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"온기식당","businessRegNo":"1234567890",
                                 "industryType":"FOOD_RESTAURANT","taxationType":"GENERAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 프론트가 "안 온다"고 보고한 두 필드. 미구현이 아니라 <b>보내지 않아서</b> null 이었고,
     * {@code default-property-inclusion: non_null} 이 키를 지운 것이다.
     */
    @Test
    void echoesOptionalProfileFieldsWhenProvided() throws Exception {
        mockMvc.perform(post(BUSINESSES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"온기식당","industryType":"FOOD_RESTAURANT","taxationType":"GENERAL",
                                 "region":"광주 동구","openedAt":"2024-03-01"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.region").value("광주 동구"))
                .andExpect(jsonPath("$.data.openedAt").value("2024-03-01"));
    }

    /** 선택지가 하나뿐인 시점에 선택을 요구하지 않는다. 이 값이 없으면 프론트에 현재 사업장이 없다. */
    @Test
    void assignsFirstBusinessAsDefault() throws Exception {
        createBusiness("첫 가게");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultBusinessId").isNumber())
                .andExpect(jsonPath("$.data.businessCount").value(1));
    }

    /** 두 번째 사업장이 사용자의 선택을 덮어쓰면 안 된다. */
    @Test
    void keepsDefaultWhenSecondBusinessIsCreated() throws Exception {
        createBusiness("첫 가게");
        Long first = jdbc.queryForObject(
                "SELECT default_business_id FROM core.users WHERE id = ?", Long.class, userId);

        createBusiness("둘째 가게");

        assertThat(jdbc.queryForObject(
                "SELECT default_business_id FROM core.users WHERE id = ?", Long.class, userId))
                .isEqualTo(first);
    }

    private void createBusiness(String name) throws Exception {
        mockMvc.perform(post(BUSINESSES)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"taxationType\":\"GENERAL\"}"))
                .andExpect(status().isCreated());
    }
}
