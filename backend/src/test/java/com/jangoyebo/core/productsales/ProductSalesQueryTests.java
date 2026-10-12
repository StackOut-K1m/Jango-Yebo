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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품별 매출 구성비 조회 (API 14.5, P1-22).
 *
 * <p><b>숫자가 POS 인쇄물과 같아야 한다.</b> 분모를 총매출로 잡으면 사장님이 보는 점유율과
 * 어긋나고, 그러면 이 화면은 신뢰를 잃는다. 나머지 검증은 <b>모르는 것을 모른다고 말하는가</b>에
 * 몰려 있다 — 비교할 직전 기간이 없을 때, 열이 일부 기간에만 있을 때, 등록 기간이 겹칠 때.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class ProductSalesQueryTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "ps-q-" + unique + "@test.local";
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

    private ResultActions shares(String from, String to, String... extra) throws Exception {
        var request = get("/api/v1/businesses/{id}/product-sales", businessId)
                .header("Authorization", "Bearer " + token)
                .param("from", from).param("to", to);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            request = request.param(extra[i], extra[i + 1]);
        }
        return mockMvc.perform(request);
    }

    /** 명세 예시 그대로. 12월 2주치를 올리고 구성비를 본다. */
    private void registerDecember() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-14",
                  "checksum": { "billedAmount": 670450 },
                  "items": [
                    { "productName": "카페라떼", "quantity": 47, "grossAmount": 163000,
                      "discountAmount": 1050, "billedAmount": 161950 },
                    { "productName": "카페모카", "quantity": 20, "grossAmount": 70000,
                      "discountAmount": 350, "billedAmount": 69650 },
                    { "productName": "아메리카노", "quantity": 72, "grossAmount": 457000,
                      "discountAmount": 18150, "billedAmount": 438850 }
                  ]
                }
                """);
    }

    /**
     * <b>POS 화면 실측이 기준이다.</b> 161,950/670,450 = 24.2% 가 인쇄된 점유율과 같아야 한다.
     * 총매출 기준(23.6%)으로 잡으면 어긋난다.
     */
    @Test
    void sharesMatchThePrintout() throws Exception {
        registerDecember();

        shares("2026-12-01", "2026-12-14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareBasis").value("BILLED"))
                .andExpect(jsonPath("$.data.incomplete").value(false))
                .andExpect(jsonPath("$.data.totals.billedAmount").value(670450))
                // 기본 정렬은 구성비 기준 금액 내림차순이라 아메리카노가 먼저다
                .andExpect(jsonPath("$.data.items[0].productName").value("아메리카노"))
                .andExpect(jsonPath("$.data.items[1].productName").value("카페라떼"))
                .andExpect(jsonPath("$.data.items[1].share").value(0.242))
                .andExpect(jsonPath("$.data.items[2].share").value(0.104));
    }

    /** 할인율은 총매출이 있어야 나온다. 1,050/163,000 = 0.6%. */
    @Test
    void reportsDiscountRate() throws Exception {
        registerDecember();

        shares("2026-12-01", "2026-12-14")
                .andExpect(jsonPath("$.data.items[1].discountRate").value(0.006));
    }

    /**
     * <b>비교할 직전 기간이 없으면 증감을 말하지 않는다.</b> 첫 달을 "전부 신규 성장"으로
     * 말하면 리포트가 통째로 거짓이 된다.
     */
    @Test
    void saysNothingAboutChangeForTheFirstPeriod() throws Exception {
        registerDecember();

        shares("2026-12-01", "2026-12-14")
                .andExpect(jsonPath("$.data.items[0].changeRate").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].contributionToChange").doesNotExist());
    }

    /** 직전 <b>동일 길이</b> 기간과 비교한다 — 12/1~12/14 를 물으면 11/17~11/30 이 대상이다. */
    @Test
    void comparesAgainstThePrecedingWindowOfTheSameLength() throws Exception {
        register("""
                {
                  "periodStart": "2026-11-17", "periodEnd": "2026-11-30",
                  "items": [ { "productName": "카페라떼", "billedAmount": 100000 } ]
                }
                """);
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-14",
                  "items": [ { "productName": "카페라떼", "billedAmount": 86000 } ]
                }
                """);

        shares("2026-12-01", "2026-12-14")
                .andExpect(jsonPath("$.data.items[0].changeRate").value(-0.14))
                .andExpect(jsonPath("$.data.items[0].contributionToChange").value(-14000));
    }

    /** 잘린 인쇄물이 섞이면 {@code incomplete} 다. 구성비는 계산하되 화면이 표시한다. */
    @Test
    void flagsIncompleteWhenAChecksumMismatched() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-14",
                  "checksum": { "billedAmount": 670450 },
                  "items": [ { "productName": "카페라떼", "billedAmount": 161950 } ]
                }
                """);

        shares("2026-12-01", "2026-12-14")
                .andExpect(jsonPath("$.data.incomplete").value(true))
                // 분모는 「보이는 행의 합」이라 총합이 100% 다 — 인쇄된 합계 행이 아니다
                .andExpect(jsonPath("$.data.items[0].share").value(1));
    }

    /** 등록 기간이 서로 겹치면 겹친 날이 두 번 더해진다. 숫자는 주되 사정을 알린다. */
    @Test
    void warnsWhenRegisteredPeriodsOverlap() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-14",
                  "items": [ { "productName": "카페라떼", "billedAmount": 100 } ]
                }
                """);
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 300 } ]
                }
                """);

        shares("2026-12-01", "2026-12-31")
                .andExpect(jsonPath("$.data.items[0].billedAmount").value(400))
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'PERIODS_OVERLAP')]")
                        .isNotEmpty());
    }

    /** 등록 기간이 조회 범위 밖으로 걸치면 물어본 것보다 넓은 기간의 숫자다. */
    @Test
    void warnsWhenARegisteredPeriodSticksOutOfTheQueryRange() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 300 } ]
                }
                """);

        shares("2026-12-01", "2026-12-14")
                .andExpect(jsonPath("$.data.items[0].billedAmount").value(300))
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'PERIOD_PARTIALLY_OUTSIDE')]")
                        .isNotEmpty());
    }

    /** 열이 일부 기간에만 있으면 합계가 그 기간만 더한 값이다. 조용히 내보내면 안 된다. */
    @Test
    void warnsWhenAColumnExistsInOnlySomePeriods() throws Exception {
        register("""
                {
                  "periodStart": "2026-11-01", "periodEnd": "2026-11-30",
                  "items": [ { "productName": "카페라떼", "grossAmount": 100, "billedAmount": 90 } ]
                }
                """);
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 90 } ]
                }
                """);

        shares("2026-11-01", "2026-12-31")
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'PARTIAL_COLUMN')]").isNotEmpty());
    }

    /** 결제금액이 없는 행이 섞이면 기준을 총매출로 내리고 그 사실을 알린다. */
    @Test
    void warnsWhenTheBasisHadToFallBackToGross() throws Exception {
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [
                    { "productName": "카페라떼", "grossAmount": 100, "billedAmount": 90 },
                    { "productName": "카페모카", "grossAmount": 50 }
                  ]
                }
                """);

        shares("2026-12-01", "2026-12-31")
                .andExpect(jsonPath("$.data.shareBasis").value("GROSS"))
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'MIXED_SHARE_BASIS')]").isNotEmpty());
    }

    /** 등록이 없으면 <b>빈 상태</b>다 — 404 가 아니다(화면구성도 6.4). */
    @Test
    void returnsAnEmptyStateInsteadOf404() throws Exception {
        shares("2026-12-01", "2026-12-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.incomplete").value(false));
    }

    @Test
    void sortsByQuantityWhenAsked() throws Exception {
        registerDecember();

        shares("2026-12-01", "2026-12-14", "sort", "QUANTITY")
                .andExpect(jsonPath("$.data.items[0].productName").value("아메리카노"))
                .andExpect(jsonPath("$.data.items[1].productName").value("카페라떼"));
    }

    @Test
    void rejectsAnUnknownSort() throws Exception {
        shares("2026-12-01", "2026-12-31", "sort", "PROFIT")
                .andExpect(status().isBadRequest());
    }

    /** 상품 하나의 기간별 추이. {@code share} 는 <b>그 기간 안에서의</b> 점유율이다. */
    @Test
    void trendShowsShareWithinEachPeriod() throws Exception {
        register("""
                {
                  "periodStart": "2026-11-01", "periodEnd": "2026-11-30",
                  "items": [
                    { "productName": "카페라떼", "billedAmount": 100 },
                    { "productName": "카페모카", "billedAmount": 300 }
                  ]
                }
                """);
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [
                    { "productName": "카페라떼", "billedAmount": 300 },
                    { "productName": "카페모카", "billedAmount": 100 }
                  ]
                }
                """);

        mockMvc.perform(get("/api/v1/businesses/{id}/product-sales/{key}", businessId, "카페 라떼")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-11-01").param("to", "2026-12-31"))
                .andExpect(status().isOk())
                // 원문 상품명을 넣어도 서버가 같은 규칙으로 정규화한다
                .andExpect(jsonPath("$.data.productKey").value("카페라떼"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].share").value(0.25))
                .andExpect(jsonPath("$.data.items[1].share").value(0.75));
    }

    /** 안 팔린 기간은 0 으로 채우지 않고 건너뛴다 — 안 판 것과 못 읽은 것이 섞이면 안 된다. */
    @Test
    void trendSkipsPeriodsWhereTheProductIsAbsent() throws Exception {
        register("""
                {
                  "periodStart": "2026-11-01", "periodEnd": "2026-11-30",
                  "items": [ { "productName": "카페모카", "billedAmount": 300 } ]
                }
                """);
        register("""
                {
                  "periodStart": "2026-12-01", "periodEnd": "2026-12-31",
                  "items": [ { "productName": "카페라떼", "billedAmount": 300 } ]
                }
                """);

        mockMvc.perform(get("/api/v1/businesses/{id}/product-sales/{key}", businessId, "카페라떼")
                        .header("Authorization", "Bearer " + token)
                        .param("from", "2026-11-01").param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].periodStart").value("2026-12-01"));
    }

    /** 남의 사업장은 보이지 않는다. */
    @Test
    void hidesAnotherOwnersBusiness() throws Exception {
        String other = tokenProvider.createAccessToken(
                jdbc.queryForObject("""
                        INSERT INTO core.users (email, password_hash, name)
                        VALUES (?, 'x', '남') RETURNING id
                        """, Long.class, "ps-other-" + System.nanoTime() + "@test.local"),
                "other@test.local");

        mockMvc.perform(get("/api/v1/businesses/{id}/product-sales", businessId)
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
    }
}
