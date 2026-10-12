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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서류 발급처 사전 (17.4)과 그것이 붙는 공고 상세 (17.2) — P1-15.
 *
 * <p><b>여기서 지키려는 것은 「링크가 조용히 사라지지 않는다」다.</b> 서류명은 공고마다
 * 표기가 흔들리는데(「사업자등록증명」·「사업자 등록증명」·「법인등기부등본」), 이름이
 * 한 글자만 어긋나 사전에 못 닿으면 응답은 <b>오류가 아니라 링크만 빠진 정상 응답</b>이
 * 된다. 화면에서 티가 안 나고 사장님만 발급처를 찾아 헤맨다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DocumentGuideTests {

    private static final String PATH = "/api/v1/document-guides";
    private static final String PROGRAMS = "/api/v1/support-programs";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;

    private String token;

    @BeforeEach
    void setUp() {
        String email = "guide-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '사장') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        jdbc.update("DELETE FROM core.support_program");
    }

    // ------------------------------------------------------------------ 17.4 사전

    /** 조건이 없으면 사전 전체를 준다 — 공고와 무관하게 서류 안내를 보는 화면이 있다. */
    @Test
    void listsTheWholeDictionary() throws Exception {
        Integer seeded = jdbc.queryForObject(
                "SELECT count(*) FROM core.document_guide WHERE active", Integer.class);

        fetch("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(seeded))
                .andExpect(jsonPath("$.data.items[0].documentName").value("사업자등록증명"))
                .andExpect(jsonPath("$.data.items[0].issuer").value("홈택스"));
    }

    /**
     * <b>표제어에 없는 말로 찾아도 나온다.</b>
     *
     * <p>「법인등기부등본」은 사전의 표제어(「등기사항전부증명서(법인)」)에 한 글자도 겹치지
     * 않는다. 별칭을 안 보면 0건이고, 사장님은 공고에 적힌 표기를 그대로 입력한다.
     */
    @Test
    void findsByAliasNotJustTheHeadword() throws Exception {
        fetch("?q=법인등기부등본").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].documentName")
                        .value("등기사항전부증명서(법인)"));
    }

    /** 발급처로도 찾는다 — 「홈택스에서 떼는 게 뭐가 있나」를 묻는 화면이 있다. */
    @Test
    void findsByIssuer() throws Exception {
        fetch("?q=홈택스").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4));
    }

    /** {@code programId} 가 있으면 그 공고가 요구하는 서류만 준다. */
    @Test
    void narrowsToWhatTheProgramAsksFor() throws Exception {
        Long programId = insertProgram("""
                [{"documentName": "사업자등록증명"}, {"documentName": "통장 사본"}]
                """);

        fetch("?programId=" + programId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    /** 없는 공고는 17.2 와 같이 404 다. */
    @Test
    void unknownProgramIsNotFound() throws Exception {
        fetch("?programId=999999").andExpect(status().isNotFound());
    }

    /** 로그인하지 않으면 닿지 않는다 (공통규약 2.1). */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 17.2 연결

    /**
     * <b>워커가 비워 보낸 두 칸을 사전이 채운다.</b>
     *
     * <p>{@code issueUrl}·{@code fee} 는 20.6 출력에 없다 — 공고 본문에서 주워 온 URL 을
     * 실으면 죽은 링크가 그대로 나간다. 여기가 채워지지 않으면 17.2 는 「무슨 서류가
     * 필요한지」까지만 말하고 끝난다.
     */
    @Test
    void detailFillsIssueUrlAndFeeFromTheDictionary() throws Exception {
        Long programId = insertProgram("""
                [{"documentName": "사업자등록증명", "issuer": "홈택스"}]
                """);

        detail(programId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredDocuments[0].documentName")
                        .value("사업자등록증명"))
                .andExpect(jsonPath("$.data.requiredDocuments[0].issueUrl")
                        .value("https://www.hometax.go.kr"))
                .andExpect(jsonPath("$.data.requiredDocuments[0].fee").value(0));
    }

    /**
     * <b>표기가 흔들려도 붙는다.</b> 공백이 섞인 이름과 별칭 둘 다 사전에 닿아야 한다 —
     * 이게 깨지면 링크가 빠진 정상 응답이 나가서 아무도 눈치채지 못한다.
     */
    @Test
    void detailMatchesThroughSpacingAndAliases() throws Exception {
        Long programId = insertProgram("""
                [{"documentName": "사업자 등록증명"}, {"documentName": "법인등기부등본"}]
                """);

        detail(programId).andExpect(status().isOk())
                // 공백만 다른 표기 — 공고에 적힌 이름을 그대로 보여 주고 링크만 붙인다.
                .andExpect(jsonPath("$.data.requiredDocuments[0].documentName")
                        .value("사업자 등록증명"))
                .andExpect(jsonPath("$.data.requiredDocuments[0].issueUrl")
                        .value("https://www.hometax.go.kr"))
                // 별칭 — 표제어와 한 글자도 안 겹친다.
                .andExpect(jsonPath("$.data.requiredDocuments[1].issueUrl")
                        .value("http://www.iros.go.kr"))
                .andExpect(jsonPath("$.data.requiredDocuments[1].fee").value(1000));
    }

    /**
     * <b>사전에 없는 서류도 목록에 남는다.</b>
     *
     * <p>빼 버리면 준비해야 할 서류가 화면에서 조용히 사라지고, 사장님은 그런 서류가
     * 있었다는 사실 자체를 모른다. 링크가 없는 편이 낫다.
     */
    @Test
    void detailKeepsDocumentsTheDictionaryDoesNotKnow() throws Exception {
        Long programId = insertProgram("""
                [{"documentName": "사업계획서", "issuer": "직접 작성"}]
                """);

        detail(programId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredDocuments.length()").value(1))
                .andExpect(jsonPath("$.data.requiredDocuments[0].documentName")
                        .value("사업계획서"))
                .andExpect(jsonPath("$.data.requiredDocuments[0].issuer").value("직접 작성"))
                // 응답 규약이 non_null 이라 빈 칸은 키 자체가 나가지 않는다.
                .andExpect(jsonPath("$.data.requiredDocuments[0].issueUrl").doesNotExist())
                .andExpect(jsonPath("$.data.requiredDocuments[0].fee").doesNotExist());
    }

    /**
     * <b>발급처가 없는 서류는 링크도 없다.</b> 통장 사본은 떼는 게 아니라 갖고 있는 것이다 —
     * 사전에 담되 링크를 비워 「발급처를 찾아봐야 소용없다」를 말해 준다.
     */
    @Test
    void detailLeavesNoLinkForDocumentsThatAreNotIssued() throws Exception {
        Long programId = insertProgram("""
                [{"documentName": "통장 사본"}]
                """);

        detail(programId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredDocuments[0].issuer").value("거래 은행"))
                .andExpect(jsonPath("$.data.requiredDocuments[0].method").value("직접 준비"))
                .andExpect(jsonPath("$.data.requiredDocuments[0].issueUrl").doesNotExist());
    }

    /** 서류를 안 적은 공고는 빈 배열이다. 오류가 아니다 — 공고가 원래 그럴 수 있다. */
    @Test
    void detailHasAnEmptyListWhenTheProgramNamesNoDocuments() throws Exception {
        Long programId = insertProgram(null);

        detail(programId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredDocuments.length()").value(0));
    }

    /** 시드의 별칭은 정규화 규칙과 같은 형태여야 한다 — 어긋나면 영영 안 걸린다. */
    @Test
    void seededAliasesAreNormalized() {
        var keys = jdbc.queryForList("""
                SELECT k FROM core.document_guide g, unnest(g.match_keys) AS k
                """, String.class);

        assertThat(keys).isNotEmpty();
        assertThat(keys).allSatisfy(key ->
                assertThat(key).isEqualTo(DocumentGuideService.normalize(key)));
    }

    // ------------------------------------------------------------------ 도우미

    private ResultActions fetch(String query) throws Exception {
        return mockMvc.perform(
                get(PATH + query).header("Authorization", "Bearer " + token));
    }

    private ResultActions detail(Long programId) throws Exception {
        return mockMvc.perform(get(PROGRAMS + "/" + programId)
                .header("Authorization", "Bearer " + token));
    }

    /** 구조화된 공고 하나. {@code documents} 는 20.6 이 준 모양의 JSON 배열이다. */
    private Long insertProgram(String documents) {
        String eligibility = documents == null
                ? "{\"regions\": [\"광주\"]}"
                : "{\"regions\": [\"광주\"], \"requiredDocuments\": " + documents.strip() + "}";

        return jdbc.queryForObject("""
                INSERT INTO core.support_program
                    (source_name, external_id, title, source_url, support_type, status,
                     eligibility)
                VALUES ('BIZINFO', ?, '서류가 필요한 공고', 'https://example.test/1', 'SUBSIDY',
                        'OPEN', ?::jsonb)
                RETURNING id
                """, Long.class, "PBLN_" + System.nanoTime(), eligibility);
    }
}
