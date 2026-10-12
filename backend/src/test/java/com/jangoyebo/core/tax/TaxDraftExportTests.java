package com.jangoyebo.core.tax;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신고서 초안 내보내기 (API 14.3 {@code export}).
 *
 * <p><b>가장 조심할 것은 민감값이다.</b> 주소지·생년월일은 요청 본문에만 있어야 하고,
 * 만들어진 파일 말고는 <b>어디에도 남지 않아야</b> 한다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TaxDraftExportTests {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);
    private static final String XLSX = "{\"format\": \"XLSX_HOMETAX_INPUT\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private TaxDraftService service;
    @Autowired private TaxDraftAggregator aggregator;

    private Long userId;
    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        String email = "taxexport-" + unique + "@test.local";
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name, phone)
                VALUES (?, 'x', '정유선', '010-1234-5678') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business
                    (owner_user_id, name, registration_number_masked, industry_code)
                VALUES (?, '유선상회', '123-**-**789', '552305') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    // --- 픽스처 --------------------------------------------------------------

    private Long tx(Long business, Long account, String category, String flow, String amount) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, tax_review_status)
                VALUES (?, ?, ?, 'FIN_API', ?, DATE '2026-06-15', ?, ?, ?, ?,
                        'CONFIRMED', 'EVIDENCE_CONFIRMED')
                RETURNING id
                """, Long.class, business, account, categoryId,
                Timestamp.from(Instant.now()), flow, new BigDecimal(amount), category,
                "dedup-" + System.nanoTime());
    }

    private Long tx(String category, String flow, String amount) {
        return tx(businessId, accountId, category, flow, amount);
    }

    private Long generate() {
        return generate(businessId);
    }

    private Long generate(Long business) {
        TaxFilingDraft draft = service.request(
                business, userId, 2026, "SIMPLE_LEDGER", FROM, TO, null);
        aggregator.generate(draft.getId());
        return draft.getId();
    }

    private MvcResult export(Long draftId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/tax-drafts/" + draftId + "/export")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private static List<String> textOf(byte[] xlsx) throws Exception {
        List<String> lines = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                StringBuilder line = new StringBuilder();
                for (Cell cell : row) {
                    line.append(cell.getCellType() == CellType.NUMERIC
                            ? String.valueOf((long) cell.getNumericCellValue())
                            : cell.getStringCellValue()).append(" | ");
                }
                lines.add(line.toString());
            }
        }
        return lines;
    }

    private static String rowWith(List<String> lines, String label) {
        return lines.stream().filter(line -> line.startsWith(label + " |"))
                .findFirst().orElseThrow(() ->
                        new AssertionError("이런 줄이 없다: " + label + "\n" + String.join("\n", lines)));
    }

    // --- 내보내기 ------------------------------------------------------------

    @Test
    void writesTheSheetInHometaxInputOrder() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "10000000");
        tx("RENT", "WITHDRAWAL", "1200000");

        MvcResult result = export(generate(), XLSX);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment").contains("xlsx");

        List<String> lines = textOf(result.getResponse().getContentAsByteArray());
        String joined = String.join("\n", lines);

        assertThat(joined).contains("1. 기본사항");
        assertThat(joined).contains("2. 사업소득명세서");
        assertThat(joined).contains("3. 총수입금액 및 필요경비명세서");
        assertThat(joined).contains("4. 소득금액");
        // 우리가 계산하지 않는 영역도 줄은 있어야 한다 — 옮겨 적는 사람이 어디서 멈추는지 안다.
        assertThat(joined).contains("5. 소득공제").contains("6. 세액공제·감면").contains("7. 기납부세액");
        assertThat(joined).contains("확정 세무 자료가 아닙니다");
        assertThat(rowWith(lines, "㉗ 임차료")).contains("1200000");
    }

    /** 본표 ⑭ 와 40호 ⑨ 가 어긋나면 홈택스가 받지 않는다. */
    @Test
    void carriesTheCrossCheckRow() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "10000000");

        List<String> lines = textOf(export(generate(), XLSX)
                .getResponse().getContentAsByteArray());

        assertThat(rowWith(lines, "⑨ 총수입금액"))
                .contains("10000000")
                .contains("74호 본표 ⑭");
    }

    /**
     * <b>민감값은 파일에만 있다.</b>
     *
     * <p>DB 에 남으면 그 칸을 일부러 저장하지 않기로 한 설계가 무너진다.
     */
    @Test
    void putsSensitiveValuesInTheFileAndNowhereElse() throws Exception {
        Long draftId = generate();

        List<String> lines = textOf(export(draftId, """
                {"format": "XLSX_HOMETAX_INPUT",
                 "sensitiveValues": {"BIRTH_DATE": "1988-03-14",
                                     "HOME_ADDRESS": "광주광역시 서구 어딘가 123"}}
                """).getResponse().getContentAsByteArray());

        assertThat(rowWith(lines, "④ 생년월일")).contains("1988-03-14");
        assertThat(rowWith(lines, "① 주소지")).contains("광주광역시 서구 어딘가 123");

        String stored = jdbc.queryForObject("""
                SELECT COALESCE(fields::text, '') || COALESCE(overrides::text, '')
                  FROM core.tax_filing_draft WHERE id = ?
                """, String.class, draftId);
        assertThat(stored)
                .as("민감값이 초안 행에 남으면 안 된다")
                .doesNotContain("1988-03-14")
                .doesNotContain("광주광역시");
    }

    /** 비어 있어도 내보낼 수 있다. 빈 칸은 「직접 기재」로 남는다(명세 14.3). */
    @Test
    void exportsWithoutSensitiveValuesAndMarksThemManual() throws Exception {
        List<String> lines = textOf(export(generate(), XLSX)
                .getResponse().getContentAsByteArray());

        assertThat(rowWith(lines, "① 주소지")).contains("직접 기재");
        assertThat(rowWith(lines, "주민등록번호")).contains("홈택스에서 직접 입력");
        assertThat(rowWith(lines, "환급금 계좌")).contains("홈택스에서 직접 입력");
    }

    @Test
    void writesTheOverriddenValueNotTheAggregatedOne() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000");
        Long draftId = generate();

        mockMvc.perform(patch("/api/v1/tax-drafts/" + draftId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"overrides": [{"fieldKey": "RENT", "value": 1000000}]}
                                """))
                .andExpect(status().isOk());

        assertThat(rowWith(textOf(export(draftId, XLSX).getResponse().getContentAsByteArray()),
                "㉗ 임차료")).contains("1000000").doesNotContain("1200000");
    }

    // --- 거절 ----------------------------------------------------------------

    @Test
    void refusesADraftThatIsNotReady() throws Exception {
        Long rent = tx("RENT", "WITHDRAWAL", "1200000");
        Long draftId = generate();
        jdbc.update("""
                UPDATE core.transaction SET tax_reviewed_at = now() + interval '1 minute' WHERE id = ?
                """, rent);

        mockMvc.perform(post("/api/v1/tax-drafts/" + draftId + "/export")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(XLSX))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TAX_DRAFT_NOT_READY"));
    }

    /** 조용히 XLSX 를 주면 사장님은 PDF 를 받은 줄 알고 그것을 제출하려 한다. */
    @Test
    void refusesPdfUntilItExists() throws Exception {
        mockMvc.perform(post("/api/v1/tax-drafts/" + generate() + "/export")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"format\": \"PDF\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXPORT_FORMAT_NOT_SUPPORTED"));
    }

    // --- 사업장 병합 ----------------------------------------------------------

    /** 서식은 개인 단위다. 사업장이 여럿이면 열로 합치고 합계 열을 둔다. */
    @Test
    void mergesBusinessesIntoColumnsWithATotal() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000");
        Long draftId = generate();

        Long second = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회 2호점') RETURNING id
                """, Long.class, userId);
        Long secondAccount = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***5678', '2호점', 'CONNECTED') RETURNING id
                """, Long.class, second, "hash2-" + System.nanoTime());
        tx(second, secondAccount, "RENT", "WITHDRAWAL", "800000");
        generate(second);

        List<String> lines = textOf(export(draftId, """
                {"format": "XLSX_HOMETAX_INPUT", "includeBusinessIds": [%d]}
                """.formatted(second)).getResponse().getContentAsByteArray());

        assertThat(String.join("\n", lines)).contains("유선상회").contains("2호점").contains("합계");
        assertThat(rowWith(lines, "㉗ 임차료"))
                .contains("1200000").contains("800000").contains("2000000");
    }

    @Test
    void refusesToMergeAnotherUsersBusiness() throws Exception {
        Long draftId = generate();
        Long stranger = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '남') RETURNING id
                """, Long.class, "stranger-" + System.nanoTime() + "@test.local");
        Long strangerBusiness = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '남의 가게') RETURNING id
                """, Long.class, stranger);

        mockMvc.perform(post("/api/v1/tax-drafts/" + draftId + "/export")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"format": "XLSX_HOMETAX_INPUT", "includeBusinessIds": [%d]}
                                """.formatted(strangerBusiness)))
                .andExpect(status().isForbidden());
    }

    // --- 이번에 함께 넣은 둘 ---------------------------------------------------

    /**
     * <b>정산을 고쳐도 상한다.</b>
     *
     * <p>⑪ 매출액이 정산의 총매출에서 오므로 정산만 바뀌어도 금액이 달라진다 — 거래는
     * 하나도 안 움직이는데 초안이 {@code READY} 로 남으면 매출이 틀린 신고서가 나간다.
     */
    @Test
    void marksStaleWhenASettlementChanges() throws Exception {
        tx("CASH_SALES", "DEPOSIT", "1000000");
        Long draftId = generate();

        jdbc.update("""
                INSERT INTO core.settlement
                    (business_id, platform_name, sales_from, sales_to,
                     gross_sales, cancellation_amount, fee_amount, status, updated_at)
                VALUES (?, '배달의민족', DATE '2026-06-01', DATE '2026-06-30',
                        3000000, 0, 300000, 'MATCHED', now() + interval '1 minute')
                """, businessId);

        mockMvc.perform(get("/api/v1/tax-drafts/" + draftId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("STALE"));
    }

    /**
     * <b>재생성이 사장님의 입력을 물려받는다.</b>
     *
     * <p>안 물려주면 거래 하나가 바뀔 때마다 감가상각비·재고·세무조정을 처음부터 다시
     * 적어야 한다 — {@code STALE} 이 「버튼 한 번」이 아니라 「작업을 다시 하라」가 된다.
     */
    @Test
    void regenerationInheritsWhatTheOwnerTyped() throws Exception {
        tx("RENT", "WITHDRAWAL", "1200000");
        Long first = generate();

        mockMvc.perform(patch("/api/v1/tax-drafts/" + first)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"overrides": [{"fieldKey": "DEPRECIATION", "value": 680000,
                                                "note": "정액법 5년"}],
                                 "hometaxReference": {"ntsRevenue": 98500000}}
                                """))
                .andExpect(status().isOk());

        Long second = generate();
        assertThat(second).isNotEqualTo(first);

        mockMvc.perform(get("/api/v1/tax-drafts/" + second)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.sections[?(@.sectionKey == 'EXPENSE')].fields[?(@.fieldKey == 'DEPRECIATION')].value")
                        .value(680000))
                // 화면이 「지난번에 적으신 값입니다」로 구분해 줘야 그대로 써도 되는지 판단한다.
                .andExpect(jsonPath(
                        "$.data.sections[?(@.sectionKey == 'EXPENSE')].fields[?(@.fieldKey == 'DEPRECIATION')].carriedOver")
                        .value(true))
                .andExpect(jsonPath("$.data.hometaxReference.ntsRevenue").value(98500000));
    }
}
