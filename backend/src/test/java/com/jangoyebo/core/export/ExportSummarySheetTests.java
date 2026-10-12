package com.jangoyebo.core.export;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobHandler;
import com.jangoyebo.core.job.JobRepository;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.storage.UploadStorage;
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
import java.nio.charset.StandardCharsets;
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
 * 매출·정산·고정비 시트와 증빙 원본 (API 14.2 — 01·04·05·08).
 *
 * <p><b>매출을 14.3 신고서 초안과 같은 기준으로 세는지가 이 시트들의 전부다.</b> 두 산출물이
 * 같은 기간에 다른 매출을 말하면 세무대리인은 우리 집계를 통째로 버린다. 특히 정산 수수료를
 * 매출에서 빼거나 정산에 연결된 입금을 총매출과 함께 더하면 숫자가 조용히 어긋난다.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
class ExportSummarySheetTests {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);
    private static final DataFormatter FORMATTER = new DataFormatter();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobService jobService;
    @Autowired private ExportService exportService;
    @Autowired private ExportGenerationHandler handler;
    @Autowired private UploadStorage storage;

    private Long userId;
    private Long businessId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name) VALUES (?, 'x', '정유선')
                RETURNING id
                """, Long.class, "summary-" + unique + "@test.local");
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

    private Long tx(String category, String flow, String amount, String date) {
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM core.transaction_category WHERE code = ?", Long.class, category);
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, category_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, deduplication_key,
                     classification_status, tax_review_status)
                VALUES (?, ?, ?, 'FIN_API', ?, ?::date, ?, ?, ?, ?, 'CONFIRMED', 'UNREVIEWED')
                RETURNING id
                """, Long.class, businessId, accountId, categoryId,
                Timestamp.from(Instant.now()), date, flow, new BigDecimal(amount),
                category + " 거래", "dedup-" + System.nanoTime());
    }

    /** 정산 한 건과 그 입금 거래. 입금은 수수료를 뗀 뒤의 금액이다. */
    private void settlement(String gross, String cancellations, String fees, String salesTo,
                            String status, Long depositTransactionId) {
        Long settlementId = jdbc.queryForObject("""
                INSERT INTO core.settlement
                    (business_id, platform_name, sales_from, sales_to,
                     gross_sales, cancellation_amount, fee_amount, status)
                VALUES (?, '배달의민족', ?::date, ?::date, ?, ?, ?, ?) RETURNING id
                """, Long.class, businessId, salesTo, salesTo,
                new BigDecimal(gross), new BigDecimal(cancellations), new BigDecimal(fees), status);
        if (depositTransactionId != null) {
            BigDecimal allocated = new BigDecimal(gross)
                    .subtract(new BigDecimal(cancellations)).subtract(new BigDecimal(fees));
            jdbc.update("""
                    INSERT INTO core.settlement_transaction
                        (settlement_id, transaction_id, allocated_amount)
                    VALUES (?, ?, ?)
                    """, settlementId, depositTransactionId, allocated);
        }
    }

    private Long evidence(String fileName) {
        return jdbc.queryForObject("""
                INSERT INTO core.evidence
                    (business_id, uploaded_by, evidence_type, original_filename, storage_key,
                     mime_type, file_size, processing_status)
                VALUES (?, ?, 'RECEIPT', ?, ?, 'image/jpeg', 12, 'CONFIRMED') RETURNING id
                """, Long.class, businessId, userId, fileName,
                "evidences/%d/%d.jpg".formatted(businessId, System.nanoTime()));
    }

    private void link(Long transactionId, Long evidenceId) {
        jdbc.update("""
                INSERT INTO core.transaction_evidence (transaction_id, evidence_id, match_status)
                VALUES (?, ?, 'LINKED')
                """, transactionId, evidenceId);
    }

    /** 실제 파일을 디스크에 쓴다 — 08 폴더가 진짜 바이트를 담는지 보려면 있어야 한다. */
    private void writeFile(Long evidenceId, String content) {
        String key = jdbc.queryForObject(
                "SELECT storage_key FROM core.evidence WHERE id = ?", String.class, evidenceId);
        try (var out = storage.openWrite(key)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("증빙 파일을 쓰지 못했다", e);
        }
    }

    // --- 패키지 열기 ---------------------------------------------------------

    private Map<String, byte[]> generate(boolean includeEvidenceFiles) {
        Job job = exportService.request(
                businessId, userId, ExportType.TAX_PREP, FROM, TO, includeEvidenceFiles);
        JobHandler.Outcome outcome =
                handler.handle(jobRepository.findById(job.getId()).orElseThrow());
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

    /** 첫 칸이 {@code label} 과 정확히 같은 줄. 이어 붙인 글자에서 찾으면 제목에 걸린다. */
    private static List<String> rowStartingWith(List<List<String>> rows, String label) {
        for (List<String> row : rows) {
            if (!row.isEmpty() && label.equals(row.get(0))) {
                return row;
            }
        }
        throw new AssertionError("「%s」로 시작하는 줄이 없다".formatted(label));
    }

    private static String flatten(List<List<String>> rows) {
        StringBuilder text = new StringBuilder();
        rows.forEach(row -> text.append(String.join("|", row)).append('\n'));
        return text.toString();
    }

    // --- 패키지 구성 ---------------------------------------------------------

    @Test
    void putsEverySheetInThePackage() {
        assertThat(generate(false).keySet()).containsExactly(
                "00_안내.txt",
                "01_연간_매출요약.xlsx",
                "02_사업용계좌_거래내역.xlsx",
                "03_필요경비_후보명세서.xlsx",
                "04_플랫폼별_정산대사표.xlsx",
                "05_고정비_반복지출.xlsx",
                "06_증빙누락_확인필요.xlsx",
                "07_거래증빙_연결목록.xlsx");
    }

    /** 증빙을 뺀 것과 증빙이 없는 것은 다른 말이다. 안내문이 그것을 적어야 한다. */
    @Test
    void theGuideSaysWhenEvidenceWasLeftOut() {
        String guide = new String(generate(false).get("00_안내.txt"), StandardCharsets.UTF_8);

        assertThat(guide).contains("증빙 원본 : 미포함", "요청에서 제외했습니다");
    }

    // --- 01 연간 매출요약 -----------------------------------------------------

    /**
     * <b>매출액 = 총매출 − 취소.</b> 수수료를 빼면 신고서 초안 ⑪ 과 어긋나고, 정산에 연결된
     * 입금을 총매출에 더하면 매출이 두 번 계상된다.
     */
    @Test
    void countsSalesTheSameWayTheDraftDoes() {
        Long deposit = tx("PLATFORM_SETTLEMENT", "DEPOSIT", "850000", "2026-06-20");
        settlement("1000000", "50000", "100000", "2026-06-30", "MATCHED", deposit);

        List<List<String>> rows = sheet(generate(false), "01_연간_매출요약.xlsx");
        List<String> total = rowStartingWith(rows, "합계");

        assertThat(total.get(1)).as("정산 총매출").isEqualTo("1,000,000");
        assertThat(total.get(2)).as("취소·환불").isEqualTo("50,000");
        assertThat(total.get(3)).as("정산에 연결된 입금은 여기 들어오면 안 된다").isEqualTo("0");
        assertThat(total.get(4)).as("매출액 = 1,000,000 − 50,000. 수수료는 빼지 않는다")
                .isEqualTo("950,000");
        assertThat(total.get(5)).as("수수료는 따로 보여 주되 매출에서 빼지 않는다")
                .isEqualTo("100,000");
    }

    /** 정산에 안 붙은 매출성 입금은 빠지면 안 된다 — 현금·계좌이체 매출이 그렇다. */
    @Test
    void addsSalesDepositsThatHaveNoSettlement() {
        tx("CASH_SALES", "DEPOSIT", "300000", "2026-06-20");
        tx("LOAN_DISBURSEMENT", "DEPOSIT", "10000000", "2026-06-21");

        List<String> total = rowStartingWith(
                sheet(generate(false), "01_연간_매출요약.xlsx"), "합계");

        assertThat(total.get(3)).as("정산 미연결 입금").isEqualTo("300,000");
        assertThat(total.get(4)).as("대출 실행은 매출이 아니다").isEqualTo("300,000");
    }

    // --- 04 플랫폼별 정산대사표 ----------------------------------------------

    @Test
    void showsTheGapBetweenExpectedAndActualDeposits() {
        Long deposit = tx("PLATFORM_SETTLEMENT", "DEPOSIT", "800000", "2026-06-20");
        settlement("1000000", "50000", "100000", "2026-06-30", "MISMATCHED", deposit);

        String text = flatten(sheet(generate(false), "04_플랫폼별_정산대사표.xlsx"));

        // 받아야 할 돈 850,000 인데 850,000 을 배분했다 — 픽스처가 순액으로 붙인다.
        assertThat(text).contains("배달의민족", "1,000,000", "850,000", "불일치");
    }

    /** 대사가 안 맞았다는 것은 입금 확인이 덜 됐다는 뜻이지 매출이 없었다는 뜻이 아니다. */
    @Test
    void keepsSettlementsThatNeverMatched() {
        settlement("700000", "0", "70000", "2026-07-31", "PENDING", null);

        String text = flatten(sheet(generate(false), "04_플랫폼별_정산대사표.xlsx"));

        assertThat(text).contains("700,000", "입금 미확인");
        assertThat(rowStartingWith(sheet(generate(false), "01_연간_매출요약.xlsx"), "합계").get(4))
                .as("대사 미완 정산도 매출액에 들어간다").isEqualTo("700,000");
    }

    // --- 05 고정비·반복지출 ---------------------------------------------------

    @Test
    void summarisesFixedCostsByCategoryAndMonth() {
        tx("RENT", "WITHDRAWAL", "1200000", "2026-06-01");
        tx("RENT", "WITHDRAWAL", "1200000", "2026-07-01");
        tx("TELECOM", "WITHDRAWAL", "55000", "2026-06-05");

        List<List<String>> rows = sheet(generate(false), "05_고정비_반복지출.xlsx");
        List<String> rent = rowStartingWith(rows, "임차료");

        assertThat(rent.get(1)).as("건수").isEqualTo("2");
        assertThat(rent.get(2)).as("합계").isEqualTo("2,400,000");
        assertThat(flatten(rows)).contains("통신비", "55,000", "2026-06", "2026-07");
    }

    /**
     * <b>대출 상환을 넣지 않는다.</b> 매달 나가지만 원금은 경비가 아니고, 우리 분류는 원금과
     * 이자를 가르지 않는다 — 넣으면 세무대리인이 원금까지 경비로 볼 수 있다.
     */
    @Test
    void leavesLoanRepaymentOutOfFixedCosts() {
        tx("LOAN_REPAYMENT", "WITHDRAWAL", "500000", "2026-06-25");
        tx("RENT", "WITHDRAWAL", "1200000", "2026-06-01");

        String text = flatten(sheet(generate(false), "05_고정비_반복지출.xlsx"));

        assertThat(text).contains("임차료");
        assertThat(text).as("대출 상환이 고정비 시트에 들어가면 안 된다").doesNotContain("500,000");
    }

    // --- 08 증빙원본 ---------------------------------------------------------

    @Test
    void packsTheEvidenceFilesWhenAsked() {
        Long transaction = tx("SUPPLIES", "WITHDRAWAL", "300000", "2026-06-15");
        Long evidenceId = evidence("영수증.jpg");
        link(transaction, evidenceId);
        writeFile(evidenceId, "원본 바이트");

        Map<String, byte[]> entries = generate(true);
        String entry = "08_증빙원본/2026-06-15_%d_영수증.jpg".formatted(evidenceId);

        assertThat(entries).containsKey(entry);
        assertThat(new String(entries.get(entry), StandardCharsets.UTF_8)).isEqualTo("원본 바이트");
    }

    @Test
    void leavesEvidenceOutWhenNotAsked() {
        Long transaction = tx("SUPPLIES", "WITHDRAWAL", "300000", "2026-06-15");
        Long evidenceId = evidence("영수증.jpg");
        link(transaction, evidenceId);
        writeFile(evidenceId, "원본 바이트");

        assertThat(generate(false).keySet()).noneMatch(name -> name.startsWith("08_증빙원본/"));
    }

    /**
     * <b>못 읽은 파일을 조용히 넘기지 않는다.</b> 그냥 빠지면 세무대리인은 애초에 증빙이
     * 없었다고 생각한다 — 그 둘은 전혀 다른 말이다.
     */
    @Test
    void saysWhichEvidenceFilesCouldNotBeRead() {
        Long transaction = tx("SUPPLIES", "WITHDRAWAL", "300000", "2026-06-15");
        Long evidenceId = evidence("사라진영수증.jpg");
        link(transaction, evidenceId);
        // 파일을 쓰지 않는다 — DB 에는 있고 디스크에는 없는 상태다.

        Map<String, byte[]> entries = generate(true);

        assertThat(entries).containsKey("08_증빙원본/_읽지_못한_파일.txt");
        assertThat(new String(entries.get("08_증빙원본/_읽지_못한_파일.txt"), StandardCharsets.UTF_8))
                .contains("사라진영수증.jpg", "증빙이 없었다는 뜻이 아니므로");
    }

    /**
     * 이름이 같은 영수증이 흔하다 — 카메라가 {@code IMG_0001.jpg} 를 계속 만든다.
     *
     * <p><b>같은 날짜여야 이 검사가 뜻을 갖는다.</b> 날짜가 다르면 이름 앞자리가 달라져 id 가
     * 없어도 안 겹친다. 실제로 겹치는 자리는 하루에 영수증 두 장을 올린 경우다 — 그때 id 가
     * 없으면 ZIP 이 같은 이름을 두 번 쓰려다 <b>패키지 생성이 통째로 실패한다</b>.
     */
    @Test
    void keepsTwoEvidencesThatShareAFileName() {
        Long first = tx("SUPPLIES", "WITHDRAWAL", "300000", "2026-06-15");
        Long second = tx("SUPPLIES", "WITHDRAWAL", "400000", "2026-06-15");
        Long a = evidence("IMG_0001.jpg");
        Long b = evidence("IMG_0001.jpg");
        link(first, a);
        link(second, b);
        writeFile(a, "첫 장");
        writeFile(b, "둘째 장");

        Map<String, byte[]> entries = generate(true);

        assertThat(entries.keySet().stream().filter(name -> name.contains("IMG_0001.jpg")))
                .hasSize(2);
    }
}
