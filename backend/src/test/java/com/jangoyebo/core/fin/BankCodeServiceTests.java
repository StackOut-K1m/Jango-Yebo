package com.jangoyebo.core.fin;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 은행 목록 캐시 검증.
 *
 * <p>{@code GET /bank-codes} 는 인증이 없는 경로다. 캐시가 새면 <b>우리 서버가 금융망으로
 * 향하는 증폭기가 된다</b> — 그래서 "몇 번 부르는가"가 여기서 가장 중요한 단정이다.
 */
class BankCodeServiceTests {

    private static final FinOpenApiProperties PROPERTIES = FinTestFixtures.properties();

    private static final String URL = FinTestFixtures.BASE_URL + "/ssafy/api/v1/edu/bank/inquireBankCodes";

    /** 실제 응답 모양이다 — REC 가 배열이다(2026-09-08 실측). */
    private static final String TWO_BANKS = """
            { "Header": { "responseCode": "H0000", "responseMessage": "정상처리 되었습니다." },
              "REC": [ { "bankCode": "088", "bankName": "신한은행" },
                       { "bankCode": "090", "bankName": "카카오뱅크" } ] }
            """;

    private MockRestServiceServer server;
    private BankCodeService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(FinTestFixtures.BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        service = new BankCodeService(new FinOpenApiClient(
                builder.build(),
                new FinApiHeaderFactory(PROPERTIES, Clock.systemUTC()),
                JsonMapper.builder().build()));
    }

    /** {@code once()} 라서 두 번째 왕복이 실제로 나가면 그 순간 실패한다. */
    @Test
    void callsFinNetworkOnlyOnceAndServesTheRestFromCache() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(TWO_BANKS, MediaType.APPLICATION_JSON));

        List<BankCode> first = service.findAll();
        List<BankCode> second = service.findAll();
        List<BankCode> third = service.findAll();

        assertThat(first).containsExactly(new BankCode("088", "신한은행"), new BankCode("090", "카카오뱅크"));
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        server.verify();
    }

    /**
     * 실패를 캐시하면 <b>금융망이 돌아와도 재기동 전까지 화면이 계속 빈다.</b>
     * 온보딩 첫 화면이라 그 대가가 크다.
     */
    @Test
    void doesNotCacheFailureSoTheNextCallRetries() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("""
                        { "responseCode": "Q1000", "responseMessage": "기타 오류" }
                        """).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andRespond(withSuccess(TWO_BANKS, MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::findAll)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FIN_API_FAILED);

        assertThat(service.findAll()).hasSize(2);
        server.verify();
    }

    /** 빈 목록으로는 계좌를 연결할 수 없다. 굳히지 말고 실패로 보낸다. */
    @Test
    void treatsEmptyListAsFailureAndDoesNotCacheIt() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H0000" }, "REC": [] }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andRespond(withSuccess(TWO_BANKS, MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::findAll)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("비어 있");

        assertThat(service.findAll()).hasSize(2);
        server.verify();
    }

    /** 502 응답 detail 에 금융망 코드를 실어 보낸다 — 프론트 이슈에 코드가 함께 붙는다. */
    @Test
    void carriesFinCodeInFailureDetail() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        { "Header": { "responseCode": "H1003", "responseMessage": "전송일시 오류" } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(service::findAll)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).detail())
                .isEqualTo(java.util.Map.of("finCode", "H1003"));
    }
}
