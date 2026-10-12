package com.jangoyebo.core.fin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 금융망 단일 POST 클라이언트 검증.
 *
 * <p>Spring 컨텍스트도 Docker 도 쓰지 않는다 — {@code MockRestServiceServer} 로 금융망을 대역
 * 처리한다. 확인하는 것은 <b>계약</b>이다: 경로 조립, 두 요청 형태의 분리, 그리고
 * {@code responseCode} 가 성공이 아닐 때 무엇이 되는가.
 */
class FinOpenApiClientTests {

    private static final FinOpenApiProperties PROPERTIES = FinTestFixtures.properties();

    private static final String BASE = FinTestFixtures.BASE_URL;

    private MockRestServiceServer server;
    private FinOpenApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FinOpenApiClient(
                builder.build(),
                new FinApiHeaderFactory(PROPERTIES, Clock.fixed(
                        Instant.parse("2026-09-08T02:30:00Z"), ZoneOffset.UTC)),
                JsonMapper.builder().build());
    }

    /** 경로의 마지막 구간이 곧 {@code apiName} 이고, 같은 값이 본문 Header 에도 들어간다. */
    @Test
    void putsApiNameInBothPathAndHeader() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/edu/demandDeposit/inquireTransactionHistoryList"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.Header.apiName").value("inquireTransactionHistoryList"))
                .andExpect(jsonPath("$.Header.apiServiceCode").value("inquireTransactionHistoryList"))
                .andExpect(jsonPath("$.Header.apiKey").value("test-api-key"))
                .andExpect(jsonPath("$.Header.userKey").value("user-key"))
                .andExpect(jsonPath("$.Header.institutionTransactionUniqueNo")
                        .value(matchesPattern("\\d{20}")))
                // payload 는 Header 와 같은 층에 평평하게 붙는다. 감싸지 않는다.
                .andExpect(jsonPath("$.accountNo").value("0011234567890123"))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000", "responseMessage": "정상처리 되었습니다." },
                          "REC": { "totalCount": "1" } }
                        """, MediaType.APPLICATION_JSON));

        Map<String, Object> body = client.callEdu("demandDeposit", "inquireTransactionHistoryList",
                "user-key", Map.of("accountNo", "0011234567890123"));

        assertThat(body).containsKey("REC");
        server.verify();
    }

    @Test
    void convertsRecIntoGivenType() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/edu/demandDeposit/inquireDemandDepositAccountBalance"))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" },
                          "REC": { "accountNo": "0011234567890123", "accountBalance": "1250000" } }
                        """, MediaType.APPLICATION_JSON));

        Balance balance = client.callEdu("demandDeposit", "inquireDemandDepositAccountBalance",
                "user-key", Map.of(), Balance.class);

        assertThat(balance).isNotNull();
        assertThat(balance.accountNo()).isEqualTo("0011234567890123");
        // 금액은 문자열로 온다. 숫자로 보내면 Q1001 이고, 받을 때도 문자열이다.
        assertThat(balance.accountBalance()).isEqualTo("1250000");
    }

    /**
     * 회원 계열은 {@code Header} 객체가 없고 {@code apiKey} 가 본문 최상위로 간다
     * (공통규약 23장). 두 형태를 하나로 뭉뚱그리면 안 되는 지점이다.
     */
    @Test
    void memberCallSendsApiKeyAtTopLevelWithoutHeaderObject() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/member/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.apiKey").value("test-api-key"))
                .andExpect(jsonPath("$.userId").value("owner@example.test"))
                .andExpect(jsonPath("$.Header").doesNotExist())
                .andRespond(withStatus(HttpStatus.CREATED).body("""
                        { "userId": "owner@example.test", "userKey": "issued-user-key" }
                        """).contentType(MediaType.APPLICATION_JSON));

        Member member = client.callMember("", Map.of("userId", "owner@example.test"), Member.class);

        assertThat(member.userKey()).isEqualTo("issued-user-key");
        server.verify();
    }

    /** {@code H0000} 이 아니면 HTTP 200 이어도 실패다. */
    @Test
    void treatsNonSuccessResponseCodeAsFailureEvenOnHttp200() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/edu/accountAuth/openAccountAuth"))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "A1003", "responseMessage": "계좌번호가 유효하지 않습니다." } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.callEdu("accountAuth", "openAccountAuth", "user-key", Map.of()))
                .isInstanceOf(FinApiException.class)
                .hasMessageContaining("A1003")
                .hasMessageContaining("계좌번호가 유효하지 않습니다.")
                .extracting(e -> ((FinApiException) e).finCode()).isEqualTo("A1003");
    }

    /** 오류 응답은 {@code responseCode} 가 최상위로 오기도 한다. 두 자리를 모두 본다. */
    @Test
    void readsResponseCodeFromTopLevelToo() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/edu/demandDeposit/inquireTransactionHistoryList"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "H1003", "responseMessage": "전송일시가 유효하지 않습니다." }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.callEdu("demandDeposit", "inquireTransactionHistoryList",
                "user-key", Map.of()))
                .isInstanceOf(FinApiException.class)
                .extracting(e -> ((FinApiException) e).finCode()).isEqualTo("H1003");
    }

    /**
     * {@code H1007}(채번 중복)만 재시도가 의미 있다 — 그것도 <b>새 번호로</b> 다시 부를 때다.
     * {@code H1003}(시계 오차)은 같은 요청을 다시 보내도 시계가 그대로라 똑같이 실패한다.
     */
    @Test
    void onlyDuplicateTransactionNumberIsWorthRetrying() {
        assertThat(new FinApiException("H1007", HttpStatus.BAD_REQUEST, "중복").isRetryable()).isTrue();
        assertThat(new FinApiException("H1003", HttpStatus.BAD_REQUEST, "시계").isRetryable()).isFalse();
        assertThat(new FinApiException("A1003", HttpStatus.BAD_REQUEST, "계좌").isRetryable()).isFalse();
        // 전송 오류는 금융망이 답을 준 적이 없다 — 일시적으로 본다.
        assertThat(new FinApiException("연결 실패", new IOException()).isRetryable()).isTrue();
    }

    /**
     * 없는 경로는 404 에 응답 형태까지 다르다 — {@code responseCode} 가 아니라
     * {@code code}/{@code payload} 다(실측). 코드를 못 찾았다고 뭉개면 원인을 못 찾는다.
     */
    @Test
    void pointsAtThePathWhenEndpointDoesNotExist() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/edu/demandDeposit/inquireNothing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body("""
                        { "code": "NOT_FOUND", "payload": null }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.callEdu("demandDeposit", "inquireNothing", "user-key", Map.of()))
                .isInstanceOf(FinApiException.class)
                .hasMessageContaining("없는 경로")
                .hasMessageContaining("apiName");
    }

    @Test
    void wrapsTransportFailureAsRetryable() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/edu/bank/inquireBankCodes"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThatThrownBy(() -> client.callEdu("bank", "inquireBankCodes", null, Map.of()))
                .isInstanceOf(FinApiException.class)
                .matches(e -> ((FinApiException) e).isRetryable())
                .matches(e -> ((FinApiException) e).finCode() == null);
    }

    /**
     * {@code inquireBankCodes} 의 REC 는 <b>배열</b>이다(2026-09-08 실측).
     * {@code Class<T>} 만으로는 표현이 안 돼 별도 경로를 둔 이유다.
     */
    @Test
    void convertsArrayRecIntoList() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/edu/bank/inquireBankCodes"))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" },
                          "REC": [ { "bankCode": "088", "bankName": "신한은행" },
                                   { "bankCode": "090", "bankName": "카카오뱅크" } ] }
                        """, MediaType.APPLICATION_JSON));

        List<BankCode> banks = client.callEduForList("bank", "inquireBankCodes", null,
                Map.of(), BankCode.class);

        assertThat(banks).containsExactly(
                new BankCode("088", "신한은행"), new BankCode("090", "카카오뱅크"));
    }

    /** 조용히 빈 목록을 주면 호출부가 "금융망에 데이터가 없다"로 오해한다. */
    @Test
    void refusesToTreatObjectRecAsList() {
        server.expect(requestTo(BASE + "/ssafy/api/v1/edu/bank/inquireBankCodes"))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" }, "REC": { "bankCode": "088" } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.callEduForList("bank", "inquireBankCodes", null,
                Map.of(), BankCode.class))
                .isInstanceOf(FinApiException.class)
                .hasMessageContaining("배열이 아니다");
    }

    private record Balance(String accountNo, String accountBalance) {
    }

    private record Member(String userId, String userKey) {
    }
}
