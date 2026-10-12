package com.jangoyebo.core.export;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobRepository;
import com.jangoyebo.core.job.JobService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 준비자료 시트 (API 14.2 — 02·03·06·07).
 *
 * <p><b>이 시트들이 틀리면 조용히 틀린다.</b> 세무대리인은 우리 집계를 검산하려고 이것을
 * 받는데, 거래 한 줄이 빠져 있어도 파일은 멀쩡히 열린다. 그래서 여기서 보는 것은 모양이
 * 아니라 <b>무엇이 빠지지 않았는가</b>다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class ExportSheetTests {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);
    private static final DataFormatter FORMATTER = new DataFormatter();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobService jobService;
    @Autowired private ExportService exportService;
    @Autowired private ExportGenerationHandler handler;
    @Autowired private com.jangoyebo.core.storage.UploadStorage storage;

    private Long userId;
    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선')
                RETURNING id
                """, Long.class, "sheet-" + unique + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '유선상회') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    // --- 픽스처 --------------------------------------------------------------

    private Long tx(String category, String flow, String amount,
                    String reviewStatus, String classificationStatus) {
        Long categoryId = category == null ? null : jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, tax_review_status)
                VALUES (?, ?, ?, 'FIN_API', ?, DATE '2026-06-15', ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                Timestamp.from(Instant.now()), flow, new BigDecimal(amount),
                category + " 지출", "dedup-" + System.nanoTime(), classificationStatus,
                reviewStatus);
    }

    private Long withdrawal(String category, String amount, String reviewStatus) {
        return tx(category, "WITHDRAWAL", amount, reviewStatus, "CONFIRMED");
    }

    private Long evidence(String fileName, String type, boolean deleted) {
        return jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, evidence_type, original_filename, storage_key,
                     mime_type, file_size, processing_status, issued_on, deleted_at)
                VALUES (?, ?, ?, ?, ?, 'image/jpeg', 1024, 'CONFIRMED', DATE '2026-06-15', ?)
                RETURNING id
                """, Long.class, businessId, userId, type, fileName,
                "evidences/%d/%d.jpg".formatted(businessId, System.nanoTime()),
                deleted ? Timestamp.from(Instant.now()) : null);
    }

    private void link(Long transactionId, Long evidenceId) {
        jdbc.update("""
                INSERT INTO core.transaction_evidence (transaction_id, evidence_id, match_status)
                VALUES (?, ?, 'LINKED')
                """, transactionId, evidenceId);
    }

    // --- 패키지 열기 ---------------------------------------------------------

    private Map<String, byte[]> generate() {
        Job job = exportService.request(businessId, userId, ExportType.TAX_PREP, FROM, TO, false);
        JobHandler.Outcome outcome = handler.handle(jobRepository.findById(job.getId()).orElseThrow());
        Map<String, Object> result = ((JobHandler.Outcome.Completed) outcome).result();
        jobService.complete(job.getId(), result);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                storage.openRead(result.get("storageKey").toString()))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        } catch (Exception e) {
            throw new IllegalStateException("패키지를 열지 못했다", e);
        }
        return entries;
    }

    /** 시트 한 장을 줄 단위 문자열 표로. 빈 줄은 그대로 남긴다 — 구획 경계가 거기 있다. */
    private List<List<String>> sheet(Map<String, byte[]> entries, String fileName) {
        byte[] bytes = entries.get(fileName);
        assertThat(bytes).as("패키지에 %s 가 없다", fileName).isNotNull();
        List<List<String>> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                List<String> values = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        values.add(cell == null ? "" : FORMATTER.formatCellValue(cell));
                    }
                }
                rows.add(values);
            }
        } catch (Exception e) {
            throw new IllegalStateException("시트를 읽지 못했다: " + fileName, e);
        }
        return rows;
    }

    private static String flatten(List<List<String>> rows) {
        StringBuilder text = new StringBuilder();
        rows.forEach(row -> text.append(String.join("|", row)).append('\n'));
        return text.toString();
    }

    // --- 패키지 구성 ---------------------------------------------------------

    @Test
    void putsTheFourTransactionSheetsInThePackage() {
        assertThat(generate().keySet()).contains(
                "02_사업용계좌_거래내역.xlsx",
                "03_필요경비_후보명세서.xlsx",
                "06_증빙누락_확인필요.xlsx",
                "07_거래증빙_연결목록.xlsx");
    }

    // --- 02 거래내역 ---------------------------------------------------------

    @Test
    void writesEveryTransactionInThePeriod() {
        withdrawal("RENT", "1200000", "EVIDENCE_CONFIRMED");
        tx("PLATFORM_SETTLEMENT", "DEPOSIT", "2000000", "UNREVIEWED", "CONFIRMED");

        String text = flatten(sheet(generate(), "02_사업용계좌_거래내역.xlsx"));

        assertThat(text).contains("입금", "출금", "1,200,000", "2,000,000");
        // 검토 상태는 한글이다. 세무대리인에게 enum 이름을 보낼 수 없다.
        assertThat(text).contains("증빙 확인 완료", "미검토").doesNotContain("EVIDENCE_CONFIRMED");
    }

    // --- 03 필요경비 후보 명세서 ---------------------------------------------

    /**
     * <b>출금 한 건도 사라지면 안 된다.</b> 구획을 조건마다 따로 쓰면 어디에도 못 끼는 값이
     * 생기는데, 파일은 멀쩡히 열리므로 아무도 모른다. {@code ASSET_REVIEW} 가 그 자리다 —
     * 아직 아무도 부여하지 않는 예약값이라 더 눈에 안 띈다.
     */
    @Test
    void everyWithdrawalLandsInExactlyOneSection() {
        withdrawal("RENT", "1200000", "EVIDENCE_CONFIRMED");
        withdrawal("SUPPLIES", "300000", "UNREVIEWED");
        withdrawal("SUPPLIES", "500000", "EXPERT_REVIEW");
        withdrawal("SUPPLIES", "700000", "PERSONAL_SUSPECTED");
        withdrawal("SUPPLIES", "900000", "ASSET_REVIEW");
        tx("SUPPLIES", "WITHDRAWAL", "110000", "UNREVIEWED", "SUGGESTED");

        List<List<String>> rows = sheet(generate(), "03_필요경비_후보명세서.xlsx");
        String text = flatten(rows);

        assertThat(text).contains("1,200,000", "300,000", "500,000", "700,000", "900,000", "110,000");

        // 금액이 적힌 줄만 세면 출금 건수와 같아야 한다 — 한 건이 두 구획에 들어가도 걸린다.
        long amountRows = rows.stream()
                .filter(row -> row.size() > 2 && row.get(2).matches("[\\d,]+"))
                .count();
        assertThat(amountRows).as("출금 6건이 구획 네 곳에 겹치지도 빠지지도 않고 들어가야 한다")
                .isEqualTo(6);
    }

    /** 개인 사용 의심분을 지우면 세무대리인이 그 판단을 다시 볼 근거가 사라진다. */
    @Test
    void keepsPersonalSuspectedInItsOwnSection() {
        withdrawal("SUPPLIES", "700000", "PERSONAL_SUSPECTED");

        String text = flatten(sheet(generate(), "03_필요경비_후보명세서.xlsx"));

        assertThat(text).contains("개인 사용 가능성 — 경비에서 제외", "700,000");
    }

    /** 명칭 고정 — 「필요경비 명세서」로 쓰면 우리가 경비를 확정한 것이 된다(도메인 규칙 4). */
    @Test
    void callsThemCandidatesNotConfirmedExpenses() {
        List<List<String>> rows = sheet(generate(), "03_필요경비_후보명세서.xlsx");

        assertThat(rows.get(0).get(0)).isEqualTo("필요경비 후보 명세서");
    }

    /** 분류가 안 끝난 거래를 빼면 0원으로 읽힌다 — 14.3 이 unclassified 를 따로 두는 것과 같다. */
    @Test
    void saysWhyUnclassifiedWithdrawalsAreNotCounted() {
        tx("SUPPLIES", "WITHDRAWAL", "110000", "UNREVIEWED", "SUGGESTED");

        String text = flatten(sheet(generate(), "03_필요경비_후보명세서.xlsx"));

        assertThat(text).contains("미분류 — 집계에 넣지 않음", "0원이라는 뜻이 아닙니다");
    }

    // --- 06 증빙누락·확인필요 -------------------------------------------------

    @Test
    void separatesMissingEvidenceFromNeedsReview() {
        withdrawal("SUPPLIES", "300000", "EVIDENCE_MISSING");
        Long reviewed = withdrawal("RENT", "1200000", "EXPERT_REVIEW");
        link(reviewed, evidence("임차료.jpg", "RECEIPT", false));

        List<List<String>> rows = sheet(generate(), "06_증빙누락_확인필요.xlsx");

        // 구획을 「제목 줄과 같은 칸」으로 찾는다. 통째로 이어 붙인 글자에서 찾으면 맨 윗줄의
        // 시트 제목(「증빙 누락 · 확인 필요 거래」)에 걸려 **아무것도 검사하지 않게 된다**.
        assertThat(flatten(sectionRows(rows, "증빙 누락", "확인 필요")))
                .as("증빙이 붙은 거래가 누락 구획에 들어가면 안 된다")
                .contains("300,000")
                .doesNotContain("1,200,000");
        assertThat(flatten(sectionRows(rows, "확인 필요", null)))
                .contains("1,200,000")
                .doesNotContain("300,000");
    }

    /**
     * 첫 칸이 {@code label} 과 <b>정확히 같은</b> 줄을 구획 머리로 보고, 다음 구획 전까지 돌려준다.
     *
     * @param nextLabel 다음 구획의 머리. {@code null} 이면 끝까지
     */
    private static List<List<String>> sectionRows(List<List<String>> rows, String label,
                                                  String nextLabel) {
        int start = indexOfHeader(rows, label);
        assertThat(start).as("구획 「%s」이 없다", label).isNotNegative();
        int end = nextLabel == null ? rows.size() : indexOfHeader(rows, nextLabel);
        assertThat(end).as("구획 「%s」이 「%s」보다 뒤에 있어야 한다", nextLabel, label)
                .isGreaterThan(start);
        return rows.subList(start + 1, end);
    }

    private static int indexOfHeader(List<List<String>> rows, String label) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() && label.equals(rows.get(i).get(0))) {
                return i;
            }
        }
        return -1;
    }

    // --- 07 거래증빙 연결목록 -------------------------------------------------

    @Test
    void listsOneRowPerEvidenceAndDropsDeletedOnes() {
        Long transaction = withdrawal("SUPPLIES", "300000", "EVIDENCE_CONFIRMED");
        link(transaction, evidence("영수증1.jpg", "RECEIPT", false));
        link(transaction, evidence("영수증2.jpg", "CASH_RECEIPT", false));
        link(transaction, evidence("지운영수증.jpg", "RECEIPT", true));

        String text = flatten(sheet(generate(), "07_거래증빙_연결목록.xlsx"));

        // 거래 하나에 증빙 둘이면 두 줄이다 — 여기는 합계를 내지 않으므로 그래야 맞다.
        assertThat(text).contains("영수증1.jpg", "영수증2.jpg", "영수증", "현금영수증");
        // 지운 증빙을 적으면 세무대리인이 없는 파일을 찾는다.
        assertThat(text).doesNotContain("지운영수증.jpg");
    }

    /** 모든 시트에 기준일·대상 기간·개인정보 고지가 들어간다(명세 14.2). */
    @Test
    void stampsEverySheetWithThePeriodAndTheNotice() {
        Map<String, byte[]> entries = generate();

        for (String name : List.of("02_사업용계좌_거래내역.xlsx", "03_필요경비_후보명세서.xlsx",
                "06_증빙누락_확인필요.xlsx", "07_거래증빙_연결목록.xlsx")) {
            String text = flatten(sheet(entries, name));
            assertThat(text).as("%s 에 대상 기간·기준일이 없다", name)
                    .contains("대상 기간 2026-01-01 ~ 2026-12-31", "기준일");
            assertThat(text).as("%s 에 개인정보 고지가 없다", name).contains("개인정보");
        }
    }
}
