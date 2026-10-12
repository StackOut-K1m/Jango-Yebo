package com.jangoyebo.forecast.residual;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.TransactionHistory.HistoricalTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 잔여 현금흐름 집계 (설계노트 M-1 · S15P21C105-66).
 *
 * <pre>
 * residual_deposit(d)    = 실제 입금 합계(d) − Σ 예정 거래에 매칭된 입금(d)
 * residual_withdrawal(d) = 실제 출금 합계(d) − Σ 예정 거래에 매칭된 출금(d)
 * </pre>
 *
 * <p><b>API 서버가 만든다.</b> 매칭 결과가 DB 에 있고, 워커는 계산 규칙을 모른 채 시계열만
 * 받는다(20.11). 참조 구현은 {@code ai/app/forecast/residual.py} 이고 이 클래스는 그 이식본이다.
 *
 * <p>정의가 뺄셈이라 이중 계상이 구조적으로 불가능하다 — 예정 거래가 설명한 만큼은 잔여에서
 * 이미 빠져 있고, 사용자가 새 패턴을 승인하면 잔여가 자동으로 준다. <b>단 매칭이 돌 때만
 * 그렇다.</b> 뺄 대상이 비면 뺄셈은 아무것도 빼지 않는다.
 *
 * <h2>이식하며 지킨 규칙 넷</h2>
 *
 * <ol>
 *   <li><b>{@code is_excluded} 는 실제 합계에서 아예 뺀다.</b> 장비 구입 같은 일회성이라
 *       예측 대상이 아니고 잔여로도 넘기지 않는다. 이 구현에서는 입력을 주는
 *       {@code TransactionHistory} 의 SQL 이 이미 걸러 준다 — 그래서 여기 코드가 없다.
 *   <li><b>매칭된 거래를 뺀다.</b> 부분 UK({@code uk_pt_matched})가 1:1 을 보장하므로
 *       한 거래가 두 예정에 붙지 않는다.
 *   <li><b>거래가 없는 날도 0 으로 채운다.</b> 배열에서 빠뜨리면 요일별 통계의 표본 수가
 *       틀어진다. 「비어 있음」이 아니라 「0」이다.
 *   <li><b>{@code BigDecimal} 로만 다룬다.</b> {@code double} 로 합치면 워커의 값과 갈린다.
 *       숫자로 바꾸는 것은 워커에 보낼 때뿐이다.
 * </ol>
 *
 * <h2>캐시하지 않는다</h2>
 *
 * <p>패턴이 승인되면 매칭 항목이 늘어 <b>과거 잔여가 소급해서 준다.</b> 저장해 두면 승인
 * 직후 이중 계상이 생기므로 예측 실행 시점마다 새로 집계한다.
 */
public final class ResidualSeriesAggregator {

    private ResidualSeriesAggregator() {
    }

    /**
     * @param transactions        {@code is_excluded = false} 인 거래. 범위 밖은 무시된다
     * @param matchedTransactionIds {@code PLANNED_TRANSACTION.matched_transaction_id} 가 가리키는 거래
     * @param accountIds          집계 대상 계좌. <b>연결이 살아 있는 계좌만</b> 넘긴다 —
     *                            해제한 계좌는 거래가 남아 있는데 잔액이 시작 잔액에서 빠져,
     *                            그 거래를 잔여에 넣으면 곡선이 한쪽으로 기운다.
     *                            비어 있으면 계좌를 가리지 않는다
     */
    public static ResidualSeries aggregate(List<HistoricalTransaction> transactions,
                                           Set<Long> matchedTransactionIds,
                                           Set<Long> accountIds,
                                           LocalDate startDate,
                                           LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "endDate 가 startDate 보다 앞이다: %s < %s".formatted(endDate, startDate));
        }

        int span = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<BigDecimal> deposit = zeros(span);
        List<BigDecimal> withdrawal = zeros(span);

        for (HistoricalTransaction tx : transactions) {
            if (matchedTransactionIds.contains(tx.transactionId())) {
                continue;   // 예정 거래가 설명한 몫 — 뺄셈으로 이미 빠진 부분
            }
            if (!accountIds.isEmpty() && !accountIds.contains(tx.accountId())) {
                continue;
            }
            if (tx.date().isBefore(startDate) || tx.date().isAfter(endDate)) {
                continue;
            }

            int i = (int) ChronoUnit.DAYS.between(startDate, tx.date());
            if (tx.flowType() == FlowType.DEPOSIT) {
                deposit.set(i, deposit.get(i).add(tx.amount()));
            } else {
                withdrawal.set(i, withdrawal.get(i).add(tx.amount()));
            }
        }

        return new ResidualSeries(startDate, deposit, withdrawal);
    }

    /**
     * 예정 거래가 설명한 금액 비율. 잔여가 얼마나 큰지 감을 잡는 데 쓴다.
     *
     * <p>1.0 에 가까우면 잔여 축이 거의 비어 있다는 뜻이고, 낮으면 <b>지금 곡선이 그만큼
     * 틀리고 있다</b>는 뜻이다. 로그로 남겨 매처가 실제로 무엇을 잡는지 본다.
     */
    public static double explainedRatio(List<HistoricalTransaction> transactions,
                                        Set<Long> matchedTransactionIds) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal matched = BigDecimal.ZERO;
        for (HistoricalTransaction tx : transactions) {
            total = total.add(tx.amount());
            if (matchedTransactionIds.contains(tx.transactionId())) {
                matched = matched.add(tx.amount());
            }
        }
        if (total.signum() == 0) {
            return 1.0;
        }
        return matched.divide(total, java.math.MathContext.DECIMAL64).doubleValue();
    }

    private static List<BigDecimal> zeros(int span) {
        List<BigDecimal> values = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            values.add(BigDecimal.ZERO);
        }
        return values;
    }
}
