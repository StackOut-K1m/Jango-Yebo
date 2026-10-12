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

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 지원 제도 검색·상세 (17.1 · 17.2, P1-15).
 *
 * <p><b>여기서 지키려는 것은 「자격이 되는 공고를 걸러 버리지 않는다」다.</b> 엉뚱한 공고를
 * 하나 더 보여 주는 것은 사장님이 읽고 넘기면 그만이지만, 걸러진 공고는 <b>있었다는 사실
 * 자체를 모른다.</b> 그래서 모르면 남긴다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SupportProgramSearchTests {

    private static final String PATH = "/api/v1/support-programs";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;

    private String token;

    @BeforeEach
    void setUp() {
        String email = "program-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        // 다른 테스트가 넣어 둔 공고가 섞이지 않게 비우고 시작한다.
        jdbc.update("DELETE FROM core.support_program");
    }

    /** 구조화된 공고만 목록에 나온다 — 자격 조건을 모르는 공고를 지원금으로 내보내면 안 된다. */
    @Test
    void listsOnlyStructuredPrograms() throws Exception {
        insert("구조화된 공고", "SUBSIDY", "[\"광주\"]", "[\"요식업\"]", futureEnd(), "OPEN");
        insertUnstructured("아직 안 읽은 공고");

        search("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("구조화된 공고"));
    }

    /**
     * <b>지역을 모르면 남긴다.</b>
     *
     * <p>공고가 제한을 안 걸었거나 구조화가 못 읽은 것이고, 둘 다 「해당 없음」의 근거가 아니다.
     * 「전국」도 항상 통과다.
     */
    @Test
    void keepsProgramsWhoseRegionIsUnknownOrNationwide() throws Exception {
        insert("광주 공고", "SUBSIDY", "[\"광주\"]", "[]", futureEnd(), "OPEN");
        insert("전국 공고", "SUBSIDY", "[\"전국\"]", "[]", futureEnd(), "OPEN");
        insert("지역 미상 공고", "SUBSIDY", "[]", "[]", futureEnd(), "OPEN");
        insert("부산 공고", "SUBSIDY", "[\"부산\"]", "[]", futureEnd(), "OPEN");

        search("?region=광주").andExpect(jsonPath("$.data.totalElements").value(3));
    }

    /**
     * 기업마당 지역 태그는 「전남광주」처럼 묶여 온다.
     *
     * <p>한쪽 방향만 보면 안 맞는다 — 양방향 부분 일치가 필요한 실제 이유다.
     */
    @Test
    void matchesRegionTagsThatBundleTwoProvinces() throws Exception {
        insert("전남광주 공고", "SUBSIDY", "[\"전남광주\"]", "[]", futureEnd(), "OPEN");

        search("?region=광주").andExpect(jsonPath("$.data.totalElements").value(1));
        search("?region=광주 서구").andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /** 「전 업종」은 업종 필터를 항상 통과한다. */
    @Test
    void keepsProgramsOpenToEveryIndustry() throws Exception {
        insert("전 업종 공고", "SUBSIDY", "[]", "[\"요식업 포함 전 업종\"]", futureEnd(), "OPEN");
        insert("제조업 공고", "SUBSIDY", "[]", "[\"제조업\"]", futureEnd(), "OPEN");

        search("?industryType=요식업").andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /** 마감된 공고는 기본으로 빠지고, 달라고 하면 나온다. */
    @Test
    void hidesClosedProgramsUnlessAsked() throws Exception {
        insert("마감된 공고", "SUBSIDY", "[]", "[]", LocalDate.now().minusDays(1), "CLOSED");

        search("").andExpect(jsonPath("$.data.totalElements").value(0));
        search("?includeClosed=true").andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /**
     * <b>마감일이 지났는데 {@code status} 가 아직 {@code OPEN} 인 공고도 기본으로 빠진다.</b>
     *
     * <p>위 테스트만으로는 부족하다 — {@code status} 를 손으로 {@code 'CLOSED'} 라고 써 넣어
     * 실제로 벌어지는 일을 재현하지 못한다. {@code status} 는 수집·구조화 시점의 스냅샷이라
     * 달력이 넘어가도 저절로 바뀌지 않고, 마감돼 출처 목록에서 빠진 공고는 재수집되지도
     * 않는다. 그래서 <b>현장의 마감 공고는 {@code 'CLOSED'} 가 아니라 {@code 'OPEN'} 이다</b>
     * (2026-09-18 배포 DB 실측 — {@code 'OPEN'} 147건 중 16건이 마감일을 지나 있었고
     * {@code 'CLOSED'} 행은 하나도 없었다).
     *
     * <p>이게 새면 기본 검색에 지난 공고가 섞이고 「마감된 공고도 보기」가 아무 일도 하지
     * 않는 것처럼 보인다.
     */
    @Test
    void hidesProgramsPastDeadlineEvenWhenStatusIsStale() throws Exception {
        insert("스냅샷이 낡은 공고", "SUBSIDY", "[]", "[]", LocalDate.now().minusDays(1), "OPEN");

        search("").andExpect(jsonPath("$.data.totalElements").value(0));
        search("?includeClosed=true").andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /** 신청 기간을 모르는 공고는 빠지지 않는다 — 모르면 남긴다. */
    @Test
    void keepsProgramsWithoutDeadline() throws Exception {
        insert("상시 공고", "SUBSIDY", "[]", "[]", null, "UNKNOWN");

        search("").andExpect(jsonPath("$.data.totalElements").value(1));
    }

    /**
     * <b>맞춤 점수 정렬은 거절한다.</b>
     *
     * <p>이 경로에 사업장이 없어 점수의 입력이 없다. 조용히 다른 순서로 주면 화면이
     * 「가장 잘 맞는 것」을 맨 위로 보여 주고, 그건 근거 없는 추천이 된다.
     */
    @Test
    void rejectsMatchScoreSortAndPointsAt173() throws Exception {
        search("?sort=MATCH_SCORE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("recommendations")));
    }

    /** 모르는 종류를 조용히 무시하면 「그 종류가 하나도 없다」로 읽힌다. */
    @Test
    void rejectsAnUnknownSupportType() throws Exception {
        search("?supportType=NOPE").andExpect(status().isBadRequest());
    }

    /** 상세는 원문 링크와 수집일, 조건 전체를 준다 (17.2). */
    @Test
    void detailCarriesTheFullConditionsAndTheSourceLink() throws Exception {
        Long id = insert("상세 공고", "FUNDING", "[\"광주\"]", "[\"요식업\"]", futureEnd(), "OPEN");

        mockMvc.perform(get(PATH + "/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.sourceUrl").exists())
                .andExpect(jsonPath("$.data.summary.collectedAt").exists())
                .andExpect(jsonPath("$.data.eligibility.regions[0]").value("광주"))
                .andExpect(jsonPath("$.data.summary.needsVerification").value(false));
    }

    /**
     * 구조화되지 않은 공고는 상세도 404 다.
     *
     * <p>목록에 안 나오는 것을 상세에서만 볼 수 있으면 「자격 조건을 모르는 공고」가 링크
     * 하나로 사용자에게 도달한다.
     */
    @Test
    void detailHidesUnstructuredPrograms() throws Exception {
        Long id = insertUnstructured("아직 안 읽은 공고");

        mockMvc.perform(get(PATH + "/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROGRAM_NOT_FOUND"));
    }

    /** 확인 필요 표시는 불확실 조건 유무로 정해진다 (17.1). */
    @Test
    void flagsProgramsThatNeedVerification() throws Exception {
        jdbc.update("""
                INSERT INTO core.support_program
                    (source_name, external_id, title, source_url, support_type, status,
                     application_end_date, eligibility)
                VALUES ('BIZINFO', ?, '조건이 애매한 공고', 'https://example.test/1', 'SUBSIDY',
                        'OPEN', ?, '{"regions": ["광주"], "uncertainConditions": ["업력 산정 기준일"]}'::jsonb)
                """, "PBLN_" + System.nanoTime(), futureEnd());

        search("").andExpect(jsonPath("$.data.items[0].needsVerification").value(true));
    }

    /**
     * <b>시 단위 공고를 잃지 않는다.</b>
     *
     * <p>사업장 주소는 「전북 군산시」인데 공고는 「군산시」라고만 적는다. 가장 넓은 토막
     * (「전북」)으로만 줄여 대조하면 어느 쪽도 서로를 포함하지 않아 <b>걸러진다</b> —
     * 2026-09-17 실호출에서 군산시 공고 둘이 그렇게 사라졌다.
     *
     * <p>그렇다고 전체 문자열만 쓰면 반대로 「전남광주」 같은 묶음 태그를 놓친다.
     * <b>둘 다 봐야 한다.</b>
     */
    @Test
    void keepsCityLevelProgramsWhenTheProfileHasAProvincePrefix() throws Exception {
        insert("군산시 공고", "SUBSIDY", "[\"군산시\"]", "[\"전 업종\"]", futureEnd(), "OPEN");
        insert("전남광주 묶음 공고", "SUBSIDY", "[\"전남광주\"]", "[\"전 업종\"]", futureEnd(), "OPEN");

        byRegion("전북 군산시")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("군산시 공고"));

        // 묶음 태그는 가장 넓은 토막으로 걸린다 — 이쪽이 깨지면 안 된다.
        byRegion("광주 서구")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("전남광주 묶음 공고"));
    }

    /** 로그인하지 않으면 닿지 않는다 (공통규약 2.1). */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 도우미

    /** 한글 파라미터는 질의 문자열에 붙이지 않고 {@code param} 으로 넘긴다 — 인코딩이 끼면
     *  무엇을 재는지 흐려진다. */
    /**
     * <b>모양이 깨진 공고 한 건이 목록 전체를 죽이지 않는다.</b>
     *
     * <p>{@code eligibility} 는 워커가 준 JSON 을 검증 없이 그대로 담는다. 배열이어야 할
     * 칸에 스칼라가 들어가면 {@code jsonb_array_length} 가
     * {@code cannot get array length of a scalar} 로 터지고, <b>Postgres 가 트랜잭션을 통째로
     * 중단시켜</b> Java 에서 잡아도 소용이 없다.
     *
     * <p>2026-09-17 고장 주입에서 공고 <b>한 건</b>의 {@code regions} 를 문자열로 바꿨더니
     * 17.1 검색·17.3 추천·<b>10.2 정산 공백(P0)</b>이 한꺼번에 500 이 났다.
     *
     * <p><b>지역 필터를 안 줘도 터졌다</b> — Postgres 는 {@code OR} 분기의 평가 순서를
     * 보장하지 않아 {@code :region IS NULL} 가드가 앞에 있어도 뒤 분기를 먼저 계산할 수
     * 있다. 그래서 순서에 기대지 않고 {@code core.jsonb_text_array}(V33) 가 값을 먼저
     * 배열로 바꾼다. 아래 두 줄이 그 둘을 각각 재는 것이다.
     *
     * <p>깨진 값은 오류가 아니라 <b>「안 적혔다」</b>로 읽힌다 — 그래서 지역을 걸어도
     * 후보에 남는다(「모르면 남긴다」).
     */
    @Test
    void survivesEligibilityWhoseArrayFieldsAreNotArrays() throws Exception {
        insert("정상 공고", "SUBSIDY", "[\"광주\"]", "[\"전 업종\"]", futureEnd(), "OPEN");
        jdbc.update("""
                INSERT INTO core.support_program
                    (source_name, external_id, title, source_url, support_type, status,
                     application_end_date, eligibility)
                VALUES ('BIZINFO', ?, '모양이 깨진 공고', 'https://example.test/x', 'SUBSIDY',
                        'OPEN', ?, '{"regions": "광주", "industries": 3, "excludes": "폐업자"}'::jsonb)
                """, "PBLN_" + System.nanoTime(), futureEnd());

        // 필터 없이 — 여기서 터지면 목록 화면이 통째로 빈다
        search("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        // 지역·업종을 걸고 — 깨진 값은 「안 적혔다」라 남는다
        byRegion("광주").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));

        search("?industryType=FOOD_CAFE").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    private org.springframework.test.web.servlet.ResultActions byRegion(String region)
            throws Exception {
        return mockMvc.perform(get(PATH).param("region", region)
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions search(String query) throws Exception {
        return mockMvc.perform(get(PATH + query).header("Authorization", "Bearer " + token));
    }

    private LocalDate futureEnd() {
        return LocalDate.now().plusDays(30);
    }

    private Long insert(String title, String supportType, String regions, String industries,
                        LocalDate applicationEnd, String status) {
        return jdbc.queryForObject("""
                INSERT INTO core.support_program
                    (source_name, external_id, title, organizer, source_url, support_type,
                     status, application_end_date, eligibility)
                VALUES ('BIZINFO', ?, ?, '광주광역시', 'https://example.test/1', ?, ?, ?,
                        jsonb_build_object('regions', ?::jsonb, 'industries', ?::jsonb))
                RETURNING id
                """, Long.class, "PBLN_" + System.nanoTime(), title, supportType, status,
                applicationEnd, regions, industries);
    }

    /** 수집만 되고 아직 읽히지 않은 공고 — eligibility 가 빈 객체다. */
    private Long insertUnstructured(String title) {
        return jdbc.queryForObject("""
                INSERT INTO core.support_program (source_name, external_id, title, source_url)
                VALUES ('BIZINFO', ?, ?, 'https://example.test/2') RETURNING id
                """, Long.class, "PBLN_" + System.nanoTime(), title);
    }
}
