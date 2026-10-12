package com.jangoyebo.core.tax;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 서식 템플릿 조회 (API 14.3).
 *
 * <p><b>이 테스트가 지키는 것은 시드다.</b> 서식 정의를 자바가 아니라 데이터로 두기로 했으므로
 * (ERD 「서식 개정 시 데이터만 갱신한다」), 매핑표가 틀어지면 컴파일이 깨지지 않고 <b>초안
 * 금액이 조용히 달라진다</b>. 거래 카테고리 23종이 빠짐없이 매핑에 들어 있는지를 여기서 센다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TaxFilingFormApiTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaxFormTemplateRepository repository;

    private String token;
    private String email;

    @BeforeEach
    void setUp() {
        email = "taxform-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '서식조회') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
    }

    @Test
    void listsFourFormsForTheYear() throws Exception {
        mockMvc.perform(get("/api/v1/tax-filing-forms").param("taxYear", "2026")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].formType").value("SIMPLE_LEDGER"))
                .andExpect(jsonPath("$.data.items[0].supportLevel").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[0].eligibilityNote").exists());
    }

    /**
     * <b>이 문구는 사장님이 그대로 읽는다.</b> 화면이 서식 목록에 설명으로 붙인다.
     *
     * <p>처음 넣은 값은 개발자에게 하는 말이었다 — 「필요 서류 체크리스트와 준비자료
     * 패키지<b>(14.2)</b>만 안내<b>한다</b>」. 괄호 안은 우리 명세 절 번호이고 「~만 안내한다」는
     * 구현 범위를 설명하는 말이라, 사장님에게는 둘 다 읽을 이유가 없다({@code V36} 에서 고쳤다 ·
     * 프론트 회신 2026-09-18).
     *
     * <p><b>글자를 그대로 고정하지 않는다.</b> 문구는 다듬을 수 있어야 하고, 지켜야 하는 것은
     * 「사용자에게 하는 말인가」다. 그래서 <b>들어가면 안 되는 것</b>만 본다.
     */
    @Test
    void writesEligibilityNotesForTheOwnerNotForDevelopers() throws Exception {
        String body = mockMvc.perform(get("/api/v1/tax-filing-forms").param("taxYear", "2026")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> notes = com.jayway.jsonpath.JsonPath.read(body, "$.data.items[*].eligibilityNote");

        assertThat(notes).hasSize(4).allSatisfy(note -> {
            assertThat(note).as("설명이 비어 있으면 화면이 빈 줄을 그린다").isNotBlank();
            // 「(14.2)」 같은 우리 명세 절 번호. 사장님에게는 아무 뜻이 없다.
            assertThat(note).as("명세 절 번호가 노출되면 안 된다 — %s", note)
                    .doesNotMatch(".*\\(\\d+\\.\\d+\\).*");
            // 「~만 제공한다」·「~만 안내한다」는 우리 구현 범위를 설명하는 말투다.
            assertThat(note).as("구현 범위를 설명하는 말투가 남으면 안 된다 — %s", note)
                    .doesNotContain("만 제공한다", "만 안내한다");
            // 사용자에게 하는 말은 존댓말로 끝난다.
            assertThat(note).as("사용자 말투여야 한다 — %s", note)
                    .matches("(?s).*(니다|세요|습니다)\\.?$");
        });
    }

    /**
     * 안내만 하는 서식은 {@code sections} 키가 <b>아예 없어야</b> 한다.
     *
     * <p>빈 배열로 내보내면 화면이 "항목이 0개인 서식"으로 읽고 빈 표를 그린다 —
     * "지원하지 않는다"와 "비어 있다"는 다른 말이다(구현현황 3.3).
     */
    @Test
    void omitsSectionsForGuideOnlyForms() throws Exception {
        mockMvc.perform(get("/api/v1/tax-filing-forms").param("taxYear", "2026")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sections").isArray())
                .andExpect(jsonPath("$.data.items[?(@.formType == 'SIMPLE_RATE')].sections")
                        .isEmpty());
    }

    /**
     * 복식부기 준비 서류 목록 ({@code V37} · {@code S15P21C105-260}).
     *
     * <p><b>화면 상수로 두면 두 벌이 된다.</b> 웹과 앱이 같은 목록을 써야 하므로 서식 정의에
     * 둔다 — 서식에 딸린 정보라 있을 자리가 여기다.
     *
     * <p><b>{@code fields} 가 아니라 {@code items} 다.</b> 같은 {@code sections} 열에 들어가지만
     * 모양이 달라야 항목 렌더러가 실수로 집어 가지 못한다. {@code kind} 가 분기 근거다.
     */
    @Test
    void carriesTheDoubleEntryDocumentChecklistInTheFormDefinition() throws Exception {
        String body = mockMvc.perform(get("/api/v1/tax-filing-forms").param("taxYear", "2026")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String base = "$.data.items[?(@.formType == 'DOUBLE_ENTRY')].sections[0]";
        List<String> kinds = com.jayway.jsonpath.JsonPath.read(body, base + ".kind");
        assertThat(kinds).containsExactly("CHECKLIST");
        List<?> items = com.jayway.jsonpath.JsonPath.read(body, base + ".items[*].itemKey");
        assertThat(items).as("서류가 하나도 없으면 화면이 빈 목록을 그린다").isNotEmpty();
        // 초안 항목이 아니다 — fields 를 쓰면 항목 렌더러가 집어 간다.
        List<?> fields = com.jayway.jsonpath.JsonPath.read(body, base + ".fields");
        assertThat(fields).as("체크리스트에 fields 가 있으면 안 된다").isEmpty();
    }

    /**
     * <b>사장님이 직접 챙겨야 하는 것이 구분돼야 한다.</b> 목록만 주면 「무엇을 내가 해야
     * 하나」에 답하지 못한다 — 현금·개인카드·종이 서류는 우리가 보지 못한다.
     */
    @Test
    void marksWhichDocumentsThePackageAlreadyProvides() throws Exception {
        String body = mockMvc.perform(get("/api/v1/tax-filing-forms").param("taxYear", "2026")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();

        List<String> providedBy = com.jayway.jsonpath.JsonPath.read(body,
                "$.data.items[?(@.formType == 'DOUBLE_ENTRY')].sections[0].items[*].providedBy");

        assertThat(providedBy).allMatch(v -> v.equals("EXPORT_PACKAGE") || v.equals("OWNER"));
        assertThat(providedBy).as("우리가 주는 것과 사장님 몫이 둘 다 있어야 한다")
                .contains("EXPORT_PACKAGE", "OWNER");
    }

    /**
     * <b>체크리스트를 채워도 초안은 열리지 않는다.</b> {@code supportsDraft()} 가 지원 수준과
     * 칸 정의를 둘 다 보므로, {@code CHECKLIST_ONLY} 는 {@code sections} 가 차 있어도 대상이
     * 아니다 — {@code V37} 이 그 자리를 건드리기 때문에 못박아 둔다.
     */
    @Test
    void stillRefusesADraftForTheChecklistOnlyForm() throws Exception {
        Long businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name)
                VALUES ((SELECT id FROM core.users WHERE email = ?), '체크리스트 사업장')
                RETURNING id
                """, Long.class, email);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/businesses/" + businessId + "/tax-drafts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"taxYear":2026,"formType":"DOUBLE_ENTRY",
                                 "periodStart":"2026-01-01","periodEnd":"2026-12-31"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsEmptyListForAYearWithoutForms() throws Exception {
        mockMvc.perform(get("/api/v1/tax-filing-forms").param("taxYear", "2099")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/tax-filing-forms").param("taxYear", "2026"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * <b>거래 카테고리 23종이 모두 매핑에 있어야 한다.</b>
     *
     * <p>빠진 카테고리의 거래는 어느 항목에도 안 들어가고 제외 목록에도 안 잡힌다 —
     * 합계만 조용히 줄어든다. 팀원이 카테고리를 하나 추가하면 여기서 걸린다.
     */
    @Test
    void mapsEveryTransactionCategory() {
        TaxFormTemplate template = repository
                .findByFormTypeAndTaxYearAndActiveTrue("SIMPLE_LEDGER", 2026)
                .orElseThrow();

        var seeded = jdbc.queryForList(
                "SELECT code FROM core.transaction_category WHERE active = TRUE", String.class);

        assertThat(seeded).hasSize(23);
        assertThat(template.getCategoryMapping().keySet())
                .as("매핑되지 않은 거래 카테고리가 있으면 그 금액이 합계에서 조용히 사라진다")
                .containsExactlyInAnyOrderElementsOf(seeded);
    }

    /**
     * 매핑이 가리키는 항목은 실제로 서식에 있어야 한다.
     *
     * <p>오타 하나면 그 카테고리 금액이 갈 곳을 잃는다.
     */
    @Test
    void everyMappedFieldKeyExistsInSections() {
        TaxFormTemplate template = repository
                .findByFormTypeAndTaxYearAndActiveTrue("SIMPLE_LEDGER", 2026)
                .orElseThrow();

        var fieldKeys = template.getSections().stream()
                .flatMap(section -> ((java.util.List<?>) section.get("fields")).stream())
                .map(field -> (String) ((java.util.Map<?, ?>) field).get("fieldKey"))
                .toList();

        var mappedKeys = template.getCategoryMapping().values().stream()
                .map(v -> (String) ((java.util.Map<?, ?>) v).get("fieldKey"))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        assertThat(mappedKeys).isNotEmpty();
        assertThat(fieldKeys).containsAll(mappedKeys);
    }

    /**
     * <b>㊵ 일반관리비등계가 ㉕~㊴ 열다섯을 빠짐없이 더하는가</b> (V31 · 부표 원문 대조).
     *
     * <p>한 항목이 산식에서 빠지면 그 금액만큼 필요경비가 줄고 <b>소득금액이 늘어난다</b> —
     * 세금을 더 내게 만드는 방향이라 아무도 항의하지 않는다.
     */
    @Test
    void generalExpenseTotalSumsEveryLineAboveIt() {
        TaxFormTemplate template = repository
                .findByFormTypeAndTaxYearAndActiveTrue("SIMPLE_LEDGER", 2026)
                .orElseThrow();

        var expense = template.getSections().stream()
                .filter(section -> "EXPENSE".equals(section.get("sectionKey")))
                .findFirst().orElseThrow();
        var fields = (java.util.List<?>) expense.get("fields");

        var lineItems = fields.stream()
                .map(field -> (java.util.Map<?, ?>) field)
                .filter(field -> !"CALCULATED".equals(field.get("source")))
                .map(field -> (String) field.get("fieldKey"))
                .toList();
        String formula = fields.stream()
                .map(field -> (java.util.Map<?, ?>) field)
                .filter(field -> "GENERAL_EXPENSE_TOTAL".equals(field.get("fieldKey")))
                .map(field -> (String) field.get("formula"))
                .findFirst().orElseThrow();

        assertThat(lineItems).as("㉕~㊴ 는 열다섯 줄이다").hasSize(15);
        assertThat(lineItems)
                .as("㊵ 산식에서 빠진 항목이 있으면 소득금액이 그만큼 늘어난다")
                .allSatisfy(key -> assertThat(formula).contains(key));
    }

    /** 민감 항목은 서식 정의와 {@code sensitive_fields} 양쪽에서 같은 것을 가리켜야 한다. */
    @Test
    void sensitiveFieldsMatchTheSectionDefinition() {
        TaxFormTemplate template = repository
                .findByFormTypeAndTaxYearAndActiveTrue("SIMPLE_LEDGER", 2026)
                .orElseThrow();

        var markedInSections = template.getSections().stream()
                .flatMap(section -> ((java.util.List<?>) section.get("fields")).stream())
                .map(field -> (java.util.Map<?, ?>) field)
                .filter(field -> Boolean.TRUE.equals(field.get("sensitive")))
                .map(field -> (String) field.get("fieldKey"))
                .toList();

        assertThat(template.getSensitiveFields())
                .containsExactlyInAnyOrderElementsOf(markedInSections);
    }
}
