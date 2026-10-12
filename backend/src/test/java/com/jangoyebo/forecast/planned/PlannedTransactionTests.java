package com.jangoyebo.forecast.planned;

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

import java.time.LocalDate;
import java.time.ZoneId;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예정 거래 (API 9.4, P0-06).
 *
 * <p>잔액 예측의 직접 입력이라 <b>금액 형태와 삭제 규칙</b>을 집중적으로 본다 —
 * 곡선에 더할 값이 없거나 취소가 되살아나면 예측이 조용히 틀린다.
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class PlannedTransactionTests {

    /**
     * 예정일 픽스처.
     *
     * <p>컨트롤러가 지난 날짜를 거부하므로 고정 날짜를 쓰면 그 날이 지나는 순간 깨진다.
     * 더 나쁜 것은 금액·분류를 보려던 400 이 <b>날짜 오류로 바뀌어도 그대로 통과</b>한다는
     * 점이다 — 오늘을 기준으로 잡고, 컨트롤러와 같은 KST 로 판정한다.
     */
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final LocalDate SOON = TODAY.plusDays(5);
    private static final LocalDate LATER = TODAY.plusDays(9);
    private static final LocalDate FAR = TODAY.plusMonths(2);

    /** {@link #SOON} 만 걸리고 {@link #FAR} 는 벗어나는 조회 범위. */
    private static final String SOON_RANGE = "from=" + SOON.minusDays(1) + "&to=" + SOON.plusDays(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "planned-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '예정') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '가게') RETURNING id
                """, Long.class, userId);
    }

    /** 사용자가 넣은 것은 이미 확정된 의사라 EXPECTED 가 아니라 CONFIRMED 로 시작한다. */
    @Test
    void createsWithFixedAmount() throws Exception {
        create("""
                {"direction":"WITHDRAWAL","title":"에스프레소 머신 교체",
                 "expectedDate":"%s","amount":1500000,"category":"EQUIPMENT"}
                """.formatted(SOON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source").value("USER"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.category").value("EQUIPMENT"))
                // 확정 금액은 세 값이 같다 — 곡선의 폭이 0 이다.
                .andExpect(jsonPath("$.data.amountExpected").value(1500000))
                .andExpect(jsonPath("$.data.amountMin").value(1500000))
                .andExpect(jsonPath("$.data.amountMax").value(1500000))
                // 보정 로직(P1-04) 착수 전이라 두 날짜가 같다.
                .andExpect(jsonPath("$.data.originalDate").value(SOON.toString()))
                .andExpect(jsonPath("$.data.holidayAdjusted").value(false));
    }

    /** 범위로 오면 대표값은 중앙이다. 곡선의 기대선이 여기서 나온다. */
    @Test
    void createsWithAmountRange() throws Exception {
        create("""
                {"direction":"DEPOSIT","title":"정산 예상",
                 "expectedDate":"%s","amountMin":820000,"amountMax":960000}
                """.formatted(SOON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amountMin").value(820000))
                .andExpect(jsonPath("$.data.amountMax").value(960000))
                .andExpect(jsonPath("$.data.amountExpected").value(890000));
    }

    /**
     * 확정 금액과 범위를 동시에 보내면 400 이다.
     *
     * <p>어느 쪽이 이기는지가 호출자마다 달라지면 <b>같은 요청이 다른 곡선을 만든다.</b>
     * DB {@code ck_pt_amount} 도 잡지만 오류가 제약 이름이라 프론트가 읽을 수 없다.
     */
    @Test
    void rejectsBothAmountForms() throws Exception {
        create("""
                {"direction":"DEPOSIT","title":"둘 다","expectedDate":"%s",
                 "amount":900000,"amountMin":820000,"amountMax":960000}
                """.formatted(SOON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsNoAmount() throws Exception {
        create("""
                {"direction":"DEPOSIT","title":"금액 없음","expectedDate":"%s"}
                """.formatted(SOON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvertedRange() throws Exception {
        create("""
                {"direction":"DEPOSIT","title":"뒤집힘","expectedDate":"%s",
                 "amountMin":960000,"amountMax":820000}
                """.formatted(SOON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownCategory() throws Exception {
        create("""
                {"direction":"DEPOSIT","title":"오타","expectedDate":"%s",
                 "amount":1000,"category":"NOPE"}
                """.formatted(SOON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 당일은 받는다.
     *
     * <p>곡선에 당일 점은 없지만 첫 점이 합산한다. 여기서 막으면 「오늘 나갈 돈」을
     * 넣을 자리가 사라진다.
     */
    @Test
    void acceptsTodayExpectedDate() throws Exception {
        create("""
                {"direction":"WITHDRAWAL","title":"오늘 낼 임차료","expectedDate":"%s","amount":8000000}
                """.formatted(TODAY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.expectedDate").value(TODAY.toString()));
    }

    /**
     * 지난 날짜는 받지 않는다.
     *
     * <p>곡선이 닿지 않는 구간이라 저장해도 어디에도 나타나지 않는다 — 201 을 돌려주고
     * 조용히 사라지면 사용자는 <b>등록했는데 잔액이 그대로인</b> 화면을 본다.
     */
    @Test
    void rejectsPastExpectedDate() throws Exception {
        create("""
                {"direction":"WITHDRAWAL","title":"어제 낸 임차료","expectedDate":"%s","amount":8000000}
                """.formatted(TODAY.minusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listsWithinRange() throws Exception {
        create("""
                {"direction":"WITHDRAWAL","title":"범위 안","expectedDate":"%s","amount":1000}
                """.formatted(SOON)).andExpect(status().isCreated());
        create("""
                {"direction":"WITHDRAWAL","title":"범위 밖","expectedDate":"%s","amount":2000}
                """.formatted(FAR)).andExpect(status().isCreated());

        list(SOON_RANGE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("범위 안"));

        list(SOON_RANGE + "&direction=DEPOSIT")
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /** 범위를 안 막으면 롤링 배치가 만든 90일치가 통째로 나온다. */
    @Test
    void rejectsTooWideRange() throws Exception {
        list("from=2026-01-01&to=2026-12-31")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsInvertedDateRange() throws Exception {
        list("from=2026-09-30&to=2026-09-01")
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesFields() throws Exception {
        long id = createAndGetId();

        mockMvc.perform(patch("/api/v1/planned-transactions/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"이름 변경","expectedDate":"%s","amount":2000000}
                                """.formatted(LATER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("이름 변경"))
                .andExpect(jsonPath("$.data.expectedDate").value(LATER.toString()))
                // 사용자가 직접 옮긴 날짜는 그 자체가 원본이다 — 공휴일 보정분으로 오해되면 안 된다.
                .andExpect(jsonPath("$.data.originalDate").value(LATER.toString()))
                .andExpect(jsonPath("$.data.holidayAdjusted").value(false))
                .andExpect(jsonPath("$.data.amountExpected").value(2000000));
    }

    @Test
    void cancelsViaStatus() throws Exception {
        long id = createAndGetId();

        mockMvc.perform(patch("/api/v1/planned-transactions/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    /** 명세가 허용한 전이는 취소뿐이다 — 나머지는 매칭 배치와 패턴이 정한다. */
    @Test
    void rejectsOtherStatusTransitions() throws Exception {
        long id = createAndGetId();

        mockMvc.perform(patch("/api/v1/planned-transactions/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"MATCHED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesUserCreated() throws Exception {
        long id = createAndGetId();

        mockMvc.perform(delete("/api/v1/planned-transactions/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/businesses/" + businessId
                        + "/planned-transactions?" + SOON_RANGE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /**
     * 패턴이 만든 것은 지울 수 없다.
     *
     * <p>지우면 다음 롤링 배치가 같은 것을 다시 만들어 <b>취소한 것이 되살아난다.</b>
     */
    @Test
    void rejectsDeletingPatternGenerated() throws Exception {
        Long id = jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                    (business_id, source_type, name, expected_date, original_expected_date,
                     flow_type, amount_expected, amount_min, amount_max)
                VALUES (?, 'PATTERN', '카드 정산', DATE '2026-09-12', DATE '2026-09-12',
                        'DEPOSIT', 300000, 280000, 320000) RETURNING id
                """, Long.class, businessId);

        mockMvc.perform(delete("/api/v1/planned-transactions/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PLANNED_TX_NOT_DELETABLE"));
    }

    /** 직접 경로라 @CurrentBusiness 를 안 거친다 — guard.assertOwned 가 유일한 방어선이다. */
    @Test
    void rejectsOtherBusinessResource() throws Exception {
        Long otherOwner = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "other-" + System.nanoTime() + "@test.local");
        Long otherBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 가게') RETURNING id
                """, Long.class, otherOwner);
        Long foreign = jdbc.queryForObject("""
                INSERT INTO forecast.planned_transaction
                    (business_id, source_type, name, expected_date, original_expected_date,
                     flow_type, amount_expected, amount_min, amount_max)
                VALUES (?, 'USER', '남의 예정', DATE '2026-09-12', DATE '2026-09-12',
                        'DEPOSIT', 1000, 1000, 1000) RETURNING id
                """, Long.class, otherBusiness);

        mockMvc.perform(delete("/api/v1/planned-transactions/" + foreign)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // --- helpers -------------------------------------------------------------

    private ResultActions create(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/businesses/" + businessId + "/planned-transactions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + businessId
                        + "/planned-transactions?" + query)
                .header("Authorization", "Bearer " + token));
    }

    private long createAndGetId() throws Exception {
        create("""
                {"direction":"WITHDRAWAL","title":"원본","expectedDate":"%s","amount":1500000}
                """.formatted(SOON)).andExpect(status().isCreated());
        return jdbc.queryForObject(
                "SELECT id FROM forecast.planned_transaction WHERE business_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, businessId);
    }
}
