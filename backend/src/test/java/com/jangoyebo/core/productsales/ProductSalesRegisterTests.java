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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품별 매출 등록 (API 14.4, P1-22).
 *
 * <p><b>여기서 지키는 선은 하나다 — 정상 인쇄물을 반려하지 않는 것.</b> 58/80mm 감열지는
 * 열이 줄어 나오고(총매출 열이 통째로 없는 레이아웃이 실측 33.3%), 합계가 안 맞는 것도
 * 22.2% 가 정상이다. 그래서 막는 것은 <b>구성비를 만들 수 없는 경우</b>와 <b>조용히 두 배가
 * 되는 경우</b>뿐이다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ProductSalesRegisterTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "ps-reg-" + unique + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '카페') RETURNING id
                """, Long.class, userId);
    }

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/businesses/{id}/product-sales", businessId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int rowCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM core.product_sales_period WHERE business_id = ?
                """, Integer.class, businessId);
    }

    // ------------------------------------------------------------------ 받아야 하는 것

    /** 8열이 다 있는 POS 화면 촬영. 합계가 맞으면 `MATCHED` 다. */
    @Test
    void savesAFullWidthPrintout() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "sourceType": "OCR_CORRECTED",
                  "checksum": { "billedAmount": 231600 },
                  "items": [
                    { "productName": "카페라떼", "quantity": 47, "grossAmount": 163000,
                      "discountAmount": 1050, "netAmount": 147227, "vatAmount": 14723,
                      "billedAmount": 161950 },
                    { "productName": "카페모카", "quantity": 20, "grossAmount": 70000,
                      "discountAmount": 350, "netAmount": 63318, "vatAmount": 6332,
                      "billedAmount": 69650 }
                  ]
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedCount").value(2))
                .andExpect(jsonPath("$.data.shareBasis").value("BILLED"))
                .andExpect(jsonPath("$.data.checksumStatus").value("MATCHED"))
                .andExpect(jsonPath("$.data.bootstrap").value(true))
                .andExpect(jsonPath("$.data.newProducts.length()").value(2));
    }

    /**
     * <b>58mm 인쇄물이 이 기능의 시험대다.</b> 상품명·수량·결제금액 4열만 있고 총매출 열이
     * 아예 없는데, 실측 90표본 중 30표본 전부가 이 모양이다. 반려하면 그 레이아웃을 쓰는
     * 사장님은 기능을 아예 못 쓴다.
     */
    @Test
    void savesA58mmPrintoutWithNoGrossColumn() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [
                    { "productName": "카페라떼", "quantity": 47, "billedAmount": 161950 },
                    { "productName": "카페모카", "quantity": 20, "billedAmount": 69650 }
                  ]
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedCount").value(2))
                .andExpect(jsonPath("$.data.checksumStatus").value("UNAVAILABLE"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM core.product_sales_period
                 WHERE business_id = ? AND gross_amount IS NULL AND billed_amount IS NOT NULL
                """, Integer.class, businessId)).isEqualTo(2);
    }

    /** 취소·반품은 음수로 들어온다. 양수 제약을 걸면 취소 데이터가 통째로 거부된다. */
    @Test
    void acceptsNegativeAmountsFromCancellations() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [
                    { "productName": "환불상품", "quantity": -12, "grossAmount": -42000,
                      "billedAmount": -42000 }
                  ]
                }
                """).andExpect(status().isOk());

        assertThat(rowCount()).isEqualTo(1);
    }

    /**
     * <b>합계가 안 맞아도 저장한다.</b> 막으면 인쇄물이 잘린 사장님이 아무것도 넣을 수 없고,
     * 그게 다섯 장 중 한 장꼴(22.2%)이다.
     */
    @Test
    void savesEvenWhenTheChecksumDisagrees() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "checksum": { "billedAmount": 670450 },
                  "items": [ { "productName": "카페라떼", "billedAmount": 161950 } ]
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedCount").value(1))
                .andExpect(jsonPath("$.data.checksumStatus").value("MISMATCHED"))
                .andExpect(jsonPath("$.data.checksumDetail.difference").value(508500))
                .andExpect(jsonPath("$.data.checksumDetail.message")
                        .value(org.hamcrest.Matchers.containsString("아래쪽 행이 빠진")));
    }

    /** 네 값이 다 있을 때만 검산한다 — 열이 줄어 나온 인쇄물을 거부하면 안 된다. */
    @Test
    void skipsArithmeticCheckWhenAColumnIsMissing() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [
                    { "productName": "카페라떼", "grossAmount": 163000, "billedAmount": 161950 }
                  ]
                }
                """).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 막아야 하는 것

    /** 분모가 될 값이 하나도 없으면 구성비를 만들 수 없다. DB CHECK 이전에 여기서 끊는다. */
    @Test
    void rejectsARowWithNeitherGrossNorBilled() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [ { "productName": "이름만", "quantity": 3 } ]
                }
                """).andExpect(status().isBadRequest());

        assertThat(rowCount()).isZero();
    }

    @Test
    void rejectsBrokenArithmeticWhenAllFourAreThere() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [
                    { "productName": "카페라떼", "grossAmount": 163000, "discountAmount": 1050,
                      "netAmount": 147227, "vatAmount": 9999, "billedAmount": 161950 }
                  ]
                }
                """).andExpect(status().isBadRequest());
    }

    /**
     * 같은 상품이 두 번 오면 <b>거절한다.</b> 합치면 그 상품의 구성비가 조용히 두 배가 된다 —
     * 워커가 장 경계 중복을 이미 제거하므로 여기까지 온 것은 검토 화면에서 만든 중복이다.
     */
    @Test
    void rejectsTheSameProductTwice() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [
                    { "productName": "카페라떼", "billedAmount": 100 },
                    { "productName": "카페 라떼", "billedAmount": 200 }
                  ]
                }
                """).andExpect(status().isBadRequest());

        assertThat(rowCount()).as("정규화하면 같은 키다").isZero();
    }

    @Test
    void rejectsAnInvertedPeriod() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-31", "periodEnd": "2026-08-01",
                  "items": [ { "productName": "카페라떼", "billedAmount": 100 } ]
                }
                """).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUnknownSourceType() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "sourceType": "CSV",
                  "items": [ { "productName": "카페라떼", "billedAmount": 100 } ]
                }
                """).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 재등록

    /**
     * <b>재등록은 그 기간을 통째로 교체한다.</b> 순수 upsert 면 지난번 오인식이 이번 제출에서
     * 빠져도 남아서 <b>구성비 분모에 계속 들어간다.</b> 인쇄물 한 장은 그 기간 전체를 말한다.
     */
    @Test
    void reRegisteringReplacesThePeriod() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [
                    { "productName": "카페라떼", "billedAmount": 100 },
                    { "productName": "카페라며", "billedAmount": 50 }
                  ]
                }
                """).andExpect(status().isOk());

        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 150 } ]
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedCount").value(1))
                .andExpect(jsonPath("$.data.removedCount").value(1));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT billed_amount FROM core.product_sales_period WHERE business_id = ?
                """, java.math.BigDecimal.class, businessId)).isEqualByComparingTo("150");
    }

    /** 기간이 겹치되 같지 않으면 서로 건드리지 않는다. 둘 다 남고 조회가 겹침을 경고한다. */
    @Test
    void leavesOverlappingButDifferentPeriodsAlone() throws Exception {
        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-14",
                  "items": [ { "productName": "카페라떼", "billedAmount": 100 } ]
                }
                """).andExpect(status().isOk());

        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 300 } ]
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removedCount").value(0));

        assertThat(rowCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 신규 상품

    /**
     * 첫 등록은 전 품목이 신규라 {@code bootstrap} 으로 일괄 수락한다. 2회차부터 진짜 신규만
     * 나온다 — 80개를 매달 확인시키면 아무도 안 본다.
     */
    @Test
    void reportsOnlyGenuinelyNewProductsFromTheSecondTime() throws Exception {
        register("""
                {
                  "periodStart": "2026-07-01", "periodEnd": "2026-07-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 100 } ]
                }
                """).andExpect(status().isOk());

        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [
                    { "productName": "카페라떼", "billedAmount": 120 },
                    { "productName": "콜드브루", "billedAmount": 80 }
                  ]
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bootstrap").value(false))
                .andExpect(jsonPath("$.data.newProducts.length()").value(1))
                .andExpect(jsonPath("$.data.newProducts[0]").value("콜드브루"));
    }

    /**
     * <b>월 1회 입력이라 이 경고가 중요하다.</b> 오인식 하나가 다음 달까지 살아남으며 두
     * 상품의 구성비를 각각 깎는다 — 일 단위였다면 다음 날 눈에 띄었을 오류다.
     */
    @Test
    void warnsWhenANewProductLooksLikeAMisreadOfAnOldOne() throws Exception {
        register("""
                {
                  "periodStart": "2026-07-01", "periodEnd": "2026-07-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 100 } ]
                }
                """).andExpect(status().isOk());

        register("""
                {
                  "periodStart": "2026-08-01", "periodEnd": "2026-08-31",
                  "items": [ { "productName": "카페라며", "billedAmount": 100 } ]
                }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings[0].productName").value("카페라며"))
                .andExpect(jsonPath("$.data.warnings[0].similarTo").value("카페라떼"));
    }
}
