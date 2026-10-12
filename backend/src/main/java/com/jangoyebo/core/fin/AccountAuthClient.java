package com.jangoyebo.core.fin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 1원 인증 ({@code ACCOUNT_AUTH_01} / {@code ACCOUNT_AUTH_02}).
 *
 * <p>흐름이 특이하다 — 우리가 인증코드를 만들지도, 보관하지도 않는다.
 * {@code openAccountAuth} 를 부르면 금융망이 코드를 만들어 <b>통장 적요에 찍어</b> 1원을 넣고,
 * 사용자가 그걸 읽어 입력하면 {@code checkAuthCode} 가 대조한다.
 * 즉 <b>1원 인증 화면이 거래내역 조회에 의존</b>한다(기술스택 §4.1).
 *
 * <p>적요 형식은 {@code "{authText} {코드4자리}"} 다(실측: {@code 잔고예보 4653}).
 * {@code authText} 는 01·02 에서 <b>같아야</b> 하므로 설정 한 곳에서만 읽는다.
 */
@Component
public class AccountAuthClient {

    /** 실측 경로 (2026-09-08). */
    private static final String DOMAIN = "accountAuth";
    private static final String OPEN = "openAccountAuth";
    private static final String CHECK = "checkAuthCode";

    private static final String SUCCESS = "SUCCESS";

    private final FinOpenApiClient client;
    private final FinOpenApiProperties properties;

    public AccountAuthClient(FinOpenApiClient client, FinOpenApiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 1원을 보낸다. 반환값은 <b>인증코드가 아니다</b> — 코드는 사용자 통장에만 있다.
     *
     * @throws FinApiException 금융망 실패. 호출부가 {@code 502} 로 옮긴다
     */
    public void sendOneWon(String userKey, String accountNo) {
        client.callEdu(DOMAIN, OPEN, userKey,
                Map.of("accountNo", accountNo, "authText", authText()));
    }

    /**
     * 대조 결과. <b>{@code boolean} 이 아닌 이유</b>는 "틀렸다"와 "만료됐다"가 사용자에게 서로
     * 다른 행동을 요구하기 때문이다 — 재입력 대 재발송. 하나로 접으면 호출부가 구분할 수 없다.
     */
    public enum AuthCheck {
        /** 코드가 맞았다. */
        MATCHED,
        /** 코드가 틀렸다({@code A1090}). <b>인증은 살아 있어</b> 곧바로 다시 입력할 수 있다. */
        MISMATCHED,
        /** 금융망 쪽 유효시간이 끝났다({@code A1087}). 재발송해야 풀린다. */
        EXPIRED
    }

    /**
     * 사용자가 입력한 코드를 대조한다.
     *
     * <p>네 코드가 <b>최상위 {@code responseCode}</b> 로 온다 — 공식 워크북
     * {@code ACCOUNT_AUTH_02} 시트 기준이다. 둘씩 짝을 이룬다:
     *
     * <table border="1">
     * <caption>인증 결과 코드</caption>
     * <tr><td>{@code A1090} 인증코드가 유효하지 않습니다 (실측)</td><td rowspan="2">{@code MISMATCHED} — 재입력</td></tr>
     * <tr><td>{@code A1088} 인증코드가 일치하지 않습니다</td></tr>
     * <tr><td>{@code A1087} 인증시간이 만료되었습니다 (프론트 실측)</td><td rowspan="2">{@code EXPIRED} — 재발송</td></tr>
     * <tr><td>{@code A1086} 인증코드 발급 기록이 없습니다</td></tr>
     * </table>
     *
     * <p>넷 다 <b>금융망 장애가 아니라 정상 결과</b>라 예외로 올리지 않는다. 나머지 실패만 예외다.
     *
     * @throws FinApiException 위 네 코드가 <b>아닌</b> 금융망 실패
     */
    public AuthCheck check(String userKey, String accountNo, String authCode) {
        try {
            AuthResult result = client.callEdu(DOMAIN, CHECK, userKey,
                    Map.of("accountNo", accountNo, "authText", authText(), "authCode", authCode),
                    AuthResult.class);
            // status 가 SUCCESS 가 아닌 값으로 오는 경우는 관측하지 못했다. 성공으로 읽지는 않는다.
            return result != null && SUCCESS.equals(result.status())
                    ? AuthCheck.MATCHED : AuthCheck.MISMATCHED;
        } catch (FinApiException e) {
            if (FinResponseCode.AUTH_CODE_INVALID.equals(e.finCode())
                    || FinResponseCode.AUTH_CODE_MISMATCHED.equals(e.finCode())) {
                return AuthCheck.MISMATCHED;
            }
            if (FinResponseCode.AUTH_EXPIRED.equals(e.finCode())
                    || FinResponseCode.AUTH_RECORD_MISSING.equals(e.finCode())) {
                return AuthCheck.EXPIRED;
            }
            throw e;
        }
    }

    private String authText() {
        return properties.accountAuth().authText();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthResult(String status, String transactionUniqueNo, String accountNo) {
    }
}
