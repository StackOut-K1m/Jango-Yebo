package com.jangoyebo.forecast.planned;

import com.jangoyebo.core.TransactionHistory;
import com.jangoyebo.core.TransactionHistory.HistoricalTransaction;
import com.jangoyebo.forecast.pattern.CadenceType;
import com.jangoyebo.forecast.pattern.RecurringPattern;
import com.jangoyebo.forecast.pattern.RecurringPatternDetector;
import com.jangoyebo.forecast.pattern.RecurringPatternRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 매처를 실제 데이터에 적용한다 (API 9.5 최소 매처).
 *
 * <p>계산은 {@link PlannedMatcher} 가 순수 함수로 하고 여기는 읽고 저장한다 —
 * {@code RecurringPatternService} 와 같은 경계다.
 *
 * <h2>왜 예측 실행 직전인가</h2>
 *
 * <p>잔여 시계열은 캐시하지 않고 실행마다 새로 집계하므로(설계노트 M-1) 매칭도 그 시점에
 * 최신이어야 한다. 거래 저장 시점에 붙이는 쪽이 자연스러워 보이지만, 저장 경로가 전부
 * {@code core} 안이라 {@code forecast} 로직을 넣을 수 없다. 거래가 들어오면 재계산이
 * 5초 안에 걸리므로 체감상 「저장 시 매칭」과 같다.
 */
@Service
public class PlannedMatchService {

    private static final Logger log = LoggerFactory.getLogger(PlannedMatchService.class);

    /** 매칭을 시도할 거래의 과거 범위. 후보 창({@link MatchPolicy#LOOKBACK_DAYS})보다 조금 넓다. */
    static final int TRANSACTION_LOOKBACK_DAYS = MatchPolicy.LOOKBACK_DAYS + 3;

    private final PlannedTransactionRepository planned;
    private final RecurringPatternRepository patterns;
    private final TransactionHistory history;

    public PlannedMatchService(PlannedTransactionRepository planned,
                               RecurringPatternRepository patterns,
                               TransactionHistory history) {
        this.planned = planned;
        this.patterns = patterns;
        this.history = history;
    }

    /**
     * 붙일 수 있는 만큼 붙이고, 매칭된 거래 id 집합을 돌려준다.
     *
     * <p>돌려주는 값이 곧 잔여 집계의 뺄셈 대상이다.
     *
     * <p><b>여기서 터지면 매칭 없이 진행한다.</b> 곡선은 매칭이 없어도 나온다(잔여가 커질 뿐)
     * 지만, 매칭 실패로 예측 전체가 죽으면 사용자는 예보를 아예 못 본다. 다음 실행이 다시
     * 시도한다.
     */
    @Transactional
    public Set<Long> matchAndCollect(Long businessId, LocalDate today) {
        Set<Long> alreadyMatched = new HashSet<>(planned.findMatchedTransactionIds(businessId));
        try {
            alreadyMatched.addAll(runMatching(businessId, today, alreadyMatched));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            // @Version 충돌(사용자가 동시에 수정) 또는 uk_pt_matched 위반.
            // 사전 로드한 집합으로 원리적으로 피하지만 경쟁이 나면 이번 실행만 건너뛴다.
            log.warn("예정 거래 매칭을 건너뛴다 — 사업장 {}: {}", businessId, e.getClass().getSimpleName());
        }
        return alreadyMatched;
    }

    private List<Long> runMatching(Long businessId, LocalDate today, Set<Long> alreadyMatched) {
        List<PlannedTransaction> targets = planned.findByBusinessIdAndStatusInAndExpectedDateBetween(
                businessId,
                List.of(PlannedStatus.EXPECTED, PlannedStatus.CONFIRMED),
                today.minusDays(MatchPolicy.LOOKBACK_DAYS),
                today.plusDays(MatchPolicy.MAX_TOLERANCE_DAYS));
        if (targets.isEmpty()) {
            return List.of();
        }

        List<HistoricalTransaction> transactions = history.findForPatternDetection(
                businessId, today.minusDays(TRANSACTION_LOOKBACK_DAYS), today);
        if (transactions.isEmpty()) {
            return List.of();
        }

        Map<Long, PlannedTransaction> byId = new HashMap<>();
        List<PlannedMatcher.Candidate> candidates = new ArrayList<>(targets.size());
        Map<Long, RecurringPattern> patternById = loadPatterns(targets);
        for (PlannedTransaction item : targets) {
            byId.put(item.getId(), item);
            Long patternId = item.getRecurringPatternId();
            // Map.of() 는 null 키 조회에 NPE 를 던진다. 사용자 등록 예정 거래가 그 경우다.
            candidates.add(toCandidate(item, patternId == null ? null : patternById.get(patternId)));
        }

        List<PlannedMatcher.Match> matches =
                PlannedMatcher.match(candidates, transactions, alreadyMatched);

        List<Long> matchedTxIds = new ArrayList<>(matches.size());
        for (PlannedMatcher.Match match : matches) {
            byId.get(match.plannedId())
                    .match(match.transactionId(), match.dateDiffDays(), match.amountDiff());
            matchedTxIds.add(match.transactionId());
        }

        reanchor(matches, byId, patternById, today);

        if (!matches.isEmpty()) {
            log.info("예정 거래 매칭 — 사업장 {} / 후보 {}건 중 {}건 붙음",
                    businessId, targets.size(), matches.size());
        }
        return matchedTxIds;
    }

    /**
     * <b>닻 재조정</b> — 붙은 실제 날짜만큼 앞으로의 예정일을 민다 (API 9.5).
     *
     * <h2>왜 필요한가</h2>
     *
     * <p>예정일은 탐지 시점의 위상에서 <b>고정 보폭으로만</b> 전진한다
     * ({@code RecurringPatternService.nextDate}). 실제 입금이 매번 이틀씩 늦으면 예정일은
     * 그 자리에 남고 간격이 계속 벌어진다. 허용폭이 {@code WEEKLY} 1일 ·
     * {@code MONTHLY} 3일로 좁아서(→ {@link MatchPolicy}) <b>한 번 밀리면 그 뒤로 영영
     * 안 붙는다.</b>
     *
     * <p>못 붙은 예정 거래는 잔여에서 빠지지 않아 <b>이중 계상</b>된다 — 모델이 그 부푼
     * 잔여를 이어서 예측하고, 그 예측이 예정 투영과 겹쳐 곡선이 위로 뜬다(설계노트 M-1).
     *
     * <p>실측(합성 3곳 · 2026-09-11). 실패 97건 중 <b>날짜가 59건</b>이고 금액은 7건이다.
     * 하루씩 흘리며 이 서비스의 루프를 그대로 돌려 쟀다.
     *
     * <pre>
     *                재현율   정밀도   놓친 금액
     * 닻 고정        62.1%   61.6%     49.3%
     * 닻 재조정      69.5%   67.4%     42.8%
     * </pre>
     *
     * <p><b>정밀도가 같이 오른다.</b> 허용폭을 넓히는 쪽은 68.8%에서 포화하면서 엉뚱한
     * 거래까지 붙이는데, 이쪽은 <b>맞는 자리로 옮기는</b> 것이라 정밀도를 팔지 않는다.
     *
     * <p>미는 방식은 <b>일괄 이동과 「실제 날짜에서 다시 세기」가 같은 값을 낸다</b>(둘 다
     * 69.5%). 같은 연산이어서다 — 오차가 일정하면 격자를 통째로 미는 것과 실제에서 다시
     * 세는 것이 만나는 날짜가 같다. 단순한 쪽을 골랐다.
     *
     * <h2>규칙</h2>
     *
     * <ul>
     *   <li><b>패턴마다 가장 최근 매칭 하나</b>만 본다. 위상은 최신 관측이 가장 잘 말한다
     *   <li>{@code BUSINESS_DAILY} 는 <b>건너뛴다.</b> 영업일마다 한 건이라 옮길 위상이 없고,
     *       날짜로 밀면 주말에 얹힌다
     *   <li>미는 폭은 매칭 허용폭 안이라 최대 {@link MatchPolicy#MAX_TOLERANCE_DAYS}일이다 —
     *       한 번에 일정이 멀리 튈 수 없다
     * </ul>
     */
    private void reanchor(List<PlannedMatcher.Match> matches,
                          Map<Long, PlannedTransaction> byId,
                          Map<Long, RecurringPattern> patternById,
                          LocalDate today) {
        Map<Long, PlannedMatcher.Match> freshest = new HashMap<>();
        for (PlannedMatcher.Match match : matches) {
            RecurringPattern pattern = patternOf(byId.get(match.plannedId()), patternById);
            if (pattern == null || pattern.getCadenceType() == CadenceType.BUSINESS_DAILY) {
                continue;
            }
            freshest.merge(pattern.getId(), match, (old, now) ->
                    byId.get(now.plannedId()).getExpectedDate()
                            .isAfter(byId.get(old.plannedId()).getExpectedDate()) ? now : old);
        }

        for (Map.Entry<Long, PlannedMatcher.Match> entry : freshest.entrySet()) {
            int offset = entry.getValue().dateDiffDays();
            if (offset == 0) {
                continue;
            }
            List<PlannedTransaction> upcoming =
                    planned.findByRecurringPatternIdAndStatusAndExpectedDateGreaterThanEqual(
                            entry.getKey(), PlannedStatus.EXPECTED, today);
            if (upcoming.isEmpty()) {
                continue;
            }
            PlannedTransaction first = null;
            for (PlannedTransaction item : upcoming) {
                item.reschedule(offset);
                if (first == null || item.getExpectedDate().isBefore(first.getExpectedDate())) {
                    first = item;
                }
            }
            RecurringPattern moved = patternById.get(entry.getKey());
            moved.advanceNextExpectedDate(first.getExpectedDate());
            // 주기의 기준값도 같이 옮긴다. 월 반복은 생성기가 dayOfMonth 에서 날짜를 만들어
            // 닻만 밀면 옛 날짜로 도로 끌어당기고(S15P21C105-187), 주 반복은 곡선은 멀쩡한
            // 대신 화면이 옛 요일을 계속 적는다(S15P21C105-189).
            moved.moveAnchorDay(first.getOriginalExpectedDate());
            log.info("닻 재조정 — 패턴 {} / {}일 이동 / 예정 {}건",
                    entry.getKey(), offset, upcoming.size());
        }
    }

    private static RecurringPattern patternOf(PlannedTransaction item,
                                              Map<Long, RecurringPattern> patternById) {
        Long patternId = item == null ? null : item.getRecurringPatternId();
        return patternId == null ? null : patternById.get(patternId);
    }

    private Map<Long, RecurringPattern> loadPatterns(List<PlannedTransaction> targets) {
        Set<Long> ids = new HashSet<>();
        for (PlannedTransaction item : targets) {
            if (item.getRecurringPatternId() != null) {
                ids.add(item.getRecurringPatternId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, RecurringPattern> byId = new HashMap<>();
        for (RecurringPattern pattern : patterns.findAllById(ids)) {
            byId.put(pattern.getId(), pattern);
        }
        return byId;
    }

    /**
     * <b>매칭 범위는 투영 범위와 다르다.</b>
     *
     * <p>예정 거래의 {@code amountMin}/{@code amountMax} 는 <b>곡선에 그릴 값</b>이다 —
     * 좁을수록 밴드가 좁아져 좋다. 그런데 {@link PlannedMatcher#fits} 가 같은 값을
     * <b>필터</b>로 쓴다. 투영을 좁히면 매칭이 같이 좁아지고, 범위 밖 거래는 안 붙어
     * <b>잔여로 새어 이중 계상된다.</b> 한 필드가 두 가지 일을 하고 있었다.
     *
     * <p>실제로 그렇게 됐다. 요일별 금액(MR !76)이 {@code BUSINESS_DAILY} 의 투영을
     * q10~q90 으로 좁혔는데, 그것이 매칭 범위까지 좁혔다 — 하노식당 신한카드 124건 중
     * <b>요일별 범위에 드는 것이 104건(83.9%)</b>이다. 통짜 min~max 였으면 123건(99.2%)
     * 이다. <b>20건이 붙을 자리를 잃었다.</b>
     *
     * <p>그래서 매칭에는 <b>패턴이 관측한 전체 폭</b>을 함께 쓴다. 넓히기만 하고 좁히지
     * 않으므로 사용자가 예정 거래 금액을 직접 고친 경우에도 그 의도가 사라지지 않는다.
     * 실험실 측정에서 매칭 범위 {@code min~max} 는 재현율 62.1% 로, 좁힌 55.5% 보다 높다.
     *
     * <p><b>거래처 키는 패턴에서만 온다.</b> {@code PlannedTransaction.name} 이 아니라
     * {@code RecurringPattern.name} 을 쓰는 이유는, 예정 거래의 이름은 사용자가 고칠 수 있고
     * 그러면 <b>탐지가 묶은 적요와 갈라져</b> 그 뒤로 아무것도 안 붙는다. 패턴 이름은
     * 탐지기가 표본 적요에서 뽑은 값이라({@code displayName}) 통장 적요와 같은 계보다.
     */
    private static PlannedMatcher.Candidate toCandidate(PlannedTransaction item, RecurringPattern pattern) {
        int tolerance = MatchPolicy.toleranceDays(
                item.getSourceType(),
                pattern == null ? null : pattern.getCadenceType(),
                pattern == null ? null : pattern.getIntervalValue());

        BigDecimal matchMin = item.getAmountMin();
        BigDecimal matchMax = item.getAmountMax();
        if (pattern != null) {
            matchMin = matchMin.min(pattern.getExpectedAmountMin());
            matchMax = matchMax.max(pattern.getExpectedAmountMax());
        }
        String merchantKey = pattern == null
                ? null : RecurringPatternDetector.merchantKey(pattern.getName());

        return new PlannedMatcher.Candidate(item.getId(), item.getAccountId(), item.getFlowType(),
                item.getExpectedDate(), matchMin, item.getAmountExpected(), matchMax, tolerance,
                merchantKey);
    }
}
