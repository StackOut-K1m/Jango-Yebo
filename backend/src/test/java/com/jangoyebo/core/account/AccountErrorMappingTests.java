package com.jangoyebo.core.account;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.fin.FinApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 금융망 실패를 우리 오류로 옮기는 규칙.
 *
 * <p><b>같은 종류의 어긋남이 두 번 나왔다</b> — 금융망 쪽 인증 만료({@code A1087})와 계좌번호
 * 오류({@code A1003})가 모두 {@code 502 FIN_API_FAILED} 로 나가, 사용자가 <b>자기가 고칠 수
 * 있는 것을 서버 장애로</b> 오해했다(S15P21C105-111). 그래서 이 매핑을 사람의 기억이 아니라
 * 테스트에 둔다 — 금융망 코드를 새로 다룰 때 여기 한 줄이 함께 늘어야 한다.
 *
 * <p>Spring 도 Docker 도 쓰지 않는다 — 순수 함수다.
 */
class AccountErrorMappingTests {

    private static final String SEND_FAILED = "1원 인증 송금에 실패했습니다.";

    /**
     * 사용자가 계좌번호를 잘못 넣은 것이라 {@code 502} 가 아니다.
     * 금융망 코드를 {@code detail} 에 싣지 않는 것도 의도다 — 프론트가 우리 코드만 보면 된다.
     */
    @Test
    void mapsInvalidAccountNumberToClientError() {
        BusinessException mapped = AccountService.finFailure(
                new FinApiException("A1003", HttpStatus.BAD_REQUEST, "계좌번호가 유효하지 않습니다."),
                SEND_FAILED);

        assertThat(mapped.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NO_INVALID);
        assertThat(mapped.errorCode().status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * 없는 계좌·형식 오류·남의 계좌가 모두 {@code A1003} 이고 <b>구분해서는 안 된다.</b>
     * "있지만 당신 것이 아니다"를 알려주면 계좌 존재 여부가 새어 나간다
     * ({@code BUSINESS_ACCESS_DENIED} 가 일괄 403 인 것과 같은 이유).
     */
    @Test
    void doesNotRevealWhyTheAccountNumberFailed() {
        BusinessException mapped = AccountService.finFailure(
                new FinApiException("A1003", HttpStatus.BAD_REQUEST, "계좌번호가 유효하지 않습니다."),
                SEND_FAILED);

        assertThat(mapped.getMessage()).isEqualTo(ErrorCode.ACCOUNT_NO_INVALID.defaultMessage());
        assertThat(mapped.detail())
                .as("금융망 코드를 흘리면 A1003 의 의미를 아는 사람에게는 구분이 노출된다")
                .isNull();
    }

    /** 나머지는 502 다. 원본 코드를 detail 에 실어 프론트 이슈에 코드가 함께 붙게 한다. */
    @Test
    void mapsUnknownFinCodeToGatewayFailureWithTheCode() {
        BusinessException mapped = AccountService.finFailure(
                new FinApiException("A1086", HttpStatus.BAD_REQUEST, "무언가"),
                "1원 인증 검증에 실패했습니다.");

        assertThat(mapped.errorCode()).isEqualTo(ErrorCode.FIN_API_FAILED);
        assertThat(mapped.detail()).isEqualTo(Map.of("finCode", "A1086"));
        assertThat(mapped.getMessage()).isEqualTo("1원 인증 검증에 실패했습니다.");
    }

    /** 전송 오류는 금융망이 답을 준 적이 없어 코드가 없다. {@code detail} 을 비운다. */
    @Test
    void mapsTransportFailureWithoutAFinCode() {
        BusinessException mapped = AccountService.finFailure(
                new FinApiException("연결 실패", new IOException()), SEND_FAILED);

        assertThat(mapped.errorCode()).isEqualTo(ErrorCode.FIN_API_FAILED);
        assertThat(mapped.detail()).isNull();
    }
}
