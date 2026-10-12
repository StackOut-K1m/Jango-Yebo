package com.jangoyebo.core.transaction;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSV 거래 업로드 (API 7.4) — <b>회귀용</b>.
 *
 * <p>이 경로는 저장을 {@code saveAll} 에서 {@link TransactionBulkWriter} 로 옮기는 동안
 * <b>테스트가 하나도 없었다.</b> 옮기고 나서 쓴 것이 아니라 옮긴 것을 지키려고 쓴 것이라,
 * 확인하는 것은 새 구현의 내부가 아니라 <b>바깥에서 보이는 계약</b>이다 — 명세 7.4 가 정한
 * {@code importedCount}·{@code duplicateCount}·{@code rejectedRows} 세 숫자와 저장된 행의 모양.
 *
 * <p><b>Docker가 필요하다.</b>
 */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TransactionImportTests {

    private static final String HEADER = "거래일시,적요,입금액,출금액,거래후잔액\n";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String unique = String.valueOf(System.nanoTime());
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '임포트') RETURNING id
                """, Long.class, "import-" + unique + "@test.local");
        token = tokenProvider.createAccessToken(userId, "import-" + unique + "@test.local");
        Long businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '임포트 사업장') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '088', 'enc', ?, '110-***-***789', '주거래', 'CONNECTED') RETURNING id
                """, Long.class, businessId, "hash-" + unique);
    }

    private org.springframework.test.web.servlet.ResultActions upload(String csv) throws Exception {
        var file = new MockMultipartFile("file", "거래.csv", MediaType.TEXT_PLAIN_VALUE,
                csv.getBytes(StandardCharsets.UTF_8));
        return mockMvc.perform(multipart("/api/v1/accounts/{id}/transactions/import", accountId)
                .file(file)
                .header("Authorization", "Bearer " + token));
    }

    private int countRows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM core.transaction WHERE account_id = ?", Integer.class, accountId);
    }

    @Test
    void 엑셀로_다시_저장한_파일도_받는다() throws Exception {
        // S15P21C105-138. 사장님이 CSV 를 엑셀로 열었다 저장하면 거래일시가 셀 표시
        // 형식으로 다시 쓰인다. 고정 패턴 하나만 받던 때는 전 행이 거부돼 0건으로 끝났다.
        upload(HEADER
                + "2026-09-01 9:30,배민 정산,250000,,1250000\n"
                + "2026-09-02 오전 9:00,월세,,800000,450000\n"
                + "2026. 09. 03 14:05:00,카드 정산,120000,,570000\n"
                + "2026/09/04,현금 입금,50000,,620000\n")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(4))
                .andExpect(jsonPath("$.data.resultRef.rejectedRows.length()").value(0));

        assertThat(countRows()).isEqualTo(4);
    }

    @Test
    void 같은_거래를_다른_표기로_올리면_중복으로_본다() throws Exception {
        // 중복 판정 키가 거래일시를 문자열이 아니라 파싱된 값으로 잡으므로,
        // 엑셀을 거친 파일을 다시 올려도 같은 거래가 두 번 쌓이지 않는다.
        upload(HEADER + "2026-09-01 09:30:00,배민 정산,250000,,1250000\n")
                .andExpect(status().isAccepted());

        upload(HEADER + "2026-09-01 9:30,배민 정산,250000,,1250000\n")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(0))
                .andExpect(jsonPath("$.data.resultRef.duplicateCount").value(1));

        assertThat(countRows()).isEqualTo(1);
    }

    @Test
    void 읽을_수_없는_거래일시만_건너뛴다() throws Exception {
        // 관용성의 원래 목적 — 깨진 한 행이 나머지를 막지 않는다.
        upload(HEADER
                + "2026-09-01 9:30,배민 정산,250000,,1250000\n"
                + "어제,알 수 없음,10000,,\n"
                + "2026-02-30 9:00,없는 날짜,10000,,\n"
                + "2026-09-02 9:00,월세,,800000,450000\n")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(2))
                .andExpect(jsonPath("$.data.resultRef.rejectedRows.length()").value(2))
                .andExpect(jsonPath("$.data.resultRef.rejectedRows[0].rowNumber").value(3));

        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    void storesEveryValidRowAndReportsTheCount() throws Exception {
        upload(HEADER
                + "2026-09-01 10:00:00,배민 정산,250000,,1250000\n"
                + "2026-09-02 09:00:00,월세,,800000,450000\n")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(2))
                .andExpect(jsonPath("$.data.resultRef.duplicateCount").value(0));

        assertThat(countRows()).isEqualTo(2);

        var row = jdbc.queryForMap("""
                SELECT source_type, flow_type, amount, balance_after, description,
                       transaction_date, deduplication_key, external_transaction_id
                  FROM core.transaction WHERE account_id = ? AND description = '배민 정산'
                """, accountId);
        assertThat(row.get("source_type")).isEqualTo("CSV");
        assertThat(row.get("flow_type")).isEqualTo("DEPOSIT");
        assertThat((java.math.BigDecimal) row.get("amount")).isEqualByComparingTo("250000");
        assertThat((java.math.BigDecimal) row.get("balance_after")).isEqualByComparingTo("1250000");
        assertThat(row.get("deduplication_key")).isNotNull();
        assertThat(row.get("external_transaction_id"))
                .as("CSV 경로는 금융망 키를 쓰지 않는다 — 두 부분 인덱스가 서로 안 걸리는 근거다")
                .isNull();
    }

    /**
     * <b>파일에 적힌 벽시계 날짜가 그대로 들어가야 한다.</b> {@code Instant} 에서 역산하면
     * 서버 시간대에 따라 하루가 밀리는데, 개발 장비가 KST 라 그 버그는 눈에 띄지 않는다.
     */
    @Test
    void keepsTheWallClockDateFromTheFile() throws Exception {
        upload(HEADER + "2026-09-01 00:30:00,새벽 정산,10000,,10000\n").andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("""
                SELECT transaction_date FROM core.transaction WHERE account_id = ?
                """, java.sql.Date.class, accountId).toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026, 9, 1));
    }

    /** 같은 파일 안의 중복. 예전에는 {@code HashSet} 이 걸렀고 이제는 {@code ON CONFLICT} 가 흡수한다. */
    @Test
    void countsDuplicatesInsideOneFile() throws Exception {
        upload(HEADER
                + "2026-09-01 10:00:00,배민 정산,250000,,\n"
                + "2026-09-01 10:00:00,배민 정산,250000,,\n"
                + "2026-09-02 09:00:00,월세,,800000,\n")
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(2))
                .andExpect(jsonPath("$.data.resultRef.duplicateCount").value(1));

        assertThat(countRows()).isEqualTo(2);
    }

    /** 두 번 올려도 쌓이지 않는다 — 인수 조건 "중복 없는 귀속". */
    @Test
    void countsDuplicatesAgainstRowsAlreadyStored() throws Exception {
        String csv = HEADER + "2026-09-01 10:00:00,배민 정산,250000,,\n";
        upload(csv).andExpect(jsonPath("$.data.resultRef.importedCount").value(1));

        upload(csv)
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(0))
                .andExpect(jsonPath("$.data.resultRef.duplicateCount").value(1));

        assertThat(countRows()).isEqualTo(1);
    }

    /**
     * 같은 시각·같은 금액의 입금과 출금은 <b>서로 다른 거래</b>다. 중복 키에 방향이 없으면
     * 하나로 뭉개져 한 건이 유실된다(API 7.4 v1.4 정정 — 계좌 간 이체에서 실제로 발생한다).
     */
    @Test
    void keepsDepositAndWithdrawalThatShareTimeAndAmount() throws Exception {
        upload(HEADER
                + "2026-09-01 10:00:00,계좌 이체,500000,,\n"
                + "2026-09-01 10:00:00,계좌 이체,,500000,\n")
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(2));

        assertThat(countRows()).isEqualTo(2);
    }

    /** 행 하나가 틀렸다고 파일을 통째로 버리지 않는다. 사유와 행 번호를 돌려준다. */
    @Test
    void rejectsBadRowsWithoutFailingTheWholeFile() throws Exception {
        upload(HEADER
                + "2026-09-01 10:00:00,정상,250000,,\n"
                + "2026-09-02 09:00:00,둘 다 양수,1000,2000,\n")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(1))
                .andExpect(jsonPath("$.data.resultRef.rejectedRows[0].rowNumber").value(3));

        assertThat(countRows()).isEqualTo(1);
    }

    /** 파일 규격 자체가 틀리면 Job 을 만들지 않고 즉시 알려 준다 — 사용자가 바로 고칠 수 있게. */
    @Test
    void rejectsAFileWhoseHeaderIsNotTheSpec() throws Exception {
        upload("아무거나,이상한,헤더\n1,2,3\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IMPORT_INVALID_FORMAT"));

        assertThat(countRows()).isZero();
    }

    /** 은행 내보내기 형식이 제각각이라 천 단위 구분자와 통화 기호를 허용한다(API 7.4). */
    @Test
    void parsesAmountsWithSeparatorsAndCurrencySigns() throws Exception {
        upload(HEADER + "2026-09-01 10:00:00,정산,\"1,250,000\",,₩1250000\n")
                .andExpect(jsonPath("$.data.resultRef.importedCount").value(1));

        assertThat((java.math.BigDecimal) jdbc.queryForMap("""
                SELECT amount FROM core.transaction WHERE account_id = ?
                """, accountId).get("amount")).isEqualByComparingTo("1250000");
    }
}
