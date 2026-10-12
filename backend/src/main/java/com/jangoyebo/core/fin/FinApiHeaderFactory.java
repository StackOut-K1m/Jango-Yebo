package com.jangoyebo.core.fin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 공통 {@link FinApiHeader} 빌더 (기술스택 §3.6①).
 *
 * <p>금융망은 REST 가 아니다 — 모든 업무 API 가 같은 호스트로 POST 하고 무엇을 부르는지는
 * {@code apiName} 이 정한다. 그래서 리소스별 인터페이스 대신 <b>이 빌더 하나</b>로 간다.
 * {@code @HttpExchange} 로 메서드를 40개 만들어도 URL 이 전부 같아 의미가 없다.
 *
 * <p><b>시계는 반드시 한국시간이다.</b> {@code transmissionTime} 이 금융망 서버 시각 ±5분을
 * 벗어나면 전부 {@code H1003} 으로 실패한다 — 서버 시계가 6분 밀리면 금융망 연동 전체가
 * 죽는다. {@link Clock} 을 주입받는 이유는 테스트뿐이고, 운영 빈은 항상 KST 다.
 */
public class FinApiHeaderFactory {

    /** 금융망은 한국 서버다. 서버가 UTC 로 떠 있어도 여기서 KST 로 읽는다. */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    static final int UNIQUE_NO_LENGTH = 20;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final FinOpenApiProperties properties;
    private final Clock clock;

    public FinApiHeaderFactory(FinOpenApiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param userKey 없으면 {@code null} 을 넘긴다 — 빈 문자열로 나간다.
     *                <b>빈 값이 통하는 것은 사용자별 데이터가 없는 조회뿐이다</b>
     *                ({@code inquireBankCodes}·{@code inquireDemandDepositList}, 실측).
     *                계좌를 지목하는 조회·생성은 검증하므로 {@code H1009} 가 온다 —
     *                <b>은행코드 조회가 통했다고 키가 맞는 것은 아니다.</b>
     */
    public FinApiHeader create(String apiName, String userKey) {
        return create(apiName, userKey, null);
    }

    /**
     * 채번을 <b>호출부가 정한</b> 헤더 (API 9.6 예정 거래 자동 반영).
     *
     * <p>평소에는 이 메서드를 쓰지 않는다 — 채번은 중복 거부 키라 호출마다 새로 만드는 것이
     * 맞다({@link #nextTransactionUniqueNo}). <b>예외가 하나 있다.</b> 되돌릴 수 없는 쓰기를
     * 두 서버가 나눠 할 때는 그 성질이 거꾸로 필요하다 — 같은 실행에 같은 번호를 주면
     * 둘째 요청을 금융망이 {@code H1007} 로 막아 준다({@code ExecutionUniqueNo}).
     *
     * @param institutionTransactionUniqueNo {@code null} 이면 평소처럼 채번한다. 값이 오면
     *                                       <b>숫자 20자여야 한다</b> — 아니면 금융망이
     *                                       {@code H1010} 을 주는데, 그건 우리 결함이라
     *                                       나가기 전에 막는다
     */
    public FinApiHeader create(String apiName, String userKey, String institutionTransactionUniqueNo) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new FinApiHeader(
                apiName,
                DATE.format(now),
                TIME.format(now),
                properties.institutionCode(),
                properties.fintechAppNo(),
                apiName,                       // apiServiceCode 는 apiName 과 항상 같다
                institutionTransactionUniqueNo == null
                        ? nextTransactionUniqueNo(now)
                        : validated(institutionTransactionUniqueNo),
                properties.apiKey(),
                userKey == null ? "" : userKey);
    }

    /** 길이·숫자 검증. {@code H1010} 은 간헐적으로만 나서 원인을 찾기 어렵다 — 여기서 끊는다. */
    private static String validated(String uniqueNo) {
        if (uniqueNo.length() != UNIQUE_NO_LENGTH || !uniqueNo.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(
                    "institutionTransactionUniqueNo 는 숫자 " + UNIQUE_NO_LENGTH + "자여야 한다");
        }
        return uniqueNo;
    }

    /**
     * 회원 계열({@code MEMBER_01/02})은 {@code Header} 없이 {@code apiKey} 만 본문 최상위로
     * 보낸다(공통규약 23장). 같은 자격증명을 두 군데서 읽지 않도록 여기서 함께 내준다.
     */
    String apiKey() {
        return properties.apiKey();
    }

    /**
     * {@code institutionTransactionUniqueNo} 채번 — {@code YYYYMMDD}(8) + {@code HHMMSS}(6)
     * + 난수 6자리(0 패딩) = <b>정확히 20자</b>.
     *
     * <p><b>0 패딩이 핵심이다.</b> 난수를 그냥 이어붙이면 길이가 들쭉날쭉해져
     * {@code H1010}("기관거래고유번호가 유효하지 않습니다")이 <b>간헐적으로</b> 난다.
     * 이 프로젝트에서 실제로 이것 때문에 {@code userKey} 문제로 오진했다(기술스택 §3.6 실측).
     *
     * <p><b>재시도할 때 같은 번호를 재사용하지 않는다.</b> 중복이면 {@code H1007} 이라
     * 우리 {@code X-Request-Id} 멱등성과는 정반대다 — 이건 멱등성 키가 아니라 중복 거부 키다.
     * 그래서 호출마다 이 메서드를 새로 부른다.
     *
     * <p>중앙 시퀀스(Redis INCR)를 두지 않는다 — 20자리 난수도 허용되며 다중 스레드에서는
     * 난수가 카운터보다 안전하다(공통규약 23장). 초당 우리 호출은 3건 남짓이고 난수 공간은
     * 초당 10^6 이라 충돌은 실질적으로 없다.
     */
    static String nextTransactionUniqueNo(LocalDateTime now) {
        return DATE.format(now)
                + TIME.format(now)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
