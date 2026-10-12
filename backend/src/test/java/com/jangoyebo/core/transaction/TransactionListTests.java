package com.jangoyebo.core.transaction;

import com.jangoyebo.NeedsDocker;
import com.jangoyebo.common.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 거래 목록 조회 (API 8.2) — 재현용. */
@NeedsDocker
@SpringBootTest
@AutoConfigureMockMvc
class TransactionListTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long accountId;
    private String token;

    @BeforeEach
    void setUp() {
        String email = "tx-" + System.nanoTime() + "@test.local";
        Long userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '거래') RETURNING id
                """, Long.class, email);
        token = tokenProvider.createAccessToken(userId, email);
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '거래 사업장') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
        insertTransaction("baemin 정산", null, 10000);
        insertTransaction("월세", "건물주", 20000);
    }

    private void insertTransaction(String description, String counterpartyName, int amount) {
        insertTransaction(description, counterpartyName, amount, accountId);
    }

    private void insertTransaction(String description, String counterpartyName, int amount, Long account) {
        jdbc.update("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, counterparty_name,
                     classification_status, needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), DATE '2026-09-10', 'DEPOSIT', ?, ?, ?,
                        'CONFIRMED', FALSE)
                """, businessId, account, "ext-" + System.nanoTime(),
                amount, description, counterpartyName);
    }

    private ResultActions list(String extraQuery) throws Exception {
        return listAll("&accountId=" + accountId + extraQuery);
    }

    /** 계좌를 지목하지 않는 목록. 계좌 상태로 걸러지는지는 여기서만 볼 수 있다. */
    private ResultActions listAll(String extraQuery) throws Exception {
        return mockMvc.perform(get("/api/v1/businesses/" + businessId + "/transactions"
                        + "?from=2026-09-01&to=2026-09-30"
                        + "&direction=DEPOSIT&classificationStatus=CONFIRMED&needsConfirmation=false"
                        + extraQuery)
                .header("Authorization", "Bearer " + token));
    }

    /**
     * 분류 주체는 <b>{@code AI}</b> 로 나간다 (8.2).
     *
     * <p>DB 는 같은 값을 {@code LLM} 으로 저장한다 — 이력 테이블의 {@code ck_tch_classifier} 가
     * 이미 적용돼 동결이라 DB 쪽을 바꿀 수 없었다. 변환이 한 곳
     * ({@code ClassifierType.apiValue})에만 있어야 같은 값이 두 이름으로 나가지 않는다.
     */
    @Test
    void reportsLlmClassificationAsAi() throws Exception {
        jdbc.update("""
                UPDATE core.transaction
                   SET classification_source = 'LLM', classification_reason = '카드사 입금'
                 WHERE business_id = ? AND description = 'baemin 정산'
                """, businessId);

        list("&q=정산")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].classification.source").value("AI"))
                .andExpect(jsonPath("$.data.items[0].classification.reason").value("카드사 입금"));
    }

    /** 기준선 — q 가 있으면 통과한다. 적요로 걸러낸다. */
    @Test
    void filtersByDescription() throws Exception {
        list("&q=정산")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].description").value("baemin 정산"));
    }

    /** 거래처명도 같은 키워드로 걸린다 — OR 두 번째 가지. */
    @Test
    void filtersByCounterpartyName() throws Exception {
        list("&q=건물주")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].description").value("월세"));
    }

    /** CAST 를 끼워도 LOWER 는 그대로 걸려야 한다. */
    @Test
    void matchesCaseInsensitively() throws Exception {
        list("&q=BAEMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    /** 안 맞으면 빈 목록이다 — 필터가 실제로 좁히는지 확인한다. */
    @Test
    void returnsEmptyWhenNothingMatches() throws Exception {
        list("&q=zzz없는키워드")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /** 버그의 본체 — 목록 첫 진입(q 없음)이 500 이었다. */
    @Test
    void returnsAllWhenKeywordOmitted() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    /** 프론트 우회로도 막혀 있었다 — 컨트롤러가 blank 를 null 로 바꿔 같은 경로를 탄다. */
    @Test
    void returnsAllWhenKeywordBlank() throws Exception {
        list("&q=")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    /** 기본값이 아닌 정렬도 엔티티 속성이면 그대로 통한다 — 아래 400 이 이걸 막으면 안 된다. */
    @Test
    void sortsByAnotherEntityAttribute() throws Exception {
        list("&sort=amount,desc")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].amount").value(20000));
    }

    /** 곁가지 — 없는 정렬 필드는 400 이어야 한다(구현현황 3.8). */
    @Test
    void rejectsUnknownSortProperty() throws Exception {
        list("&sort=bogus,asc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // --- 필터 확장 (API 8.2) -------------------------------------------------

    @Test
    void filtersByCategory() throws Exception {
        setCategory("baemin 정산", "PLATFORM_SETTLEMENT");

        list("&category=PLATFORM_SETTLEMENT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].description").value("baemin 정산"));
    }

    /**
     * 모르는 코드를 조용히 무시하면 <b>필터가 안 걸린 전체 목록</b>이 나가고, 프론트는 그것을
     * "이 카테고리 거래가 이만큼"으로 읽는다. 오타 한 글자가 잘못된 집계로 보이는 편이
     * 빈 목록보다 나쁘다.
     */
    @Test
    void rejectsUnknownCategory() throws Exception {
        list("&category=NOT_A_CATEGORY")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void filtersByAmountRange() throws Exception {
        list("&minAmount=15000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].amount").value(20000));

        list("&maxAmount=15000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].amount").value(10000));

        // 경계는 포함이다(>=, <=). 한쪽만 열어도 같은 건이 걸려야 한다.
        list("&minAmount=10000&maxAmount=10000")
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    /** 값을 바꾸는 경로는 아직 없다(P0-12 미착수). 기본값이 응답에 실려 나가는지만 본다. */
    @Test
    void includesTaxReviewWithDefaultStatus() throws Exception {
        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taxReview.status").value("UNREVIEWED"))
                .andExpect(jsonPath("$.data.items[0].taxReview.note").doesNotExist());
    }

    @Test
    void filtersByTaxReviewStatus() throws Exception {
        setTaxReviewStatus("월세", "EXPERT_REVIEW");

        list("&taxReviewStatus=EXPERT_REVIEW")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].description").value("월세"));
    }

    /** 검토 화면(P1-23)이 "확인이 필요한 것만" 묶음을 한 번에 거른다 — 쉼표 복수 지정. */
    @Test
    void filtersByMultipleTaxReviewStatuses() throws Exception {
        setTaxReviewStatus("월세", "EXPERT_REVIEW");

        list("&taxReviewStatus=EXPERT_REVIEW,UNREVIEWED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));

        // 공백이 섞여도 같아야 한다 — 프론트가 join(', ') 로 만들 수 있다.
        list("&taxReviewStatus=EXPERT_REVIEW , UNREVIEWED")
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void rejectsUnknownTaxReviewStatus() throws Exception {
        list("&taxReviewStatus=UNREVIEWED,NOPE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    /**
     * 빈 값은 필터를 걸지 않는 것으로 다룬다.
     *
     * <p>빈 컬렉션을 그대로 넘기면 {@code IN ()} 이 되어 <b>아무것도 안 나온다.</b>
     * {@code q=} 가 같은 이유로 500 이었던 전례가 있어 여기서도 못박는다.
     */
    @Test
    void returnsAllWhenTaxReviewStatusBlank() throws Exception {
        list("&taxReviewStatus=")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    // --- 해제된 계좌 -----------------------------------------------------------

    /**
     * <b>기본은 제외다.</b> 연동을 끊은 계좌의 거래가 목록에 계속 섞여 있으면 사용자는
     * 해제로 무엇을 했는지 알 수 없고, 목록을 더해 만드는 숫자가 전부 그만큼 부풀어 있다.
     */
    @Test
    void hidesTransactionsOfDisconnectedAccounts() throws Exception {
        insertTransaction("옛 계좌 입금", null, 50000, disconnectedAccount());

        listAll("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    /** 보존한 이유가 이것이다 — 명시로 물으면 그대로 나온다. */
    @Test
    void showsThemWhenAskedExplicitly() throws Exception {
        insertTransaction("옛 계좌 입금", null, 50000, disconnectedAccount());

        listAll("&includeDisconnected=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3));
    }

    /**
     * <b>계좌를 지목해도 문은 하나다.</b> {@code accountId} 만으로 열리게 두면 같은 것을 얻는
     * 길이 둘이 되고, 어느 쪽이 합계에 들어가는지가 호출자마다 갈린다. 해제된 계좌의 거래를
     * 보려면 어느 경로든 {@code includeDisconnected} 를 함께 보낸다.
     */
    @Test
    void needsTheFlagEvenWhenTheAccountIsNamed() throws Exception {
        Long disconnected = disconnectedAccount();
        insertTransaction("옛 계좌 입금", null, 50000, disconnected);

        listAll("&accountId=" + disconnected)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        listAll("&accountId=" + disconnected + "&includeDisconnected=true")
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].description").value("옛 계좌 입금"));
    }

    /** 인증 대기·만료 계좌는 빼지 않는다 — CSV 로 직접 올린 거래가 있을 수 있다. */
    @Test
    void keepsTransactionsOfAccountsAwaitingVerification() throws Exception {
        insertTransaction("직접 올린 거래", null, 70000, accountWithStatus("VERIFICATION_EXPIRED"));

        listAll("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3));
    }

    private Long disconnectedAccount() {
        return accountWithStatus("DISCONNECTED");
    }

    private Long accountWithStatus(String status) {
        return jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name, status)
                VALUES (?, '004', 'enc', ?, '***9999', '옛 계좌', ?) RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime(), status);
    }

    private void setCategory(String description, String categoryCode) {
        jdbc.update("""
                UPDATE core.transaction SET category_id =
                       (SELECT id FROM core.transaction_category WHERE code = ?)
                 WHERE business_id = ? AND description = ?
                """, categoryCode, businessId, description);
    }

    private void setTaxReviewStatus(String description, String status) {
        jdbc.update("""
                UPDATE core.transaction SET tax_review_status = ?
                 WHERE business_id = ? AND description = ?
                """, status, businessId, description);
    }
}
