package com.jangoyebo.core.fin;

import java.util.Set;

/**
 * 금융망 응답 코드 중 <b>문서와 실측으로 확인된 것</b>만 모은다 (기술스택 §3.6④).
 *
 * <p>전체는 214종이다(`H` 헤더 12 · `A` 계좌 103 · `E` 환전 92 · `G` 5 · `Q` 공통 2).
 * 전부 옮겨 적을 이유는 없다 — <b>우리 동작이 갈리는 코드만</b> 여기 올리고, 나머지는
 * {@code FIN_API_FAILED} 로 떨어뜨리되 <b>원본 코드를 반드시 로그에 남긴다.</b>
 * 안 그러면 장애 원인을 못 찾는다.
 *
 * <p>새 코드를 만나면 여기 추가한다. 추측으로 채우지 않는다 — 근거 없는 매핑은 없는 것보다 나쁘다.
 */
public final class FinResponseCode {

    /** 정상처리. 이 값이 아니면 전부 실패로 본다. */
    public static final String SUCCESS = "H0000";

    /**
     * {@code transmissionDate}(전송일자) 오류. 날짜가 오늘이 아니면 난다(실측 — 어제 날짜로 보내면
     * 이 코드다). {@link #CLOCK_SKEW} 의 날짜 쪽 짝이며 역시 <b>우리 서버 시계 문제</b>다.
     */
    public static final String TRANSMISSION_DATE_INVALID = "H1002";

    /**
     * {@code transmissionTime}(전송시각) 오류.
     *
     * <p>워크북 문구는 "형식이 유효하지 않습니다" 지만 <b>형식과 시각 오차를 둘 다 덮는다</b>
     * (실측 — 30분 전 시각을 보내도 이 코드다). 어느 쪽이든 <b>우리 서버 시계·요청 조립 문제</b>이며
     * 재시도해도 그대로면 똑같이 실패한다.
     */
    public static final String CLOCK_SKEW = "H1003";

    /** {@code apiKey} 가 유효하지 않다. <b>설정 문제</b>지 금융망 장애가 아니다. */
    public static final String API_KEY_INVALID = "H1008";

    /**
     * {@code institutionTransactionUniqueNo} 중복. <b>새 번호로 다시 부르면 통한다</b> —
     * 유일하게 재시도가 의미 있는 코드다.
     */
    public static final String DUPLICATE_TRANSACTION_NO = "H1007";

    /**
     * "기관거래고유번호가 유효하지 않습니다". 실측상 <b>길이가 20자가 아닐 때</b> 난다.
     * 우리 채번 버그이며 0 패딩이 빠지면 간헐적으로 나타난다.
     */
    public static final String INVALID_TRANSACTION_NO = "H1010";

    /**
     * "USER_KEY가 유효하지 않습니다"(실측 메시지). {@code userKey} 가 없거나 이 계좌의 소유자와
     * 맞지 않을 때다.
     *
     * <p>⚠ <b>어떤 API 가 이걸 검증하는지가 갈린다.</b> 사용자별 데이터가 없는 조회
     * ({@code inquireBankCodes} 등)는 빈 값으로도 통과하지만, <b>계좌를 지목하는 조회는
     * 검증한다</b>({@code inquireTransactionHistoryList} 에 빈 값 → {@code H1009}, 실측).
     * 앞의 관대함만 보고 "조회는 안 본다"로 일반화하면 원인을 늦게 찾는다.
     */
    public static final String USER_KEY_REJECTED = "H1009";

    /**
     * "요청 본문의 형식이 잘못되었습니다". 실측상 <b>금액을 숫자로 보내면</b> 난다 —
     * {@code transactionBalance} 를 비롯한 금액 필드는 전부 문자열이다. 문서 어디에도 없다.
     */
    public static final String MALFORMED_BODY = "Q1001";

    /**
     * 1원 인증 코드 불일치 ({@code ACCOUNT_AUTH_02}). <b>최상위 {@code responseCode} 로 온다</b>(실측).
     * 사용자 입력 오류이며, 틀렸다고 인증 자체가 무효화되지는 않는다 — 바로 다시 넣으면 통한다.
     */
    public static final String AUTH_CODE_INVALID = "A1090";

    /**
     * "계좌번호가 유효하지 않습니다"(실측 메시지, HTTP 400).
     *
     * <p><b>세 가지가 이 코드 하나로 온다</b> — 존재하지 않는 계좌번호 · 형식이 이상한 계좌번호 ·
     * <b>남의 계좌번호</b>(프론트 실측 S15P21C105-111). 금융망이 구분해 주지 않고,
     * <b>우리도 구분해서 알려주면 안 된다</b> — "있지만 당신 것이 아니다"는 계좌 존재 여부를
     * 흘리는 답이다. 그래서 {@code ACCOUNT_NO_INVALID} 하나로 모은다.
     */
    public static final String ACCOUNT_NO_INVALID = "A1003";

    /**
     * "인증코드 발급 기록이 없습니다"({@code ACCOUNT_AUTH_02}). <b>{@code A1087} 과 같은 뜻으로
     * 다룬다</b> — 우리 {@code ACCOUNT_AUTH_EXPIRED} 의 정의가 "만료 <b>또는 발급 기록 없음</b>"
     * 이다(공통규약 22장). 사용자가 할 일도 같다: 재발송.
     */
    public static final String AUTH_RECORD_MISSING = "A1086";

    /**
     * "인증코드가 일치하지 않습니다"({@code ACCOUNT_AUTH_02}). <b>{@code A1090} 과 별개 코드다</b> —
     * 공식 워크북에 둘 다 있고, 실측에서는 틀린 코드에 {@code A1090} 이 왔다. 사용자 입장에서는
     * 같은 일이라 둘 다 불일치로 접는다.
     */
    public static final String AUTH_CODE_MISMATCHED = "A1088";

    /**
     * <b>금융망 쪽</b> 1원 인증 유효시간 만료 (워크북 {@code ERROR_01} — "인증시간이
     * 만료되었습니다"). 프론트가 S15P21C105-111 로 보고했다.
     *
     * <p><b>금융망이 만료의 실제 권위자다.</b> 우리도 Redis TTL 로 시간을 재지만 그 값은
     * 금융망 값을 모른 채 정한 것이라, 금융망이 먼저 끝내면 이 코드가 온다. 둘 다
     * {@code ACCOUNT_AUTH_EXPIRED} 로 모아야 프론트가 코드 하나만 보고 "재발송"을 안내할 수 있다 —
     * 뭉뚱그려 {@code 502} 로 보내면 사용자는 무엇을 해야 할지 알 수 없다.
     */
    public static final String AUTH_EXPIRED = "A1087";

    /**
     * {@code MEMBER_01} 이미 존재하는 ID. 이메일은 <b>금융망 전역에서 유니크</b>하다.
     * 우리가 이전 시도에서 만든 것인지 다른 팀 것인지 <b>구분할 수 없다</b> —
     * ERD 가 "계좌 연결 시 재시도"를 설계했으므로 {@code MEMBER_02} 로 복구를 시도한다.
     */
    public static final String MEMBER_ID_DUPLICATED = "E4002";

    /** {@code MEMBER_02} 대상 없음. 아직 발급받지 않은 사용자라는 뜻이라 오류가 아니다. */
    public static final String MEMBER_ID_NOT_FOUND = "E4003";

    /**
     * 잔액 부족 (워크북 {@code A1014}, {@code DD_08}).
     *
     * <p><b>결함이 아니라 사건이다.</b> 예정 거래 자동 반영(API 9.6)에서 이 코드가 오면
     * 「시연 계좌에 돈이 모자라 지출이 못 나갔다」는 뜻이고, 그건 이 서비스가 예측하려는
     * <b>정산 공백이 실제로 일어난 것</b>이다. 그래서 다른 실패와 달리 다음 날 다시 시도한다.
     *
     * <p><b>실측 확인</b>(2026-09-14) — 잔액 10,100원 계좌에 1,010,100원 출금을 걸었더니
     * {@code HTTP 400} 에 이 코드와 "계좌 잔액이 부족하여 거래가 실패했습니다" 가 왔다.
     */
    public static final String INSUFFICIENT_BALANCE = "A1014";

    /**
     * 조회 파라미터 오류 — 시작일자·종료일자·거래구분·정렬순서({@code DD_12}).
     * 요청을 만드는 쪽이 우리라 <b>전부 우리 결함</b>이다. 특히 앞의 둘은 기간 분할이 잘못됐다는
     * 신호라 조용히 넘기면 안 된다.
     */
    private static final Set<String> QUERY_PARAM_INVALID = Set.of("A1004", "A1005", "A1006", "A1007");

    /** 재시도가 의미 있는 코드. 나머지는 같은 요청을 다시 보내도 같은 답이 온다. */
    private static final Set<String> RETRYABLE = Set.of(DUPLICATE_TRANSACTION_NO);

    /**
     * 금융망이 아니라 <b>우리</b>가 틀린 코드. 응답을 고쳐도 소용없으니 크게 남긴다 —
     * 장애 조사에서 "금융망이 이상하다"와 "우리 요청이 틀렸다"를 가르는 것이 이 구분의 전부다.
     */
    private static final Set<String> OUR_BUG = Set.of(
            TRANSMISSION_DATE_INVALID, CLOCK_SKEW, INVALID_TRANSACTION_NO,
            MALFORMED_BODY, API_KEY_INVALID);

    private FinResponseCode() {
    }

    public static boolean isSuccess(String code) {
        return SUCCESS.equals(code);
    }

    public static boolean isRetryable(String code) {
        return RETRYABLE.contains(code);
    }

    public static boolean isOurBug(String code) {
        return OUR_BUG.contains(code) || QUERY_PARAM_INVALID.contains(code);
    }

    /**
     * 로그에 붙일 진단 한 줄. 이 세 코드는 원인이 특정되는데 메시지만 봐서는 알 수 없어
     * 매번 같은 곳을 헤매게 된다.
     */
    public static String diagnosis(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case TRANSMISSION_DATE_INVALID, CLOCK_SKEW ->
                    " — 서버 시계가 밀렸거나(±5분) 전송일자·시각 형식이 틀렸다. NTP 동기화를 먼저 확인한다";
            case API_KEY_INVALID -> " — FINOPENAPI_API_KEY 설정이 잘못됐다. 금융망 장애가 아니다";
            case INVALID_TRANSACTION_NO -> " — 채번이 20자가 아니다. 난수 0 패딩을 확인한다";
            case MALFORMED_BODY -> " — 금액 필드를 숫자로 보내지 않았는지 확인한다(전부 문자열이다)";
            case USER_KEY_REJECTED -> " — userKey 가 없거나 이 계좌의 소유자와 맞지 않는다";
            case AUTH_CODE_INVALID -> " — 사용자가 코드를 잘못 입력했다. 서버 결함이 아니다";
            case AUTH_EXPIRED, AUTH_RECORD_MISSING -> " — 금융망 쪽 인증이 살아 있지 않다. 재발송이 필요하다";
            case AUTH_CODE_MISMATCHED -> " — 사용자가 코드를 잘못 입력했다. 서버 결함이 아니다";
            case ACCOUNT_NO_INVALID -> " — 사용자가 넣은 계좌번호 문제다. 서버 결함이 아니다";
            case INSUFFICIENT_BALANCE -> " — 계좌 잔액이 모자란다. 자동 반영이면 다음 날 다시 시도한다";
            case MEMBER_ID_DUPLICATED -> " — 이 이메일은 금융망 전역에서 이미 쓰였다. MEMBER_02 로 복구를 시도한다";
            case MEMBER_ID_NOT_FOUND -> " — 아직 userKey 를 발급받지 않은 사용자다";
            default -> "";
        };
    }
}
