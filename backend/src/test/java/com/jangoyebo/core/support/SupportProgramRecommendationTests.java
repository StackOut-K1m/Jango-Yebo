package com.jangoyebo.core.support;

import com.jangoyebo.TestcontainersConfiguration;
import com.jangoyebo.common.security.JwtTokenProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 맞춤·공백 연계 추천 (17.3, P1-15).
 *
 * <p><b>여기서 지키려는 것은 「모르는 것을 아는 척하지 않는다」다.</b> 실측상 공고의 97% 가
 * 지급 시기를 말하지 않는다(32건 중 1건). 그 칸을 {@code false} 로 두면 쓸 수 있는 지원금을
 * 「못 받는다」로 읽고, {@code true} 로 두면 오지 않을 돈을 기다리며 다른 자금 대책을 접는다.
 * <b>둘 다 사장님이 손해를 보는 쪽</b>이라 키를 뺀다.
 *
 * <p>예측 실행이 없는 환경이라 {@code cashGap} 은 비어 있다 — 그 상태에서도 추천이
 * 나와야 한다는 것이 이 묶음의 또 다른 기준이다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SupportProgramRecommendationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;

    private String token;
    private Long businessId;

    @BeforeEach
    void setUp() {
        String email = "rec-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business
                    (owner_user_id, name, region, industry_code, annual_revenue, opened_on)
                VALUES (?, '카페', '광주 서구', 'FOOD_CAFE', 88000000, ?) RETURNING id
                """, Long.class, userId, LocalDate.now().minusYears(3));
        jdbc.update("DELETE FROM core.support_program");
    }

    /** 프로필이 응답에 실린다 — 화면이 「무엇을 기준으로 골랐는지」를 보여야 한다. */
    @Test
    void showsTheProfileItScoredWith() throws Exception {
        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.region").value("광주 서구"))
                .andExpect(jsonPath("$.data.profile.industryType").value("FOOD_CAFE"))
                .andExpect(jsonPath("$.data.profile.yearsInBusiness").value(3))
                .andExpect(jsonPath("$.data.disclaimer")
                        .value("자격 충족 여부는 주관 기관 확인이 필요합니다."));
    }

    /**
     * <b>조건이 많이 맞는 공고가 위로 온다.</b>
     *
     * <p>지역·업종이 맞는 공고(2/2 = 1.0)가 매출 상한에 걸리는 공고(2/3 ≒ 0.67)보다 앞이다.
     *
     * <p><b>업종이 안 맞는 공고로는 이걸 잴 수 없다</b> — 그런 공고는 후보를 뽑는 단계
     * (17.1 검색)에서 이미 빠진다. 카페 사장님에게 제조업 전용 지원금을 0.5 점으로 보여
     * 주는 것은 추천이 아니라 잡음이다.
     */
    @Test
    void ranksByHowManyConditionsMatch() throws Exception {
        insert("조건이 다 맞음", "[\"광주\"]", "[\"전 업종\"]", future());
        // 연매출 88,000,000 인 사업장이라 상한 100만원에 걸린다.
        jdbc.update("""
                INSERT INTO core.support_program
                    (source_name, external_id, title, source_url, support_type, status,
                     application_end_date, eligibility)
                VALUES ('BIZINFO', ?, '매출 상한에 걸림', 'https://example.test/1', 'SUBSIDY',
                        'OPEN', ?, '{"regions": ["전국"], "industries": ["전 업종"],
                                     "maxAnnualRevenue": 1000000}'::jsonb)
                """, "PBLN_" + System.nanoTime(), future());

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("조건이 다 맞음"))
                .andExpect(jsonPath("$.data.items[0].matchScore").value(1.0))
                .andExpect(jsonPath("$.data.items[0].matchedConditions.length()").value(2))
                .andExpect(jsonPath("$.data.items[1].matchScore").value(0.67))
                .andExpect(jsonPath("$.data.items[1].matchedConditions.length()").value(2));
    }

    /**
     * <b>조건을 하나도 안 건 공고는 0.5 다.</b>
     *
     * <p>0 으로 두면 목록 맨 아래로 밀려 사실상 안 보이는데, 그건 「해당 없음」이 아니라
     * 「모름」이다 — 실측상 공고의 35% 가 조건을 본문에 안 적는다.
     */
    @Test
    void treatsASilentAnnouncementAsUnknownNotDisqualified() throws Exception {
        insert("조건을 안 적은 공고", "[]", "[]", future());

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].matchScore").value(0.5))
                .andExpect(jsonPath("$.data.items[0].matchedConditions.length()").value(0));
    }

    /**
     * <b>지급 시기를 모르면 키 자체가 없다.</b>
     *
     * <p>이 테스트가 이 기능의 핵심이다. 응답 규약이 {@code non_null} 이라 빈 칸은 키가
     * 나가지 않고, 화면은 {@code timingNote} 로 「기관 확인 필요」를 말한다.
     */
    @Test
    void omitsArrivalWhenTheAnnouncementNeverSaysWhenMoneyComes() throws Exception {
        insert("지급 시기 없는 공고", "[\"광주\"]", "[\"전 업종\"]", future());

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].canArriveBeforeGap").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].expectedDisbursementDays").doesNotExist())
                // 비워 두지 않는다 — 빈 자리는 화면에서 「문제 없음」으로 읽힌다.
                .andExpect(jsonPath("$.data.items[0].timingNote").exists());
    }

    /** 마감이 지난 공고는 기본으로 빠진다. 지난 지원금을 추천하면 안 된다. */
    @Test
    void leavesOutAnnouncementsWhoseDeadlinePassed() throws Exception {
        insert("지난 공고", "[\"광주\"]", "[\"전 업종\"]", LocalDate.now().minusDays(1));
        insert("살아 있는 공고", "[\"광주\"]", "[\"전 업종\"]", future());

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("살아 있는 공고"));
    }

    /**
     * <b>완료된 예측이 없어도 추천은 나온다.</b>
     *
     * <p>곡선이 없다고 지원 제도 화면이 통째로 막히면 계좌를 막 연결한 사장님이 아무것도
     * 못 본다. {@code cashGap} 키만 빠진다.
     */
    @Test
    void stillRecommendsWhenThereIsNoForecastYet() throws Exception {
        insert("공고", "[\"광주\"]", "[\"전 업종\"]", future());

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cashGap").doesNotExist())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    /** 구조화되지 않은 공고는 추천에 안 나온다 — 자격을 모르는 공고를 지원금으로 내보내면 안 된다. */
    @Test
    void neverRecommendsAnUnstructuredAnnouncement() throws Exception {
        jdbc.update("""
                INSERT INTO core.support_program (source_name, external_id, title, source_url)
                VALUES ('BIZINFO', ?, '아직 안 읽은 공고', 'https://example.test/1')
                """, "PBLN_" + System.nanoTime());

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /** 남의 사업장은 403 이다 (BusinessGuard). */
    @Test
    void refusesSomeoneElsesBusiness() throws Exception {
        String other = "other-" + System.nanoTime() + "@test.local";
        Long otherUser = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '남') RETURNING id
                """, Long.class, other);
        String otherToken = tokenProvider.createAccessToken(otherUser, other);

        mockMvc.perform(get(path()).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
    }

    /** 로그인하지 않으면 닿지 않는다 (공통규약 2.1). */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(path())).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 도우미

    private String path() {
        return "/api/v1/businesses/" + businessId + "/support-programs/recommendations";
    }

    /**
     * <b>프로필이 비면 조건을 적은 공고가 0점으로 밀리지 않는다.</b>
     *
     * <p>점수식에는 「공고가 말하지 않은 조건은 분모에서 뺀다 — 말하지 않은 것으로 감점하지
     * 않는다」는 원칙이 있는데, 오래 <b>그 원칙을 공고 쪽에만</b> 적용했다. 사업장이 말하지
     * 않은 것(빈 프로필)은 「안 맞음」으로 처리했다.
     *
     * <p>그래서 프로필이 빈 사장님에게 <b>조건을 적은 공고가 0.0, 안 적은 공고가 0.5</b> 가
     * 되어 <b>정보가 가장 적은 공고가 맨 위</b>로 왔다. 오류가 아니라 정상 200 이라 화면이
     * 이유를 말해 줄 수단도 없었다.
     *
     * <p>이제 판정할 수 없는 조건은 양쪽에서 빠져 <b>둘 다 0.5 동률</b>이고, 정렬은 2차 기준인
     * 마감 임박순으로 떨어진다 — 「누가 더 맞는지 알 수 없으니 급한 것부터」다.
     */
    @Test
    void doesNotPunishProgramsForConditionsTheProfileCannotAnswer() throws Exception {
        emptyProfile();
        insert("조건을 적은 공고", "[\"부산\"]", "[\"제조업\"]", future().plusDays(10));
        insert("조건이 없는 공고", "[]", "[]", future());

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                // 둘 다 0.5 — 어느 쪽도 「더 맞는다」고 말할 수 없다
                .andExpect(jsonPath("$.data.items[0].matchScore").value(0.5))
                .andExpect(jsonPath("$.data.items[1].matchScore").value(0.5))
                // 동률이면 마감이 임박한 쪽이 위다
                .andExpect(jsonPath("$.data.items[0].title").value("조건이 없는 공고"))
                .andExpect(jsonPath("$.data.items[0].matchedConditions.length()").value(0))
                .andExpect(jsonPath("$.data.items[1].matchedConditions.length()").value(0));
    }

    /**
     * <b>「전국」·「전 업종」은 프로필이 비어도 맞은 것이다.</b>
     *
     * <p>모르는 게 아니다 — 그 공고가 <b>사업장이 무엇이든 대상</b>이라고 말하고 있다.
     * 이걸 「판정 불가」로 빼면 전국 공고가 0.5 로 내려가 지역 공고와 구분이 사라진다.
     */
    @Test
    void countsNationwideAndAllIndustriesEvenWithoutAProfile() throws Exception {
        emptyProfile();
        insert("전국·전 업종 공고", "[\"전국\"]", "[\"전 업종\"]", future());
        insert("조건이 없는 공고", "[]", "[]", future().plusDays(10));

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("전국·전 업종 공고"))
                .andExpect(jsonPath("$.data.items[0].matchScore").value(1.0))
                .andExpect(jsonPath("$.data.items[0].matchedConditions.length()").value(2))
                .andExpect(jsonPath("$.data.items[1].matchScore").value(0.5));
    }

    /**
     * <b>프로필이 일부만 비면 그 칸만 빠진다.</b>
     *
     * <p>지역은 알고 업종은 모르는 사업장이다. 지역 조건만 분모에 들어가야 하고, 업종은
     * 있으나 없으나 점수에 영향이 없어야 한다 — 둘 다 1.0 이다.
     */
    @Test
    void dropsOnlyTheConditionsTheProfileCannotAnswer() throws Exception {
        jdbc.update("UPDATE core.business SET industry_code = NULL, annual_revenue = NULL,"
                + " opened_on = NULL WHERE id = ?", businessId);
        insert("지역만 건 공고", "[\"광주\"]", "[]", future());
        insert("지역·업종을 건 공고", "[\"광주\"]", "[\"제조업\"]", future().plusDays(10));

        recommend().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].matchScore").value(1.0))
                .andExpect(jsonPath("$.data.items[1].matchScore").value(1.0))
                // 업종은 판정할 수 없어 맞은 조건에도 안 들어간다
                .andExpect(jsonPath("$.data.items[0].matchedConditions[0]").value("지역"))
                .andExpect(jsonPath("$.data.items[0].matchedConditions.length()").value(1))
                .andExpect(jsonPath("$.data.items[1].matchedConditions.length()").value(1));
    }

    /** 이름과 과세 유형만 넣고 만든 사업장이다 — 생성 API 가 나머지를 전부 선택으로 받는다. */
    private void emptyProfile() {
        jdbc.update("UPDATE core.business SET region = NULL, industry_code = NULL,"
                + " annual_revenue = NULL, opened_on = NULL WHERE id = ?", businessId);
    }

    private ResultActions recommend() throws Exception {
        return mockMvc.perform(get(path()).header("Authorization", "Bearer " + token));
    }

    private LocalDate future() {
        return LocalDate.now().plusDays(30);
    }

    private void insert(String title, String regions, String industries, LocalDate end) {
        jdbc.update("""
                INSERT INTO core.support_program
                    (source_name, external_id, title, organizer, source_url, support_type,
                     status, application_end_date, eligibility)
                VALUES ('BIZINFO', ?, ?, '광주광역시', 'https://example.test/1', 'SUBSIDY',
                        'OPEN', ?, jsonb_build_object('regions', ?::jsonb, 'industries', ?::jsonb))
                """, "PBLN_" + System.nanoTime(), title, end, regions, industries);
    }
}
