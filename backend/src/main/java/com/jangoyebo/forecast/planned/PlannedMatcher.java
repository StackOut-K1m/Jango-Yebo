package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.core.TransactionHistory.HistoricalTransaction;
import com.jangoyebo.forecast.pattern.RecurringPatternDetector;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 예정 거래를 실제 거래에 붙인다 (API 9.5 최소 매처).
 *
 * <p><b>순수 함수다.</b> DB·시계를 건드리지 않는다 —
 * {@code RecurringPatternDetector}·{@code BalanceCurveCalculator} 와 같은 형태이며,
 * 규칙이 Docker 없이 단위 테스트로 굳는다.
 *
 * <h2>왜 이것이 P0 인가</h2>
 *
 * <p>잔여 현금흐름의 정의가 {@code 일별 실제 합계 − 예정 거래에 매칭된 합계} 다. <b>뺄 대상이
 * 비면 뺄셈이 아무것도 빼지 않는다</b> — 잔여가 「실제 거래 전부」가 되어 예정 거래를 두 번
 * 세고 곡선이 위로 발산한다. 「뺄셈이라 이중 계상이 구조적으로 불가능하다」는 설명은
 * <b>매칭이 돌 때만</b> 참이다(설계노트 M-1).
 *
 * <h2>줄인 것</h2>
 *
 * <p>9.5 의 전체 규칙은 거래처 <b>유사도</b>를 포함한 가중 점수 매칭이고 그것은 P1-05 다.
 * 여기서는 방향·계좌·날짜·금액·거래처 다섯 조건을 <b>전부 통과</b>해야 붙는다.
 *
 * <p><b>거래처는 유사도가 아니라 동일성으로만 본다.</b> 적요로 거래처를 «추정»하면 틀렸을 때
 * 잔여가 조용히 어긋나는데, 패턴이 만든 예정 거래는 추정할 것이 없다 — 탐지기가 바로 그
 * 적요로 거래를 묶어 그 패턴을 만들었다. 그래서 {@code RecurringPatternDetector.merchantKey}
 * 를 그대로 쓴다. 넓히는 판단이 아니라 <b>탐지기가 이미 내린 판단을 매칭이 따라가는</b>
 * 것이므로 패턴 예정 거래의 재현율은 정의상 줄지 않는다.
 *
 * <p>실측(시드 「김가네」 · 기준일 2026-09-17)에서 없어서 붙은 오매칭 둘:
 *
 * <pre>
 * 동서식품    예정 09/17 −291,100  ←  무등빌딩관리단 실거래 09/15 −260,000
 * 해양도시가스 예정 09/18 −120,000  ←  SK매직         실거래 09/15 −120,000
 * </pre>
 *
 * <p>방향·계좌·날짜·금액 네 조건을 전부 통과한다 — 금액 범위에 들고 허용폭 안이다.
 * 붙은 예정 거래는 곡선에서 빠지므로 <b>실제로 나갈 411,100원이 사라졌고</b>, 그만큼
 * 예보가 후하게 나왔다. 사라지는 쪽이라 화면에는 아무 표시도 안 남는다.
 *
 * <p><b>사용자·세금·대출이 만든 예정 거래는 게이트를 걸지 않는다</b>({@code merchantKey} 가
 * {@code null}). 이름이 사람이 적은 자유 문구라 통장 적요와 같을 이유가 없다 — 「임차료」로
 * 넣은 예정 거래에 「김영자」 이체가 붙는 것이 정상이다.
 */
public final class PlannedMatcher {

    private PlannedMatcher() {
    }

    /**
     * 붙일 수 있는 짝을 모두 찾아 <b>1:1 로</b> 배정한다.
     *
     * <p>배정은 결정적이다 — 같은 입력이면 같은 결과다. 후보쌍을 (날짜 오차, 금액 오차,
     * 예정 id, 거래 id) 순으로 정렬해 앞에서부터 양쪽이 비어 있을 때만 붙인다. 탐욕이라
     * 전역 최적은 아니지만, <b>가장 가까운 짝부터 붙는다</b>는 성질이 사람의 판단과 맞고
     * 무엇보다 재현된다.
     *
     * @param alreadyMatchedTxIds 이미 다른 예정 거래에 붙어 있는 거래. {@code uk_pt_matched} 가
     *                            1:1 을 강제하므로 여기서 걸러야 INSERT 에서 터지지 않는다
     */
    public static List<Match> match(List<Candidate> candidates,
                                    List<HistoricalTransaction> transactions,
                                    Set<Long> alreadyMatchedTxIds) {
        List<Pair> pairs = new ArrayList<>();
        for (Candidate candidate : candidates) {
            for (HistoricalTransaction tx : transactions) {
                if (alreadyMatchedTxIds.contains(tx.transactionId())) {
                    continue;
                }
                if (!fits(candidate, tx)) {
                    continue;
                }
                int dateDiff = (int) ChronoUnit.DAYS.between(candidate.expectedDate(), tx.date());
                BigDecimal amountDiff = tx.amount().subtract(candidate.amountExpected());
                pairs.add(new Pair(candidate, tx, dateDiff, amountDiff));
            }
        }

        pairs.sort(Comparator
                .comparingInt((Pair p) -> Math.abs(p.dateDiff))
                .thenComparing(p -> p.amountDiff.abs())
                .thenComparing(p -> p.candidate.plannedId())
                .thenComparing(p -> p.tx.transactionId()));

        Set<Long> usedPlanned = new HashSet<>();
        Set<Long> usedTx = new HashSet<>(alreadyMatchedTxIds);
        List<Match> matches = new ArrayList<>();
        for (Pair pair : pairs) {
            if (!usedPlanned.add(pair.candidate.plannedId())) {
                continue;
            }
            if (!usedTx.add(pair.tx.transactionId())) {
                usedPlanned.remove(pair.candidate.plannedId());
                continue;
            }
            matches.add(new Match(pair.candidate.plannedId(), pair.tx.transactionId(),
                    pair.dateDiff, pair.amountDiff));
        }
        return matches;
    }

    /**
     * 다섯 조건.
     *
     * <p>금액은 <b>범위 포함으로만</b> 판정한다. 확정 금액으로 넣은 예정 거래
     * ({@code amountMin == amountMax})는 이체 수수료 몇백 원 차이로도 붙지 않는다 —
     * 허용폭을 넣지 않은 것은 의도다. 느슨하게 하면 엉뚱한 거래가 붙고, 그 오차는
     * 잔여를 통해 곡선에 조용히 들어간다. 필요해지면 {@link MatchPolicy} 한 곳에서 켠다.
     */
    private static boolean fits(Candidate candidate, HistoricalTransaction tx) {
        if (candidate.flowType() != tx.flowType()) {
            return false;
        }
        // 예정 거래에 계좌를 지정하지 않았으면 사업장의 어느 계좌든 된다.
        if (candidate.accountId() != null && !candidate.accountId().equals(tx.accountId())) {
            return false;
        }
        // 패턴이 만든 예정 거래는 거래처가 같아야 한다. 사용자 등록분은 비교할 것이 없다.
        if (candidate.merchantKey() != null
                && !candidate.merchantKey().equals(
                        RecurringPatternDetector.merchantKey(tx.description()))) {
            return false;
        }
        long dateDiff = Math.abs(ChronoUnit.DAYS.between(candidate.expectedDate(), tx.date()));
        if (dateDiff > candidate.toleranceDays()) {
            return false;
        }
        return tx.amount().compareTo(candidate.amountMin()) >= 0
                && tx.amount().compareTo(candidate.amountMax()) <= 0;
    }

    /**
     * @param accountId     {@code null} 이면 계좌를 가리지 않는다
     * @param toleranceDays {@link MatchPolicy#toleranceDays} 가 정한 값
     * @param merchantKey   {@code RecurringPatternDetector.merchantKey} 로 만든 패턴 거래처 키.
     *                      <b>{@code null} 이면 거래처를 가리지 않는다</b> — 사용자·세금·대출이
     *                      만든 예정 거래가 그 경우다
     */
    public record Candidate(Long plannedId,
                            Long accountId,
                            FlowType flowType,
                            LocalDate expectedDate,
                            BigDecimal amountMin,
                            BigDecimal amountExpected,
                            BigDecimal amountMax,
                            int toleranceDays,
                            String merchantKey) {
    }

    /**
     * @param dateDiffDays 실제일 − 예정일. 늦게 들어왔으면 양수다
     * @param amountDiff   실제액 − 예정액
     */
    public record Match(Long plannedId, Long transactionId, int dateDiffDays, BigDecimal amountDiff) {
    }

    private record Pair(Candidate candidate, HistoricalTransaction tx, int dateDiff, BigDecimal amountDiff) {
    }
}
