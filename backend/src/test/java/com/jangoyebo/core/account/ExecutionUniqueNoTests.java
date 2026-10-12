package com.jangoyebo.core.account;

import com.jangoyebo.common.domain.FlowType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 반영 채번 (API 9.6) — <b>순수 함수라 Docker 가 필요 없다.</b>
 *
 * <p>이 함수가 지켜야 하는 것은 둘이다. <b>길이</b>(20자가 아니면 {@code H1010} 이고, 0 패딩이
 * 빠지면 간헐적으로만 실패해 원인을 찾기 어렵다) 와 <b>결정성</b>(같은 회차는 어느 서버가 언제
 * 보내도 같은 번호여야 두 번째 요청이 {@code H1007} 로 막힌다).
 */
class ExecutionUniqueNoTests {

    private static final LocalDate DUE = LocalDate.of(2026, 9, 14);
    private static final String ACCOUNT_NO = "0000000000000000";

    @Test
    void 항상_숫자_20자다() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String uniqueNo = ExecutionUniqueNo.derive(
                    ACCOUNT_NO,
                    DUE.plusDays(ThreadLocalRandom.current().nextInt(400)),
                    ThreadLocalRandom.current().nextBoolean() ? FlowType.DEPOSIT : FlowType.WITHDRAWAL,
                    BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(1, 9_000_000)),
                    "적요" + ThreadLocalRandom.current().nextInt(1000),
                    ThreadLocalRandom.current().nextInt(5));

            assertThat(uniqueNo).hasSize(20).containsOnlyDigits();
            seen.add(uniqueNo);
        }
        // 충돌이 잦으면 H1007 이 정상 실행을 막는다. 2,000회에 몇 건이라도 겹치면 신호다.
        assertThat(seen).hasSize(2_000);
    }

    @Test
    void 같은_입력이면_같은_번호다() {
        String first = ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "김영자", 0);
        String second = ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "김영자", 0);

        assertThat(second).isEqualTo(first);
    }

    /**
     * <b>금액의 {@code scale} 이 번호를 가르면 안 된다.</b>
     *
     * <p>예정 거래 금액은 {@code NUMERIC(19,4)} 라 같은 900,000원이 {@code 900000} 으로도
     * {@code 900000.0000} 으로도 온다. 둘이 다른 번호를 만들면 재전송이 중복으로 막히지 않는다.
     */
    @Test
    void 금액의_소수점_표기는_번호를_가르지_않는다() {
        String plain = ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "김영자", 0);
        String scaled = ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000.0000"), "김영자", 0);

        assertThat(scaled).isEqualTo(plain);
    }

    /**
     * 접두사가 <b>예정일</b>이다.
     *
     * <p>실행일로 잡으면 하루 늦게 따라잡는 서버가 다른 번호를 만들어 금융망이 거절하지 못한다 —
     * 이중 실행을 막는 성질이 통째로 사라진다.
     */
    @Test
    void 예정일로_시작한다() {
        String uniqueNo = ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "김영자", 0);

        assertThat(uniqueNo).startsWith("20260914");
    }

    @Test
    void 입력이_하나라도_다르면_번호가_다르다() {
        String base = ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "김영자", 0);

        assertThat(ExecutionUniqueNo.derive("0011111111111111", DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "김영자", 0)).isNotEqualTo(base);
        assertThat(ExecutionUniqueNo.derive(ACCOUNT_NO, DUE.plusDays(1), FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "김영자", 0)).isNotEqualTo(base);
        assertThat(ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.DEPOSIT,
                new BigDecimal("900000"), "김영자", 0)).isNotEqualTo(base);
        assertThat(ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900001"), "김영자", 0)).isNotEqualTo(base);
        assertThat(ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "박정호", 0)).isNotEqualTo(base);
        // 같은 날 같은 금액·적요가 두 건인 경우다. 순번이 안 먹으면 둘째가 영영 못 나간다.
        assertThat(ExecutionUniqueNo.derive(ACCOUNT_NO, DUE, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), "김영자", 1)).isNotEqualTo(base);
    }
}
