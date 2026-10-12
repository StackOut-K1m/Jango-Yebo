package com.jangoyebo.forecast.loan;

import com.jangoyebo.core.Loans;
import com.jangoyebo.core.Loans.RepayingLoan;
import com.jangoyebo.core.Loans.RepaymentCycle;
import com.jangoyebo.forecast.planned.PlannedSourceType;
import com.jangoyebo.forecast.planned.PlannedTransaction;
import com.jangoyebo.forecast.planned.PlannedTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대출 → 예정 거래 (P1-13). Docker 불필요 — 저장소를 가짜로 둔다.
 */
class LoanPlannedGeneratorTests {

    private static final Long BIZ = 2L;
    private static final LocalDate 오늘 = LocalDate.of(2026, 9, 15);
    private static final LocalDate 개설일 = LocalDate.of(2026, 9, 14);

    private Loans loans;
    private PlannedTransactionRepository planned;
    private LoanPlannedGenerator generator;
    private final List<PlannedTransaction> saved = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        loans = mock(Loans.class);
        planned = mock(PlannedTransactionRepository.class);
        when(planned.findOccupiedLoanDates(anyLong(), any())).thenReturn(Set.of());
        when(planned.saveAll(any())).thenAnswer(call -> {
            saved.clear();
            ((Iterable<PlannedTransaction>) call.getArgument(0)).forEach(saved::add);
            return saved;
        });
        generator = new LoanPlannedGenerator(loans, planned, event -> { });
    }

    private static RepayingLoan 대출(BigDecimal 총액, int 기간, Long 계좌, boolean 연결) {
        return new RepayingLoan(7L, 계좌, "소상공인 비상자금", RepaymentCycle.DAILY,
                총액, new BigDecimal("16.8"), 개설일, 개설일.plusDays(기간),
                null, null, false, 연결);
    }

    @Test
    void 지평_안의_회차만_만든다() {
        // 180일 대출인데 곡선은 90일만 본다. 전부 만들면 달력이 대출로 덮인다.
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("5414247"), 180, 11L, true)));

        int made = generator.regenerate(BIZ, 오늘);

        // 지평은 양끝을 포함한다 — 오늘부터 오늘+90 까지 91일. 반복 패턴 생성기와 같은 규약이다.
        assertThat(made).isEqualTo(91);
        assertThat(saved).hasSize(91);
        assertThat(saved.getFirst().getExpectedDate()).isEqualTo(오늘);   // 개설 다음날 = 오늘
        assertThat(saved.getLast().getExpectedDate()).isEqualTo(오늘.plusDays(90));
    }

    @Test
    void 매일_같은_금액이_빠진다() {
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("5414247"), 180, 11L, true)));

        generator.regenerate(BIZ, 오늘);

        // 5,414,247 / 180 = 30,079.15 → 내림 30,079. 마지막 회차만 나머지를 흡수하는데
        // 그 회차는 지평 밖(180일차)이라 여기 안 들어온다.
        assertThat(saved).extracting(PlannedTransaction::getAmountExpected)
                .containsOnly(new BigDecimal("30079"));
    }

    @Test
    void 금액이_확정이라_범위가_없다() {
        // 반복 패턴은 관측에서 추정한 범위를 갖지만 대출은 계약이다.
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("5414247"), 180, 11L, true)));

        generator.regenerate(BIZ, 오늘);

        PlannedTransaction first = saved.getFirst();
        assertThat(first.getAmountMin()).isEqualByComparingTo(first.getAmountExpected());
        assertThat(first.getAmountMax()).isEqualByComparingTo(first.getAmountExpected());
    }

    @Test
    void 출처가_LOAN_이라_자동_반영에_안_걸린다() {
        // findForExecutionWindow 가 PATTERN·USER 만 본다. 금융망이 08:30 에 스스로 빼므로
        // 우리가 또 빼면 하루에 두 번 나간다.
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("5414247"), 180, 11L, true)));

        generator.regenerate(BIZ, 오늘);

        assertThat(saved).extracting(PlannedTransaction::getSourceType)
                .containsOnly(PlannedSourceType.LOAN);
        assertThat(saved.getFirst().getLoanId()).isEqualTo(7L);
    }

    @Test
    void 연동이_끊긴_계좌의_대출은_곡선에_안_넣는다() {
        // 끊긴 계좌는 잔액이 갱신되지 않아 시작 잔액에 없다. 지출만 넣으면 없는 공백이 생긴다.
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("5414247"), 180, 11L, false)));

        assertThat(generator.regenerate(BIZ, 오늘)).isZero();
        verify(planned, never()).findOccupiedLoanDates(anyLong(), any());
    }

    @Test
    void 우리가_모르는_계좌의_대출도_안_넣는다() {
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("5414247"), 180, null, false)));

        assertThat(generator.regenerate(BIZ, 오늘)).isZero();
    }

    @Test
    void 이미_자리가_찬_날짜는_비켜_간다() {
        // 남은 행은 이미 일어났거나 사용자가 끈 것이다. 하나 더 만들면 곡선에 두 번 들어간다.
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("5414247"), 180, 11L, true)));
        when(planned.findOccupiedLoanDates(anyLong(), any()))
                .thenReturn(Set.of(오늘, 오늘.plusDays(3)));

        assertThat(generator.regenerate(BIZ, 오늘)).isEqualTo(89);   // 91 − 찬 자리 2
        assertThat(saved).extracting(PlannedTransaction::getExpectedDate)
                .doesNotContain(오늘, 오늘.plusDays(3));
    }

    @Test
    void 다시_만들기_전에_사업장_전체를_지운다() {
        // 대출 하나씩 지우면 완납·해지된 대출의 미래 회차가 영영 남는다.
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("5414247"), 180, 11L, true)));

        generator.regenerate(BIZ, 오늘);

        verify(planned).deleteRegenerableLoansOf(BIZ, 오늘);
    }

    @Test
    void 만기가_지평보다_짧으면_거기서_끝난다() {
        // 30일 대출이면 회차가 30개다. 지평이 90일이어도 없는 회차를 만들지 않는다.
        when(loans.repayingLoans(BIZ)).thenReturn(List.of(대출(new BigDecimal("1007479"), 30, 11L, true)));

        assertThat(generator.regenerate(BIZ, 오늘)).isEqualTo(30);
        assertThat(saved.getLast().getExpectedDate()).isEqualTo(개설일.plusDays(30));
    }
}
