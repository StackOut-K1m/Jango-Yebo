package com.jangoyebo.core.loan;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대출 상품 추천 (API 16.3, P1-14).
 *
 * <p>금융망을 부르는 부분({@code LOAN_04} 현재 등급)은 여기서 검증하지 않는다 —
 * 순위 규칙은 {@code LoanProductRankingTests} 가 순수 함수로 본다. 여기서 보는 것은
 * <b>꺼진 상품이 새어 나가지 않는가</b>와 입력 검증이다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class LoanProductApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoanProductsService productsService;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "loan-product-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, status)
                VALUES (?, 'hash', '김사장', 'ACTIVE') RETURNING id
                """, Long.class, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name, taxation_type, status)
                VALUES (?, '온기식당', 'GENERAL', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        token = tokenProvider.createAccessToken(userId, email);
    }

    @Test
    void 꺼진_상품은_추천_후보에_안_들어간다() {
        // 금융망에 상품 삭제 API 가 없고 팀원 누구나 상품을 만들 수 있다.
        // 동기화한 목록을 그대로 쓰면 남의 테스트 상품이 「이 대출을 받으세요」로 나간다.
        Long 켜짐 = product("켠 상품", true);
        Long 꺼짐 = product("안 켠 상품", false);

        var 후보 = productsService.recommendable();

        org.assertj.core.api.Assertions.assertThat(후보)
                .extracting(p -> p.productId())
                .contains(켜짐)
                .doesNotContain(꺼짐);
    }

    @Test
    void 금액이_없으면_거절한다() throws Exception {
        // 필수 파라미터 누락을 전역 핸들러가 안 잡아 500 이 되던 것을 컨트롤러에서 받는다.
        mockMvc.perform(get(url()).header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 금액이_0이하면_거절한다() throws Exception {
        mockMvc.perform(get(url() + "?amount=0").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 남의_사업장은_못_본다() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/999999/loan-products?amount=1000000")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());
    }

    private String url() {
        return "/api/v1/businesses/" + businessId + "/loan-products";
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private Long product(String name, boolean recommendable) {
        return jdbc.queryForObject("""
                INSERT INTO core.loan_product (external_id, bank_code, bank_name, name,
                                               loan_type_name, min_grade,
                                               min_loan_balance, max_loan_balance,
                                               interest_rate, loan_period_days,
                                               repayment_method, recommendable)
                VALUES (?, '999', '싸피은행', ?, '신용대출', 'E',
                        100000, 10000000, 8.400, 365, '원리금균등상환', ?)
                RETURNING id
                """, Long.class, "P" + System.nanoTime(), name, recommendable);
    }
}
