package com.jangoyebo.core.productsales;

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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품명 일괄 변경 (API 14.6, P1-22).
 *
 * <p><b>마스터 테이블이 없어 이것이 표기 정리의 유일한 수단이다.</b> 그래서 두 가지를 지킨다 —
 * 과거 전체에 적용될 것(한 기간만 고치면 추이에서 두 줄로 갈라진 채 남는다)과, 합칠 때
 * <b>없는 열을 0 으로 만들지 않을 것</b>(58mm 인쇄물의 빠진 총매출 열이 "0원"이 되면 안 된다).
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ProductSalesRenameTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "ps-rn-" + unique + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '카페') RETURNING id
                """, Long.class, userId);
    }

    private void register(String body) throws Exception {
        mockMvc.perform(post("/api/v1/businesses/{id}/product-sales", businessId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private ResultActions rename(String from, String to) throws Exception {
        return mockMvc.perform(patch("/api/v1/businesses/{id}/product-sales/rename", businessId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"from\": \"%s\", \"to\": \"%s\" }".formatted(from, to)));
    }

    private BigDecimal billedOf(String productKey) {
        return jdbc.queryForObject("""
                SELECT billed_amount FROM core.product_sales_period
                 WHERE business_id = ? AND product_key = ?
                """, BigDecimal.class, businessId, productKey);
    }

    private int rowCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM core.product_sales_period WHERE business_id = ?
                """, Integer.class, businessId);
    }

    // ------------------------------------------------------------------ 키가 다른 경우

    /**
     * <b>이것이 14.6 이 실제로 하는 일이다.</b> OCR 이 `카페라떼` 를 `카페라며` 로 읽어 두
     * 상품이 됐고, 같은 기간에 둘 다 있으니 합쳐야 한다.
     */
    @Test
    void mergesTwoKeysWithinTheSamePeriod() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [
                    { "productName": "카페라떼", "quantity": 40, "billedAmount": 100000 },
                    { "productName": "카페라며", "quantity": 7, "billedAmount": 20000 }
                  ]
                }
                """);

        rename("카페라며", "카페라떼")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyUnchanged").value(false))
                .andExpect(jsonPath("$.data.mergedCount").value(1))
                .andExpect(jsonPath("$.data.renamedCount").value(0));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(billedOf("카페라떼")).isEqualByComparingTo("120000");
        assertThat(jdbc.queryForObject("""
                SELECT quantity FROM core.product_sales_period
                 WHERE business_id = ? AND product_key = '카페라떼'
                """, BigDecimal.class, businessId)).isEqualByComparingTo("47");
    }

    /** 그 기간에 대상 상품이 없었으면 합칠 것이 없고 옮기기만 한다. */
    @Test
    void movesTheRowWhenTheTargetIsAbsentInThatPeriod() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [ { "productName": "카페라며", "billedAmount": 20000 } ]
                }
                """);

        rename("카페라며", "카페라떼")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.renamedCount").value(1))
                .andExpect(jsonPath("$.data.mergedCount").value(0));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(billedOf("카페라떼")).isEqualByComparingTo("20000");
        assertThat(jdbc.queryForObject("""
                SELECT product_name FROM core.product_sales_period WHERE business_id = ?
                """, String.class, businessId)).isEqualTo("카페라떼");
    }

    /**
     * <b>과거 전체에 적용한다.</b> 한 기간만 고치면 추이 조회에서 같은 상품이 두 줄로 갈라진
     * 채 남는다 — 표기 정리의 목적이 그것을 없애는 것이다.
     */
    @Test
    void appliesToEveryPeriodNotJustTheLatest() throws Exception {
        register("""
                {
                  "periodStart": "2026-11-01", "periodEnd": "2026-11-30",
                  "items": [ { "productName": "카페라며", "billedAmount": 10000 } ]
                }
                """);
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [
                    { "productName": "카페라떼", "billedAmount": 50000 },
                    { "productName": "카페라며", "billedAmount": 20000 }
                  ]
                }
                """);

        rename("카페라며", "카페라떼")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.renamedCount").value(1))
                .andExpect(jsonPath("$.data.mergedCount").value(1))
                .andExpect(jsonPath("$.data.periods.length()").value(2));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.product_sales_period
                 WHERE business_id = ? AND product_key = '카페라며'
                """, Integer.class, businessId)).isZero();
        assertThat(rowCount()).isEqualTo(2);
    }

    /**
     * <b>합칠 때 없는 열을 0 으로 만들지 않는다.</b> 58mm 인쇄물에는 총매출 열이 없는데
     * 0 으로 채우면 "총매출 0원인 상품"이 되고, 그 값이 구성비 계산에 섞인다.
     */
    @Test
    void keepsMissingColumnsNullWhenMerging() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [
                    { "productName": "카페라떼", "billedAmount": 100000 },
                    { "productName": "카페라며", "billedAmount": 20000 }
                  ]
                }
                """);

        rename("카페라며", "카페라떼").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                SELECT gross_amount IS NULL FROM core.product_sales_period
                 WHERE business_id = ? AND product_key = '카페라떼'
                """, Boolean.class, businessId)).isTrue();
    }

    /** 사람이 손으로 고쳤다는 사실을 출처에 남긴다 — 실사용 정확도를 볼 때 구분돼야 한다. */
    @Test
    void marksTheRowAsHumanCorrected() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "sourceType": "OCR",
                  "items": [ { "productName": "카페라며", "billedAmount": 20000 } ]
                }
                """);

        rename("카페라며", "카페라떼").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
                SELECT source_type FROM core.product_sales_period WHERE business_id = ?
                """, String.class, businessId)).isEqualTo("OCR_CORRECTED");
    }

    /** 괄호 변형도 합칠 수 있다 — 정규화가 괄호를 남기므로 키가 다르기 때문이다. */
    @Test
    void mergesParenthesisedVariants() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [
                    { "productName": "아메리카노", "billedAmount": 100000 },
                    { "productName": "아메리카노(HOT)", "billedAmount": 30000 }
                  ]
                }
                """);

        rename("아메리카노(HOT)", "아메리카노")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mergedCount").value(1));

        assertThat(billedOf("아메리카노")).isEqualByComparingTo("130000");
    }

    // ------------------------------------------------------------------ 키가 같은 경우

    /**
     * <b>명세 예시가 이 경우다.</b> {@code 카페 라떼} 와 {@code 카페라떼} 는 공백만 다른데
     * 정규화가 공백을 이미 지워서 <b>처음부터 한 행</b>이었다. 합칠 것이 없고 보이는 글자만
     * 바뀐다 — 거절하지 않는 것은 그것도 쓸모 있는 동작이기 때문이다.
     */
    @Test
    void changesOnlyTheDisplayNameWhenBothNormalizeToTheSameKey() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [ { "productName": "카페 라떼", "billedAmount": 100000 } ]
                }
                """);

        rename("카페 라떼", "카페라떼")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyUnchanged").value(true))
                .andExpect(jsonPath("$.data.mergedCount").value(0))
                .andExpect(jsonPath("$.data.renamedCount").value(1));

        assertThat(rowCount()).as("합쳐질 것이 없었다").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT product_name FROM core.product_sales_period WHERE business_id = ?
                """, String.class, businessId)).isEqualTo("카페라떼");
        assertThat(billedOf("카페라떼")).isEqualByComparingTo("100000");
    }

    // ------------------------------------------------------------------ 막아야 하는 것

    /**
     * 바꿀 상품이 없으면 400 이다. 화면이 목록에서 고르는 값이라 여기 걸리면 목록이 낡은
     * 것이고, 0건으로 조용히 답하면 사용자는 바뀐 줄 안다.
     */
    @Test
    void rejectsWhenThereIsNothingToRename() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 100000 } ]
                }
                """);

        rename("없는상품", "카페라떼").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRenamingToItself() throws Exception {
        rename("카페라떼", "카페라떼").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankNames() throws Exception {
        rename("", "카페라떼").andExpect(status().isBadRequest());
        rename("카페라떼", "   ").andExpect(status().isBadRequest());
    }

    /** 남의 사업장 상품은 건드릴 수 없다. */
    @Test
    void rejectsAnotherOwnersBusiness() throws Exception {
        String other = tokenProvider.createAccessToken(
                jdbc.queryForObject("""
                        INSERT INTO core.users (email, password_hash, name)
                        VALUES (?, 'x', '남') RETURNING id
                        """, Long.class, "rn-other-" + System.nanoTime() + "@test.local"),
                "other@test.local");

        mockMvc.perform(patch("/api/v1/businesses/{id}/product-sales/rename", businessId)
                        .header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"from\": \"가\", \"to\": \"나\" }"))
                .andExpect(status().isForbidden());
    }
}
