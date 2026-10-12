package com.jangoyebo.forecast.pattern;

import com.jangoyebo.common.domain.BusinessCalendar;
import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.forecast.calendar.OpenDayRatio;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.PatternCandidateAlerts;
import com.jangoyebo.core.TransactionHistory;
import com.jangoyebo.forecast.pattern.RecurringPattern.Effect;
import com.jangoyebo.forecast.pattern.RecurringPatternDetector.DetectedPattern;
import com.jangoyebo.forecast.planned.PlannedTransaction;
import com.jangoyebo.forecast.planned.PlannedTransactionRepository;
import com.jangoyebo.forecast.run.ForecastInputsChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 반복 패턴 탐지·승인 (P0-03·04·05).
 *
 * <p>탐지 <b>계산</b>은 {@link RecurringPatternDetector}(순수 함수)가 하고, 여기는 읽고 저장한다.
 * 그 경계 덕에 탐지 규칙은 Docker 없이 단위 테스트로 굳는다.
 */
@Service
public class RecurringPatternService {

    private static final Logger log = LoggerFactory.getLogger(RecurringPatternService.class);

    // --- 조정 가능 지점 -------------------------------------------------------

    /**
     * 탐지가 훑는 과거 범위. <b>잔여 창과 같다</b>({@code ForecastRunService.LOOKBACK_DAYS}).
     *
     * <p>180일이었다. 「계절성이 섞이기 전 최근 흐름을 보려면 너무 길어도 안 된다」가 근거였는데
     * <b>재 보니 그 대가가 없었다.</b> 대신 <b>분기 패턴을 구조적으로 놓치고 있었다</b> —
     * 부가가치세는 약 91일 주기라 180일 창에 최대 2건만 들어오고, {@code MIN_SAMPLES} 가 3 이라
     * 영원히 후보가 되지 않는다. 부가세는 소상공인 자금 공백의 대표 원인이다.
     *
     * <p>실측 (BE 규칙 복제본 · 라벨 붙은 픽스처 3곳 · 1년치,
     * {@code private/현재/forecast-lab/audit/be_detector.py}):
     *
     * <pre>
     * 창     재현율    정밀도   새로 잡힌 것
     * 180    98.8%    86.5%
     * 270   100.0%    86.7%   부가가치세
     * 365   100.0%    86.8%   부가가치세
     * </pre>
     *
     * <p>같은 패턴 39개의 신뢰도는 <b>중앙값 +0.025</b> 로 올랐다(표본이 늘어 표본충분성이 오른다).
     * 27개가 오르고 11개가 내렸는데, 내린 쪽은 카드 정산처럼 <b>금액이 요일마다 다른</b> 것들이고
     * 그건 탐지 창이 아니라 금액 축의 문제다(설계노트 G-2 · {@code S15P21C105-143}).
     *
     * <p>270 이 아니라 365 인 이유는 둘이다 — 분기 패턴이 <b>4번</b> 들어와 표본 3건에 여유가
     * 생기고, {@code ForecastRunService} 의 잔여 창과 같아져 <b>확정한 패턴이 잔여 전 구간을
     * 덮는다</b>({@code S15P21C105-151} 이 남긴 ⚠ 가 이것이다).
     */
    private static final int LOOKBACK_DAYS = 365;

    /** 승인 시 생성하는 예정 거래 범위 (API 9.1 — "향후 90일"). */
    private static final int HORIZON_DAYS = 90;

    /** 한 패턴이 만들 수 있는 예정 거래 상한. BUSINESS_DAILY 는 90일에 60건이 넘는다. */
    private static final int MAX_GENERATED = 120;

    /**
     * 닻을 오늘까지 굴릴 때의 회차 상한. {@code BUSINESS_DAILY} 가 1년에 약 250회차다.
     *
     * <p>넘으면 위상을 포기하고 오늘부터 센다 — 1년 넘게 재생성이 없던 패턴이라 옛 위상이
     * 맞다는 근거도 이미 약하다.
     */
    private static final int MAX_ROLL_FORWARD = 400;

    private final RecurringPatternRepository patterns;
    private final PlannedTransactionRepository planned;
    private final TransactionHistory history;
    private final ApplicationEventPublisher events;

    private final RecurringPatternTransactionRepository patternTransactions;
    private final OpenDayRatio openDays;
    private final PatternCandidateAlerts candidateAlerts;

    public RecurringPatternService(RecurringPatternRepository patterns,
                                   PlannedTransactionRepository planned,
                                   TransactionHistory history,
                                   ApplicationEventPublisher events,
                                   RecurringPatternTransactionRepository patternTransactions,
                                   OpenDayRatio openDays,
                                   PatternCandidateAlerts candidateAlerts) {
        this.patterns = patterns;
        this.planned = planned;
        this.history = history;
        this.events = events;
        this.patternTransactions = patternTransactions;
        this.openDays = openDays;
        this.candidateAlerts = candidateAlerts;
    }

    /**
     * 승인 대기 후보 수를 알림에 맞춘다 (API 10.3 {@code PATTERN_CANDIDATE}).
     *
     * <p><b>탐지·승인·무시가 모두 부른다.</b> 「후보가 생겼다」를 사건으로 기록하면 승인을 마친
     * 뒤에도 알림이 남아 처리한 일이 계속 경고로 보인다. 지금 몇 건이 기다리는지를 그때그때
     * 다시 세고, 0 이면 알림이 내려간다.
     */
    private void syncCandidateAlert(Long businessId) {
        List<RecurringPattern> candidates = patterns
                .findByBusinessIdAndStatusOrderByConfidenceDesc(businessId, PatternStatus.CANDIDATE);
        candidateAlerts.sync(businessId, candidates.size(),
                candidates.stream().map(RecurringPattern::getName).toList());
    }

    /**
     * 과거 거래를 훑어 후보를 만들거나 갱신한다.
     *
     * <p><b>무시({@code DISMISSED})한 것은 되살리지 않는다.</b> 같은 키로 이미 행이 있으면
     * {@code CANDIDATE} 일 때만 값을 갱신한다 — 사용자가 조정한 금액이나 무시 결정을
     * 배치가 지우면 같은 판단을 계속 요구하게 된다(API 9.3).
     *
     * @return 새로 만들어진 후보 수
     */
    @Transactional
    public int detectAndUpsert(Long businessId, LocalDate today) {
        List<TransactionHistory.HistoricalTransaction> transactions =
                history.findForPatternDetection(businessId, today.minusDays(LOOKBACK_DAYS), today);

        List<DetectedPattern> detected = RecurringPatternDetector.detect(transactions, today);

        int created = 0;
        for (DetectedPattern d : detected) {
            var existing = patterns.findByBusinessIdAndAccountIdAndFlowTypeAndName(
                    businessId, d.accountId(), d.flowType(), d.name());
            RecurringPattern pattern;
            if (existing.isPresent()) {
                pattern = existing.get();
                pattern.refreshIfCandidate(d);
            } else {
                pattern = patterns.save(RecurringPattern.candidate(businessId, d));
                created++;
            }
            linkSamples(pattern, d);
        }
        log.info("반복 패턴 탐지 — 사업장 {} / 거래 {}건 / 후보 {}건(신규 {})",
                businessId, transactions.size(), detected.size(), created);

        // <b>「분류 뒤 다시 탐지하라」가 참이 되는 자리다.</b> 위 갱신은 후보만 건드리므로
        // 이미 승인한 패턴의 빈 분류는 그대로 남는다 — 시나리오가 경고로 시키는 그 일을
        // 해도 아무것도 달라지지 않는 상태였다. 채울 것이 없으면 조회 한 번으로 끝난다.
        backfillCategories(businessId, today);
        syncCandidateAlert(businessId);
        return created;
    }

    /**
     * 패턴이 설명하는 과거 거래를 적어 둔다 (S15P21C105-151).
     *
     * <p><b>잔여 집계가 뺄 대상이다.</b> 예정 거래는 앞으로만 만들어지므로 과거에는 뺄 것이
     * 없고, 그대로 두면 갓 승인한 패턴의 과거 거래가 잔여와 예정 투영에 <b>두 번</b> 들어간다
     * (설계노트 M-1). 탐지기가 이미 표본 id 를 주는데 아무도 저장하지 않고 있었다.
     *
     * <p><b>갈아끼운다.</b> 재탐지하면 표본이 달라질 수 있고, 옛 연결이 남으면 이제는 패턴이
     * 설명하지 않는 거래가 잔여에서 빠진다 — 곡선이 반대로 기운다.
     *
     * <p>상태를 보지 않는 이유는 <b>연결이 사실이기 때문</b>이다. 후보든 확정이든 「이 거래로
     * 이 패턴을 만들었다」는 변하지 않는다. 빼도 되는지는 읽는 쪽이 정한다 —
     * {@code ForecastRunService.loadResidual} 이 확정된 패턴만 고른다.
     */
    private void linkSamples(RecurringPattern pattern, DetectedPattern d) {
        patternTransactions.deleteLinksOf(pattern.getId());
        if (d.sampleTransactionIds().isEmpty()) {
            return;
        }
        List<RecurringPatternTransaction> links = new ArrayList<>(d.sampleTransactionIds().size());
        for (Long transactionId : d.sampleTransactionIds()) {
            links.add(RecurringPatternTransaction.of(pattern.getId(), transactionId, d.confidence()));
        }
        patternTransactions.saveAll(links);
    }

    /**
     * 거래 분류가 뒤늦게 돈 뒤, 패턴의 <b>빈 분류를 근거 거래에서 채운다</b>.
     *
     * <p><b>왜 필요한가.</b> 패턴의 분류는 탐지가 표본 거래에서 복사한다. 분류 Job 은 수집
     * 직후 비동기로 돌고 탐지는 사용자가 누르므로, 분류가 끝나기 전에 탐지가 돌면 분류가 빈
     * 패턴이 남는다. 그 패턴이 만든 예정 입금도 분류가 비고, 시나리오의 매출 변화가 대상을
     * 한 건도 찾지 못한다(11.1). 재탐지는 후보만 갱신하고({@code refreshIfCandidate}) 승인·수정
     * 요청에 분류를 실어 보내는 화면이 없어, <b>승인된 패턴에는 이 경로 말고 채울 길이 없다.</b>
     *
     * <p><b>근거는 패턴 자신의 표본 거래다.</b> 재탐지를 다시 돌려 분류만 빼 오지 않는 이유는
     * 둘이다 — 탐지는 1년치를 다시 훑는 무거운 일이고, 그 사이 표본이 달라지면 <b>분류를
     * 채우려다 금액·주기까지 흔든다.</b> 여기서 하는 일은 빈칸 하나를 메우는 것뿐이다.
     *
     * <p><b>표가 갈리면 많은 쪽을 쓴다.</b> 탐지가 분류별로 그룹을 나누므로 한 패턴의 표본은
     * 원래 같은 분류였고, 갈렸다면 사용자가 일부만 정정한 것이다. 소수 쪽으로 패턴 전체를
     * 끌고 가지 않는다.
     *
     * <p><b>확정된 패턴은 예정 거래를 다시 만든다.</b> 예정 거래는 만들 때 패턴의 분류를
     * 복사해 두므로, 다시 만들지 않으면 패턴만 고쳐지고 <b>곡선 쪽에서는 아무것도 달라지지
     * 않는다</b> — {@link #update} 가 분류 변경을 {@code REBUILD} 로 보는 이유와 같다.
     *
     * <p><b>{@code REQUIRES_NEW} 여야 한다.</b> 부르는 쪽이
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 라, 그 시점에는 방금 커밋된 트랜잭션이
     * 아직 정리되지 않았다. 기본값({@code REQUIRED})으로 두면 그것에 합류해 여기서 쓴 것이
     * <b>오류 없이 사라진다</b> — {@code LoanPlannedGenerator.regenerate} 와 같은 자리다.
     * 탐지에서 부르는 경로는 자기 호출이라 프록시를 타지 않고 <b>그 트랜잭션에 그대로 남는다</b>
     * (탐지 결과와 분류 채우기가 함께 커밋돼야 한다).
     *
     * @return 분류를 채운 패턴 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int backfillCategories(Long businessId, LocalDate today) {
        List<RecurringPattern> blanks = patterns.findByBusinessIdAndCategoryIdIsNull(businessId);
        if (blanks.isEmpty()) {
            return 0;
        }
        List<RecurringPatternTransaction> links = patternTransactions.findByRecurringPatternIdIn(
                blanks.stream().map(RecurringPattern::getId).toList());
        if (links.isEmpty()) {
            // 탐지 전에 손으로 만든 패턴이거나 연결이 정리된 뒤다. 채울 근거가 없다.
            return 0;
        }
        Map<Long, Long> categoryByTransaction = history.categoryIdsOf(businessId,
                links.stream().map(RecurringPatternTransaction::getTransactionId).toList());

        Map<Long, Map<Long, Integer>> votes = new HashMap<>();
        for (RecurringPatternTransaction link : links) {
            Long categoryId = categoryByTransaction.get(link.getTransactionId());
            if (categoryId != null) {
                votes.computeIfAbsent(link.getRecurringPatternId(), key -> new HashMap<>())
                        .merge(categoryId, 1, Integer::sum);
            }
        }

        List<Long> rebuild = new ArrayList<>();
        int filled = 0;
        for (RecurringPattern pattern : blanks) {
            if (!pattern.fillCategoryIfBlank(dominant(votes.get(pattern.getId())))) {
                continue;
            }
            filled++;
            // 중지·후보는 앞으로의 회차를 들고 있지 않다. 승인될 때 지금 채운 분류가 복사된다.
            if (pattern.getStatus() == PatternStatus.CONFIRMED) {
                rebuild.add(pattern.getId());
            }
        }
        if (filled == 0) {
            return 0;
        }

        for (Long patternId : rebuild) {
            // ⚠ 이 삭제가 영속성 컨텍스트를 비운다(flush 가 먼저 나가 위 분류 변경은 저장된다).
            //   그래서 패턴을 여기서 다시 읽는다 — {@link #update} 와 같은 주의다.
            planned.deleteRegenerableOf(patternId, today);
            generatePlanned(find(patternId), today);
        }
        if (!rebuild.isEmpty()) {
            events.publishEvent(ForecastInputsChanged.of(
                    businessId, ForecastInputsChanged.Reason.PATTERN_CONFIRMED));
        }
        log.info("패턴 분류 백필 — 사업장 {} / 채운 패턴 {}건 / 예정 거래 재생성 {}건",
                businessId, filled, rebuild.size());
        return filled;
    }

    /**
     * 표를 가장 많이 받은 분류. 없으면 {@code null} 이고, 그때는 채우지 않는다.
     *
     * <p>동률이면 id 가 작은 쪽을 쓴다 — 어느 쪽이 맞다고 볼 근거가 없으니 <b>적어도
     * 결정적으로</b> 고른다(탐지기가 같은 상황에서 쓰는 규칙과 같은 성격이다).
     */
    private static Long dominant(Map<Long, Integer> votes) {
        if (votes == null) {
            return null;
        }
        Long best = null;
        int bestCount = 0;
        for (Map.Entry<Long, Integer> vote : votes.entrySet()) {
            boolean wins = vote.getValue() > bestCount
                    || (vote.getValue() == bestCount && best != null && vote.getKey() < best);
            if (wins) {
                best = vote.getKey();
                bestCount = vote.getValue();
            }
        }
        return best;
    }

    /**
     * 승인하고 향후 90일치 예정 거래를 만든다 (API 9.3).
     *
     * <p>이 시점부터 패턴이 <b>예측에 들어간다</b> — 잔액 곡선이 이 예정 거래들을 더한다.
     */
    @Transactional
    public RecurringPattern confirm(Long patternId, Long userId, Long categoryId,
                                    java.math.BigDecimal amountMin, java.math.BigDecimal amountMax,
                                    HolidayRule rule, LocalDate today) {
        RecurringPattern pattern = find(patternId);
        pattern.confirm(userId, categoryId, amountMin, amountMax, rule);
        generatePlanned(pattern, today);
        // 이 시점부터 예정 거래가 곡선에 들어간다. 커밋 뒤 재계산을 예약한다(공통규약 2.6).
        events.publishEvent(ForecastInputsChanged.of(
                pattern.getBusinessId(), ForecastInputsChanged.Reason.PATTERN_CONFIRMED));
        syncCandidateAlert(pattern.getBusinessId());
        return pattern;
    }

    @Transactional
    public void dismiss(Long patternId) {
        RecurringPattern pattern = find(patternId);
        pattern.dismiss();
        syncCandidateAlert(pattern.getBusinessId());
    }

    /**
     * 수정 (API 9.3). <b>예정 거래를 여기서 다시 맞춘다.</b>
     *
     * <p>멈추면 앞으로의 회차를 걷어내고, 되돌리거나 금액·분류·휴일 규칙을 고치면 걷어낸 뒤
     * <b>다시 만든다.</b> 패턴만 바꾸고 예정 거래를 두면 「고쳤는데 곡선은 그대로」가 된다.
     *
     * <p><b>되돌리는 쪽이 비어 있었다.</b> 멈출 때만 움직이고 확정으로 되돌릴 때는 아무것도
     * 하지 않아서, 목록은 「확정」인데 예정 거래는 꺼진 채로 남았다. 게다가 확정된 패턴의 과거
     * 거래는 잔여에서 빠지므로({@code ForecastRunService.explainedByConfirmedPatterns}) 그 돈이
     * <b>예정과 잔여 양쪽에서 동시에 사라졌다</b> — 곡선이 통째로 한쪽으로 기운다
     * ({@code S15P21C105-180}).
     *
     * <p>걷어내는 범위는 좁다 — 사용자가 손댄 것과 이미 금융망에 나간 것은 남는다
     * ({@code deleteRegenerableOf} 의 술어). 남은 자리는 생성기가 비켜 간다.
     *
     * <p>⚠ <b>벌크 삭제 뒤에는 패턴을 다시 읽는다.</b> {@code clearAutomatically} 가 영속성
     * 컨텍스트를 통째로 비워 그 전에 읽은 패턴이 detach 되고, 거기에 닻을 써도 커밋에 UPDATE 가
     * 나가지 않는다({@link #refreshPlanned} 의 같은 주의). 상태·금액 변경 자체는
     * {@code flushAutomatically} 가 삭제보다 먼저 내보낸다.
     */
    @Transactional
    public RecurringPattern update(Long patternId, Long categoryId,
                                   java.math.BigDecimal amountMin, java.math.BigDecimal amountMax,
                                   HolidayRule rule, PatternStatus status, LocalDate today) {
        Effect effect = find(patternId).update(categoryId, amountMin, amountMax, rule, status);
        if (effect == Effect.NONE) {
            return find(patternId);
        }
        planned.deleteRegenerableOf(patternId, today);

        // 위 삭제가 컨텍스트를 비웠다. 여기서 읽은 것만 managed 라 닻이 저장된다.
        RecurringPattern pattern = find(patternId);
        if (effect == Effect.REBUILD) {
            generatePlanned(pattern, today);
        }
        events.publishEvent(ForecastInputsChanged.of(pattern.getBusinessId(),
                effect == Effect.PAUSED
                        ? ForecastInputsChanged.Reason.PATTERN_PAUSED
                        : ForecastInputsChanged.Reason.PATTERN_CONFIRMED));
        return pattern;
    }

    /**
     * <b>확정한 패턴의 미래 예정 거래를 다시 만든다</b> — 영업일 달력이 바뀌었을 때.
     *
     * <p>휴무일만큼 정산액을 줄이는 것은 {@link #generatePlanned} 가 만들 때 계산한다
     * ({@code settlementRatio}). 그래서 나중에 달력을 고치면 <b>이미 만들어진 예정 거래는
     * 옛 금액 그대로다</b> — 사장님이 「추석에 쉰다」를 등록했는데 곡선이 안 움직인다.
     *
     * <p>지우는 범위가 좁다 ({@code deleteRegenerable}) — 사용자가 손댄 것과 이미 매칭된
     * 것, 그리고 <b>이미 금융망에 반영된 것</b>은 남는다.
     *
     * <p>⚠ <b>벌크 삭제 뒤에는 패턴을 다시 읽어야 한다.</b> {@code deleteRegenerable} 이
     * {@code clearAutomatically = true} 라 영속성 컨텍스트를 통째로 비운다 — 그 전에 읽어 둔
     * 패턴은 detach 되고, 거기에 {@code advanceNextExpectedDate} 를 써도 <b>커밋에 UPDATE 가
     * 나가지 않는다.</b> 예정 거래는 멀쩡히 다시 만들어져서 그 자리에서는 아무 일도 안 일어나고,
     * <b>다음 재생성이 옛 위상에서 시작</b>할 때에야 드러난다({@code RecurringPatternRefreshTests}).
     *
     * @return 다시 만든 패턴 수
     */
    @Transactional
    public int refreshPlanned(Long businessId, LocalDate today) {
        if (patterns.findByBusinessIdAndStatusOrderByConfidenceDesc(
                businessId, PatternStatus.CONFIRMED).isEmpty()) {
            return 0;
        }
        planned.deleteRegenerable(businessId, today);

        // 위 삭제가 컨텍스트를 비웠다. 여기서 읽은 것만 managed 라 닻이 저장된다.
        List<RecurringPattern> confirmed =
                patterns.findByBusinessIdAndStatusOrderByConfidenceDesc(businessId, PatternStatus.CONFIRMED);
        for (RecurringPattern pattern : confirmed) {
            generatePlanned(pattern, today);
        }
        events.publishEvent(ForecastInputsChanged.of(
                businessId, ForecastInputsChanged.Reason.PATTERN_CONFIRMED));
        return confirmed.size();
    }

    public RecurringPattern find(Long patternId) {
        return patterns.findById(patternId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATTERN_NOT_FOUND));
    }

    /**
     * 향후 90일치 예정 거래를 만든다.
     *
     * <p><b>오늘보다 앞은 만들지 않는다.</b> 닻({@code nextExpectedDate})은 첫 회차에 머무르므로
     * 시간이 지나면 과거에 있다. 그대로 세면 지난 회차를 다시 만들어 — 같은 날짜에 행이 겹치고,
     * {@code MAX_GENERATED} 를 과거가 먹어 미래 90일이 잘리고, 무엇보다 <b>자동 반영의 따라잡기
     * 창(API 9.6)에 들어가 이미 지난 회차의 돈이 나간다.</b> 그 회차는 취소됐던 것이라 원장이
     * 없고, {@code uk_pe_pattern_due} 도 막지 못한다.
     *
     * <p><b>이미 자리가 있는 날짜는 비켜 간다.</b> 지우지 않고 남은 행은 사용자가 끈 회차이거나
     * 이미 일어난 것이거나 금융망에 나간 것이다({@code findOccupiedDates}). 거기에 하나 더
     * 만들면 <b>사장님이 끈 회차가 되살아나고</b> 나간 회차는 곡선에 두 번 들어간다.
     *
     * <p>닻은 <b>회차가 있었으면 그 자리로</b> 옮긴다 — 자리가 차서 안 만든 회차도 회차다.
     * 만들었느냐로 닻을 정하면 사용자가 하나 끌 때마다 위상이 한 회차씩 밀린다.
     */
    private void generatePlanned(RecurringPattern pattern, LocalDate today) {
        LocalDate limit = today.plusDays(HORIZON_DAYS);
        LocalDate start = firstAnchorFrom(pattern, today);
        LocalDate date = start;
        Set<LocalDate> occupied = planned.findOccupiedDates(pattern.getId(), today);

        var byWeekday = pattern.getAmountByWeekday();

        List<PlannedTransaction> batch = new ArrayList<>();
        LocalDate firstAnchor = null;
        int guard = 0;
        while (!date.isAfter(limit) && guard++ < MAX_GENERATED) {
            LocalDate due = dueDate(pattern, date);
            // 보정이 날짜를 앞으로 당길 수 있다(PREVIOUS_BUSINESS_DAY) — 기준일이 오늘이어도
            // 돈이 오가는 날은 지난주일 수 있으므로 보정한 날짜로 다시 잰다.
            if (due != null && !due.isAfter(limit) && !due.isBefore(today)) {
                if (firstAnchor == null) {
                    firstAnchor = date;
                }
                if (occupied.contains(due)) {
                    date = nextDate(pattern, date);
                    continue;
                }
                // 요일별 금액이 있으면 그날 요일 값을 쓴다. 없으면 지금까지처럼 패턴의 min/max 다.
                var w = byWeekday == null ? null : byWeekday.get(due.getDayOfWeek().getValue());
                BigDecimal open = settlementRatio(pattern, date);
                batch.add(w != null
                        ? PlannedTransaction.fromPattern(
                                pattern.getBusinessId(), pattern.getId(), pattern.getName(),
                                due, date, pattern.getFlowType(),
                                scale(w.lo(), open), scale(w.mid(), open), scale(w.hi(), open),
                                pattern.getAccountId(), pattern.getCategoryId())
                        : PlannedTransaction.fromPattern(
                                pattern.getBusinessId(), pattern.getId(), pattern.getName(),
                                due, date, pattern.getFlowType(),
                                scale(pattern.getExpectedAmountMin(), open),
                                scale(pattern.expectedAmount(), open),
                                scale(pattern.getExpectedAmountMax(), open),
                                pattern.getAccountId(), pattern.getCategoryId()));
            }
            date = nextDate(pattern, date);
        }
        planned.saveAll(batch);
        // 보정 전 날짜를 남긴다. 보정한 날짜를 저장하면 다음 회차가 그 위에서 또 밀려 <b>주기가
        // 조금씩 흘러간다</b> — 임차료 5일이 한 번 일요일이었다고 이후로 6일이 되면 안 된다.
        // 창 안에 회차가 하나도 없으면(분기 패턴은 90일을 건너뛸 수 있다) 굴려 둔 자리를 남긴다.
        // null 로 지우면 다음 생성이 오늘부터 세어 위상을 잃는다.
        pattern.advanceNextExpectedDate(firstAnchor == null ? start : firstAnchor);
        log.info("패턴 {} — 예정 거래 {}건 생성 (닻 {})",
                pattern.getId(), batch.size(), pattern.getNextExpectedDate());
    }

    /**
     * 세기 시작할 날 — <b>오늘 이후 첫 회차</b>.
     *
     * <p>닻을 통째로 오늘로 당기지 않고 <b>회차 단위로만 굴린다.</b> 닻은 주기의 위상이라
     * 임차료 5일은 굴려도 5일이어야 한다.
     *
     * <p><b>월 반복은 출발선부터 기준일로 맞춘다</b> (S15P21C105-187). 닻이 짧은 달에서 잘린
     * 채 저장돼 있을 수 있어서다 — 30일 패턴의 닻이 28일에 주저앉아 있으면 거기서 굴려도
     * 28일만 나온다. {@code MONTHLY} 에서 진짜는 {@code dayOfMonth} 이고 닻은 파생이다.
     *
     * <p>그래서 일정이 실제로 옮겨간 것은 {@code dayOfMonth} 에도 적혀야 한다 — 매칭 결과로
     * 닻을 다시 잡는 {@code PlannedMatchService.reanchor} 가 그 자리다. 닻만 밀고 기준일을
     * 두면 여기서 도로 끌어당긴다.
     */
    static LocalDate firstAnchorFrom(RecurringPattern pattern, LocalDate today) {
        LocalDate date = pattern.getNextExpectedDate() == null
                ? today : pattern.getNextExpectedDate();
        if (pattern.getCadenceType() == CadenceType.MONTHLY) {
            date = RecurringPatternDetector.onDayOfMonth(date, pattern.getDayOfMonth());
        }
        int guard = 0;
        while (date.isBefore(today) && guard++ < MAX_ROLL_FORWARD) {
            date = nextDate(pattern, date);
        }
        return date.isBefore(today) ? today : date;
    }

    /**
     * <b>휴무일만큼 정산액을 줄인다.</b>
     *
     * <p>카드·배달·현금 정산은 그 기간의 매출을 모아서 들어온다. 명절에 사흘 닫으면 그 주
     * 정산이 그만큼 적은데, 지금 투영은 매 회차 같은 금액을 찍어 곡선을 위로 띄운다.
     * 실측(시드 「카페 한잔」)에서 예정 입금 과대평가 331,850원 중 <b>253,050원(76%)</b>이
     * 추석 주 두 건이었다.
     *
     * <p><b>매출성 입금에만 건다</b> — {@code DEPOSIT} 이면서 {@code WEEKLY}·{@code INTERVAL}.
     * 고정비는 가게가 쉬어도 그대로 나가고, 월 단위 입금은 임대수입처럼 매출과 무관한 것이
     * 섞이기 쉽다. {@code BUSINESS_DAILY} 는 회차가 하루라 비율이 의미를 잃고, 휴일에는
     * 이미 {@link #dueDate} 가 회차를 안 만든다.
     *
     * <p>세는 구간은 <b>직전 회차부터 이번 회차까지</b>다 — 그 기간 매출이 이번 정산에 담긴다.
     *
     * <p>달력이 비어 있으면 1.0 이라 아무것도 바뀌지 않는다.
     */
    private BigDecimal settlementRatio(RecurringPattern pattern, LocalDate anchor) {
        if (pattern.getFlowType() != FlowType.DEPOSIT) {
            return BigDecimal.ONE;
        }
        LocalDate previous = switch (pattern.getCadenceType()) {
            case WEEKLY -> anchor.minusWeeks(1);
            case INTERVAL -> anchor.minusDays(
                    pattern.getIntervalValue() == null ? 1 : pattern.getIntervalValue());
            default -> null;
        };
        return previous == null ? BigDecimal.ONE : openDays.of(pattern.getBusinessId(), previous, anchor);
    }

    private static BigDecimal scale(BigDecimal amount, BigDecimal ratio) {
        return ratio.compareTo(BigDecimal.ONE) == 0
                ? amount
                : amount.multiply(ratio).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 실제로 돈이 오가는 날 — <b>공휴일 보정을 여기서 한다</b> (P1-04 · API 9.2).
     *
     * <p>{@code holidayRule} 은 지금까지 저장만 되고 <b>한 번도 쓰이지 않았다.</b> 그래서
     * 예정 거래의 두 날짜가 늘 같았다. 자동이체는 휴일이면 다음 영업일로 밀리고
     * ({@code NEXT_BUSINESS_DAY}), 급여처럼 앞당기는 것도 있다.
     *
     * <p>{@code BUSINESS_DAILY} 는 보정하지 않고 <b>건너뛴다</b>({@code null}). 「영업일마다」가
     * 주기의 정의라 공휴일에는 회차 자체가 없다 — 카드사가 그날 정산하지 않는다.
     *
     * <p>⚠ <b>이월은 하지 않는다.</b> 실제로는 공휴일 매출이 다음 영업일 정산에 얹혀 그날이
     * 그만큼 커지는데, 그러려면 <b>그날 가게가 열었는지</b>를 알아야 한다. 명절에 닫는 가게와
     * 여는 가게가 다르고 백엔드는 그 정보를 갖고 있지 않다. 추측해서 넣으면 오차를 다른
     * 오차로 바꾸는 것이라, 지금은 놓인 자리만 바로잡는다.
     */
    private static LocalDate dueDate(RecurringPattern pattern, LocalDate anchor) {
        if (pattern.getCadenceType() == CadenceType.BUSINESS_DAILY) {
            return BusinessCalendar.isBusinessDay(anchor) ? anchor : null;
        }
        HolidayRule rule = pattern.getHolidayRule();
        if (rule == null || rule == HolidayRule.NONE || BusinessCalendar.isBusinessDay(anchor)) {
            return anchor;
        }
        return rule == HolidayRule.PREVIOUS_BUSINESS_DAY
                ? BusinessCalendar.previousBusinessDay(anchor)
                : BusinessCalendar.nextBusinessDay(anchor);
    }

    /**
     * 다음 회차의 <b>기준일</b>. 공휴일 보정은 {@link #dueDate} 가 따로 한다 — 기준일을 밀면
     * 주기가 흘러간다.
     *
     * <p>{@code BUSINESS_DAILY} 만 여기서 건너뛴다. 그건 보정이 아니라 <b>주기의 정의</b>라
     * ({@code 영업일마다}) 다음 회차 자체가 공휴일에 없다.
     *
     * <p><b>{@code MONTHLY} 는 직전 회차가 아니라 기준일에서 만든다</b> (S15P21C105-187).
     * {@code plusMonths} 만 하면 2월에 잘린 날짜에서 다음 달을 세게 돼 30일 패턴이 28일로
     * 주저앉고 돌아오지 않는다.
     *
     * <p>패키지 밖으로 열지 않되 {@code private} 도 아니다 — 순수 함수라 Docker 없이 굳힌다
     * ({@code RecurringPatternDateTests}).
     */
    static LocalDate nextDate(RecurringPattern p, LocalDate from) {
        return switch (p.getCadenceType()) {
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> RecurringPatternDetector.onDayOfMonth(from.plusMonths(1), p.getDayOfMonth());
            case INTERVAL -> from.plusDays(p.getIntervalValue() == null ? 1 : p.getIntervalValue());
            case BUSINESS_DAILY -> BusinessCalendar.nextBusinessDay(from.plusDays(1));
        };
    }
}
