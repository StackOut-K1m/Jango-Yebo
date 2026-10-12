package com.jangoyebo.core.fin;

import org.junit.jupiter.api.BeforeEach;
import com.jangoyebo.core.fin.AccountAuthClient.AuthCheck;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 1원 인증 호출 검증 (실측 계약 기반, 2026-09-08).
 *
 * <p>가장 중요한 갈래는 <b>"코드가 틀렸다"와 "금융망이 죽었다"를 섞지 않는 것</b>이다.
 * 전자는 사용자에게 재입력을 시키는 400 이고 후자는 502 인데, 둘 다 예외로 뭉치면
 * 사용자가 자기 오타를 서버 장애로 본다.
 */
class AccountAuthClientTests {

    private static final String OPEN = FinTestFixtures.BASE_URL + "/ssafy/api/v1/edu/accountAuth/openAccountAuth";
    private static final String CHECK = FinTestFixtures.BASE_URL + "/ssafy/api/v1/edu/accountAuth/checkAuthCode";
    private static final String ACCOUNT_NO = "0000000000000000";

    private MockRestServiceServer server;
    private AccountAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(FinTestFixtures.BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        FinOpenApiProperties properties = FinTestFixtures.properties();
        client = new AccountAuthClient(
                new FinOpenApiClient(builder.build(),
                        new FinApiHeaderFactory(properties, Clock.systemUTC()),
                        JsonMapper.builder().build()),
                properties);
    }

    /** {@code authText} 는 01·02 에서 같아야 한다. 설정 한 곳에서만 읽는지 확인한다. */
    @Test
    void sendsConfiguredAuthTextWithTheAccountNumber() {
        server.expect(requestTo(OPEN))
                .andExpect(jsonPath("$.accountNo").value(ACCOUNT_NO))
                .andExpect(jsonPath("$.authText").value(FinTestFixtures.AUTH_TEXT))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" },
                          "REC": { "transactionUniqueNo": "191471", "accountNo": "0000000000000000" } }
                        """, MediaType.APPLICATION_JSON));

        client.sendOneWon("user-key", ACCOUNT_NO);

        server.verify();
    }

    @Test
    void reportsMatchWhenStatusIsSuccess() {
        server.expect(requestTo(CHECK))
                .andExpect(jsonPath("$.authCode").value("4653"))
                .andExpect(jsonPath("$.authText").value(FinTestFixtures.AUTH_TEXT))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" },
                          "REC": { "status": "SUCCESS", "transactionUniqueNo": "191471" } }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.check("user-key", ACCOUNT_NO, "4653")).isEqualTo(AuthCheck.MATCHED);
    }

    /**
     * 틀린 코드는 {@code A1090} 이 <b>최상위</b> {@code responseCode} 로 온다(실측).
     * 예외가 아니라 {@code false} 여야 한다 — 사용자 오타는 장애가 아니다.
     */
    @Test
    void reportsMismatchWithoutThrowing() {
        server.expect(requestTo(CHECK))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "A1090", "responseMessage": "인증코드가 유효하지 않습니다." }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThat(client.check("user-key", ACCOUNT_NO, "0000")).isEqualTo(AuthCheck.MISMATCHED);
    }

    /** {@code A1090} 이 아닌 실패는 삼키지 않는다. 502 로 나가야 할 것들이다. */
    @Test
    void rethrowsFailuresThatAreNotAWrongCode() {
        server.expect(requestTo(CHECK))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "H1003", "responseMessage": "전송일시가 유효하지 않습니다." }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.check("user-key", ACCOUNT_NO, "4653"))
                .isInstanceOf(FinApiException.class)
                .extracting(e -> ((FinApiException) e).finCode()).isEqualTo("H1003");
    }

    /**
     * <b>금융망 쪽 만료는 장애가 아니다</b> — S15P21C105-111 의 ②다.
     * 예외로 올리면 {@code 502 FIN_API_FAILED} 가 되어, 사용자는 "재발송하세요"를 못 듣는다.
     */
    @Test
    void reportsExpiryWhenFinNetworkSaysTimeIsUp() {
        server.expect(requestTo(CHECK))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "A1087", "responseMessage": "인증시간이 만료되었습니다." }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThat(client.check("user-key", ACCOUNT_NO, "4653")).isEqualTo(AuthCheck.EXPIRED);
    }

    /**
     * 공식 워크북({@code ACCOUNT_AUTH_02})에는 인증 결과 코드가 <b>넷</b>이다. 실측으로 만난 둘만
     * 다루면 나머지 둘이 {@code 502 FIN_API_FAILED} 로 새어 나간다 — 같은 종류의 어긋남을
     * 세 번 겪고 나서 워크북으로 대조해 찾았다.
     */
    @Test
    void treatsTheOtherTwoWorkbookCodesTheSameWay() {
        // 기대는 호출 전에 다 걸어야 한다 — MockRestServiceServer 가 중간 추가를 막는다.
        server.expect(requestTo(CHECK))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "A1086", "responseMessage": "인증코드 발급 기록이 없습니다." }
                        """).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(CHECK))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""
                        { "responseCode": "A1088", "responseMessage": "인증코드가 일치하지 않습니다." }
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThat(client.check("user-key", ACCOUNT_NO, "4653"))
                .as("발급 기록 없음도 만료와 같다 — 사용자가 할 일은 재발송이다")
                .isEqualTo(AuthCheck.EXPIRED);
        assertThat(client.check("user-key", ACCOUNT_NO, "0000"))
                .as("A1090 과 별개 코드지만 사용자 입장에서는 같은 일이다")
                .isEqualTo(AuthCheck.MISMATCHED);
        server.verify();
    }

    /** {@code status} 가 {@code SUCCESS} 가 아닌 값으로 오면 성공으로 읽지 않는다. */
    @Test
    void doesNotTreatUnknownStatusAsMatch() {
        server.expect(requestTo(CHECK))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" }, "REC": { "status": "FAIL" } }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.check("user-key", ACCOUNT_NO, "4653")).isEqualTo(AuthCheck.MISMATCHED);
    }
}
