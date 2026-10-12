package com.jangoyebo.core.fin;

import com.jangoyebo.core.fin.DemandDepositClient.TransactionRec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 수시입출금 조회 검증 (실측 계약 기반, 2026-09-08).
 *
 * <p>확인하려는 것은 두 가지다 — {@code DD_12} 의 {@code REC} 가 <b>배열이 아니라 객체</b>
 * ({@code {totalCount, list[]}}) 라는 것, 그리고 <b>모든 숫자가 문자열로 와도</b> 깨지지 않는 것.
 */
class DemandDepositClientTests {

    private static final String HISTORY =
            FinTestFixtures.BASE_URL + "/ssafy/api/v1/edu/demandDeposit/inquireTransactionHistoryList";
    private static final String BALANCE =
            FinTestFixtures.BASE_URL + "/ssafy/api/v1/edu/demandDeposit/inquireDemandDepositAccountBalance";
    private static final String WITHDRAW =
            FinTestFixtures.BASE_URL + "/ssafy/api/v1/edu/demandDeposit/updateDemandDepositAccountWithdrawal";
    private static final String DEPOSIT =
            FinTestFixtures.BASE_URL + "/ssafy/api/v1/edu/demandDeposit/updateDemandDepositAccountDeposit";
    private static final String ACCOUNT_NO = "0000000000000000";

    /** 결정적 채번의 자리. 값 자체는 뜻이 없고 <b>그대로 나가는지</b>가 관심사다. */
    private static final String FIXED_UNIQUE_NO = "20260914000000000001";

    private MockRestServiceServer server;
    private DemandDepositClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(FinTestFixtures.BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DemandDepositClient(new FinOpenApiClient(builder.build(),
                new FinApiHeaderFactory(FinTestFixtures.properties(), Clock.systemUTC()),
                JsonMapper.builder().build()));
    }

    /** 오름차순으로 부르는 것이 계약이다 — 삽입 순서가 일정해야 동시 배치가 교착하지 않는다. */
    @Test
    void asksForBothDirectionsInAscendingOrder() {
        server.expect(requestTo(HISTORY))
                .andExpect(jsonPath("$.accountNo").value(ACCOUNT_NO))
                .andExpect(jsonPath("$.startDate").value("20260801"))
                .andExpect(jsonPath("$.endDate").value("20260831"))
                .andExpect(jsonPath("$.transactionType").value("A"))
                .andExpect(jsonPath("$.orderByType").value("ASC"))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" },
                          "REC": { "totalCount": "0", "list": [] } }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.inquireTransactionHistory("user-key", ACCOUNT_NO, "20260801", "20260831"))
                .isEmpty();
        server.verify();
    }

    /** {@code REC} 가 객체다. 배열로 읽으려 하면 여기서 깨진다. */
    @Test
    void unwrapsTheListNestedInsideTheRecObject() {
        server.expect(requestTo(HISTORY)).andRespond(withSuccess("""
                { "Header": { "responseCode": "H0000" },
                  "REC": { "totalCount": "2", "list": [
                    { "transactionUniqueNo": "191471", "transactionDate": "20260908",
                      "transactionTime": "105739", "transactionType": "1",
                      "transactionTypeName": "입금", "transactionAccountNo": "",
                      "transactionBalance": "1", "transactionAfterBalance": "1",
                      "transactionSummary": "잔고예보 4653", "transactionMemo": "" },
                    { "transactionUniqueNo": "191472", "transactionDate": "20260908",
                      "transactionTime": "111223", "transactionType": "2",
                      "transactionAccountNo": "0011234567890123",
                      "transactionBalance": "250000", "transactionAfterBalance": "0",
                      "transactionSummary": "월세" } ] } }
                """, MediaType.APPLICATION_JSON));

        List<TransactionRec> rows = client.inquireTransactionHistory(
                "user-key", ACCOUNT_NO, "20260901", "20260930");

        assertThat(rows).hasSize(2);
        // 전 필드가 문자열이다. 숫자 타입으로 받으면 금융망이 형식을 바꿀 때 통째로 깨진다.
        assertThat(rows.getFirst().transactionUniqueNo()).isEqualTo("191471");
        assertThat(rows.getFirst().transactionBalance()).isEqualTo("1");
        assertThat(rows.getFirst().transactionSummary()).isEqualTo("잔고예보 4653");
        assertThat(rows.getFirst().transactionAccountNo()).isEmpty();
        assertThat(rows.get(1).transactionType()).isEqualTo("2");
        // transactionTypeName·transactionMemo 는 우리가 안 쓰는 필드다. 있어도 없어도 통과해야 한다.
        assertThat(rows.get(1).transactionAccountNo()).isEqualTo("0011234567890123");
    }

    /** 거래가 없는 달이 흔하다. {@code list} 가 없거나 {@code REC} 자체가 없어도 빈 목록이다. */
    @Test
    void returnsEmptyWhenTheWindowHasNoTransactions() {
        server.expect(requestTo(HISTORY)).andRespond(withSuccess("""
                { "Header": { "responseCode": "H0000" }, "REC": { "totalCount": "0" } }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.inquireTransactionHistory("user-key", ACCOUNT_NO, "20260101", "20260131"))
                .isEmpty();
    }

    @Test
    void readsBalanceAsDecimalFromAString() {
        server.expect(requestTo(BALANCE))
                .andExpect(jsonPath("$.accountNo").value(ACCOUNT_NO))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" },
                          "REC": { "accountNo": "0000000000000000", "accountBalance": "1250000" } }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.inquireBalance("user-key", ACCOUNT_NO))
                .hasValueSatisfying(balance -> assertThat(balance).isEqualByComparingTo("1250000"));
    }

    @Test
    void returnsEmptyBalanceWhenTheFieldIsMissing() {
        server.expect(requestTo(BALANCE)).andRespond(withSuccess("""
                { "Header": { "responseCode": "H0000" }, "REC": { "accountNo": "0000000000000000" } }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.inquireBalance("user-key", ACCOUNT_NO)).isEmpty();
    }

    // --- 쓰기 (DD_08 · DD_09) ------------------------------------------------

    /**
     * <b>금액은 문자열이고 채번은 호출부가 정한다</b> (API 9.6).
     *
     * <p>숫자로 보내면 {@code Q1001} 이고, 채번이 매번 달라지면 두 서버가 같은 회차를 두 번
     * 보내는 것을 금융망이 막아 주지 못한다 — 이 요청 본문이 자동 반영의 안전성 그 자체다.
     */
    @Test
    void 출금은_금액을_문자열로_주어진_채번_그대로_보낸다() {
        server.expect(requestTo(WITHDRAW))
                .andExpect(jsonPath("$.accountNo").value(ACCOUNT_NO))
                .andExpect(jsonPath("$.transactionBalance").value("900000"))
                .andExpect(jsonPath("$.transactionSummary").value("김영자"))
                .andExpect(jsonPath("$.Header.institutionTransactionUniqueNo").value(FIXED_UNIQUE_NO))
                .andExpect(jsonPath("$.Header.apiName").value("updateDemandDepositAccountWithdrawal"))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" },
                          "REC": { "transactionUniqueNo": "191471", "transactionDate": "20260914" } }
                        """, MediaType.APPLICATION_JSON));

        var rec = client.withdraw("user-key", ACCOUNT_NO, "900000", "김영자", FIXED_UNIQUE_NO);

        assertThat(rec.transactionUniqueNo()).isEqualTo("191471");
        assertThat(rec.transactionDate()).isEqualTo("20260914");
        server.verify();
    }

    @Test
    void 입금은_입금_apiName_으로_간다() {
        server.expect(requestTo(DEPOSIT))
                .andExpect(jsonPath("$.Header.apiName").value("updateDemandDepositAccountDeposit"))
                .andExpect(jsonPath("$.transactionSummary").value("신한카드"))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" },
                          "REC": { "transactionUniqueNo": "191472", "transactionDate": "20260914" } }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.deposit("user-key", ACCOUNT_NO, "132000", "신한카드", FIXED_UNIQUE_NO)
                .transactionUniqueNo()).isEqualTo("191472");
    }

    /**
     * 쓰기 계열의 {@code REC} 형태는 <b>실측하지 않았다.</b> 배열로 와도 깨지지 않아야 한다 —
     * 여기서 터지면 돈은 이미 나간 뒤인데 우리는 번호를 모르게 된다.
     */
    @Test
    void REC_가_배열로_와도_읽는다() {
        server.expect(requestTo(WITHDRAW)).andRespond(withSuccess("""
                { "Header": { "responseCode": "H0000" },
                  "REC": [ { "transactionUniqueNo": "191473", "transactionDate": "20260914" } ] }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.withdraw("user-key", ACCOUNT_NO, "1000", "테스트", FIXED_UNIQUE_NO)
                .transactionUniqueNo()).isEqualTo("191473");
    }

    /** {@code REC} 가 없어도 실패가 아니다 — 호출부가 거래내역으로 번호를 메운다. */
    @Test
    void REC_가_없으면_null_을_돌려준다() {
        server.expect(requestTo(DEPOSIT)).andRespond(withSuccess("""
                { "Header": { "responseCode": "H0000" } }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.deposit("user-key", ACCOUNT_NO, "1000", "테스트", FIXED_UNIQUE_NO)).isNull();
    }

    /** 잔액 부족은 자동 반영에서 <b>정상적인 결과</b>다. 코드가 그대로 올라와야 갈라낼 수 있다. */
    @Test
    void 잔액_부족_코드를_그대로_올린다() {
        server.expect(requestTo(WITHDRAW))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "A1014", "responseMessage": "계좌 잔액이 부족합니다." }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.withdraw("user-key", ACCOUNT_NO, "900000", "김영자", FIXED_UNIQUE_NO))
                .isInstanceOf(FinApiException.class)
                .extracting(e -> ((FinApiException) e).finCode()).isEqualTo("A1014");
    }

    /** 금융망 실패는 삼키지 않는다 — 호출부가 구간 재시도 여부를 정한다. */
    @Test
    void propagatesFinFailures() {
        server.expect(requestTo(HISTORY))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "H1009", "responseMessage": "USER_KEY가 유효하지 않습니다." }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.inquireTransactionHistory("", ACCOUNT_NO, "20260901", "20260930"))
                .isInstanceOf(FinApiException.class)
                .extracting(e -> ((FinApiException) e).finCode()).isEqualTo("H1009");
    }
}
