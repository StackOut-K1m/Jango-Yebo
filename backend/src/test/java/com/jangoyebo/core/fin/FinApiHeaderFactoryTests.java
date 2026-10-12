package com.jangoyebo.core.fin;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공통 Header 빌더 검증.
 *
 * <p>Spring 컨텍스트도 Docker 도 쓰지 않는다. 여기서 틀리면 <b>모든</b> 금융망 호출이 죽는
 * 자리라, 실측으로 확인된 세 가지 계약을 그대로 단정한다 — 20자 고정 채번 · 호출마다 새 번호 ·
 * 한국시간.
 */
class FinApiHeaderFactoryTests {

    private static final FinOpenApiProperties PROPERTIES = FinTestFixtures.properties();

    private FinApiHeaderFactory factoryAt(Clock clock) {
        return new FinApiHeaderFactory(PROPERTIES, clock);
    }

    /**
     * 짧으면 {@code H1010} 이다. 난수를 0 패딩 없이 이어붙이면 길이가 들쭉날쭉해져
     * <b>간헐적으로</b> 실패하므로, 한 번 통과하는 것으로는 증명이 안 된다.
     */
    @Test
    void transactionUniqueNoIsAlwaysExactlyTwentyDigits() {
        var factory = factoryAt(Clock.systemUTC());

        for (int i = 0; i < 2_000; i++) {
            String number = factory.create("inquireBankCodes", null).institutionTransactionUniqueNo();
            assertThat(number)
                    .as("채번 %d 회차", i)
                    .hasSize(FinApiHeaderFactory.UNIQUE_NO_LENGTH)
                    .containsOnlyDigits();
        }
    }

    /**
     * 중복이면 {@code H1007} 이다 — 우리 {@code X-Request-Id} 멱등성과 정반대로,
     * <b>재시도할 때 같은 번호를 다시 쓰면 거절당한다.</b>
     */
    @Test
    void transactionUniqueNoIsNewOnEveryCall() {
        // 시각이 고정돼 있어도 달라야 한다. 앞 14자가 같으니 난수 6자리만으로 갈린다.
        var factory = factoryAt(Clock.fixed(Instant.parse("2026-09-08T02:30:00Z"), ZoneOffset.UTC));

        Set<String> issued = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            issued.add(factory.create("inquireBankCodes", null).institutionTransactionUniqueNo());
        }

        // 난수 6자리(10^6)에서 1000회를 뽑으므로 생일 문제로 한두 건은 겹칠 수 있다.
        // 잡으려는 것은 "같은 번호를 재사용한다"는 결함이지 완전 유일성이 아니다.
        assertThat(issued).hasSizeGreaterThan(990);
    }

    /**
     * {@code transmissionTime} 이 금융망 서버 시각 ±5분을 벗어나면 전부 {@code H1003} 이다.
     * 주입된 시계를 쓰지 않고 시스템 기본 시간대로 읽으면 <b>개발 장비(KST)에서는 통과하고
     * 서버(UTC)에서만 죽는다</b> — 그래서 일부러 UTC 시계를 넣고 UTC 값을 단정한다.
     */
    @Test
    void readsWallClockFromInjectedClockNotSystemDefault() {
        var factory = factoryAt(Clock.fixed(Instant.parse("2026-09-08T23:30:00Z"), ZoneOffset.UTC));

        FinApiHeader header = factory.create("inquireBankCodes", "user-key");

        assertThat(header.transmissionDate()).isEqualTo("20260908");
        assertThat(header.transmissionTime()).isEqualTo("233000");
        assertThat(header.institutionTransactionUniqueNo()).startsWith("20260908233000");
    }

    /** 운영 배선은 KST 고정이다. 서버가 UTC 로 떠 있어도 한국시간을 보내야 한다. */
    @Test
    void productionClockIsKoreaStandardTime() {
        assertThat(FinApiHeaderFactory.KST).isEqualTo(ZoneId.of("Asia/Seoul"));
        assertThat(FinOpenApiConfig.kstClock().getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void copiesApiNameIntoApiServiceCodeAndCarriesFixedCodes() {
        FinApiHeader header = factoryAt(Clock.systemUTC())
                .create("inquireTransactionHistoryList", "user-key");

        assertThat(header.apiName()).isEqualTo("inquireTransactionHistoryList");
        assertThat(header.apiServiceCode())
                .as("apiServiceCode 는 apiName 과 항상 같다")
                .isEqualTo(header.apiName());
        assertThat(header.institutionCode()).isEqualTo("00100");
        assertThat(header.fintechAppNo()).isEqualTo("001");
        assertThat(header.apiKey()).isEqualTo("test-api-key");
        assertThat(header.userKey()).isEqualTo("user-key");
    }

    /**
     * 조회 계열({@code inquireBankCodes} 등)은 {@code userKey} 를 검증하지 않아 빈 값으로도
     * {@code H0000} 이 온다(실측). {@code null} 을 그대로 보내지 않고 빈 문자열로 바꾼다.
     */
    @Test
    void sendsEmptyStringWhenUserKeyIsAbsent() {
        assertThat(factoryAt(Clock.systemUTC()).create("inquireBankCodes", null).userKey())
                .isEmpty();
    }

    /**
     * 자동 반영은 채번을 <b>호출부가 정한다</b> (API 9.6). 평소 규칙(호출마다 새 난수)의
     * 의도된 예외이며, 그 성질이 두 서버의 중복 실행을 막는 유일한 장치다.
     */
    @Test
    void 주어진_채번을_그대로_쓴다() {
        FinApiHeader header = factoryAt(Clock.systemUTC())
                .create("updateDemandDepositAccountWithdrawal", "user-key", "20260914000000000001");

        assertThat(header.institutionTransactionUniqueNo()).isEqualTo("20260914000000000001");
    }

    /** {@code H1010} 은 간헐적으로만 나서 원인을 찾기 어렵다 — 나가기 전에 끊는다. */
    @Test
    void 채번이_숫자_20자가_아니면_거부한다() {
        var factory = factoryAt(Clock.systemUTC());
        assertThatThrownBy(() -> factory.create("x", "user-key", "2026091400000000000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create("x", "user-key", "2026091400000000000a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
