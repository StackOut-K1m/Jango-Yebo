package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.domain.FlowType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공휴일 보정이 <b>두 날짜를 갈라 두는가</b> (P1-04).
 *
 * <p>{@code originalExpectedDate} 는 보정 <b>전</b>이고 {@code expectedDate} 는 보정 <b>후</b>다.
 * 둘이 다를 때만 화면이 「휴일로 밀림」을 표시한다.
 *
 * <p>실제로 한 번 무너뜨렸다 — 보정한 날짜를 두 칸에 다 넣어서, 임차료가 10/3(토·개천절)에서
 * 10/5(월)로 밀렸는데 <b>밀린 흔적이 남지 않았다.</b> 값은 맞고 표시만 조용히 사라지는
 * 종류라 눈으로는 못 잡는다.
 *
 * <p>Docker 가 필요 없다 — 순수 팩토리다.
 */
class PlannedTransactionDateTests {

    private static final LocalDate 개천절_토요일 = LocalDate.of(2026, 10, 3);
    private static final LocalDate 다음_영업일 = LocalDate.of(2026, 10, 5);

    @Test
    void 보정되면_원래_날짜가_남는다() {
        PlannedTransaction pt = fromPattern(다음_영업일, 개천절_토요일);

        assertThat(pt.getExpectedDate()).isEqualTo(다음_영업일);
        assertThat(pt.getOriginalExpectedDate())
                .as("보정 전 날짜가 사라지면 화면이 「휴일로 밀림」을 못 띄운다")
                .isEqualTo(개천절_토요일);
    }

    @Test
    void 보정이_없으면_둘이_같다() {
        LocalDate 평일 = LocalDate.of(2026, 10, 8);
        PlannedTransaction pt = fromPattern(평일, 평일);

        assertThat(pt.getExpectedDate()).isEqualTo(pt.getOriginalExpectedDate());
    }

    private static PlannedTransaction fromPattern(LocalDate due, LocalDate anchor) {
        return PlannedTransaction.fromPattern(
                1L, 2L, "임차료", due, anchor, FlowType.WITHDRAWAL,
                new BigDecimal("900000"), new BigDecimal("900000"), new BigDecimal("900000"),
                3L, null);
    }
}
