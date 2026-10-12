package com.jangoyebo.core.settlement;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.alert.AlertService;
import com.jangoyebo.core.alert.AlertType;
import com.jangoyebo.core.transaction.Transaction;
import com.jangoyebo.core.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 정산 대사 계산과 알림 (API 14.1, P0-11).
 *
 * <p>계산은 단순하다 — {@code 예상 정산액 = 총매출 − 취소 − 수수료}. 어려운 쪽은
 * <b>매칭 거래가 정말 이 사업장의 입금인지</b>와 <b>알림을 몇 개 만드는지</b>다.
 */
@Service
public class SettlementService {

    private final SettlementRepository settlements;
    private final SettlementTransactionRepository links;
    private final TransactionRepository transactions;
    private final AlertService alerts;

    public SettlementService(SettlementRepository settlements,
                             SettlementTransactionRepository links,
                             TransactionRepository transactions,
                             AlertService alerts) {
        this.settlements = settlements;
        this.links = links;
        this.transactions = transactions;
        this.alerts = alerts;
    }

    @Transactional
    public Settlement create(Long businessId, String platformName,
                             java.time.LocalDate from, java.time.LocalDate to,
                             BigDecimal grossSales, BigDecimal cancellations, BigDecimal fees,
                             List<Long> matchedTransactionIds) {
        Settlement settlement = settlements.save(Settlement.of(
                businessId, platformName, from, to, grossSales, cancellations, fees));
        replaceMatches(settlement, matchedTransactionIds);
        return settlement;
    }

    @Transactional
    public Settlement update(Settlement settlement, String platformName,
                             java.time.LocalDate from, java.time.LocalDate to,
                             BigDecimal grossSales, BigDecimal cancellations, BigDecimal fees,
                             List<Long> matchedTransactionIds) {
        settlement.update(platformName, from, to, grossSales, cancellations, fees);
        // 매칭 목록을 안 보냈으면 그대로 두고 금액 변경분만 반영한다.
        replaceMatches(settlement, matchedTransactionIds != null
                ? matchedTransactionIds
                : links.findBySettlementId(settlement.getId()).stream()
                        .map(SettlementTransaction::getTransactionId).toList());
        return settlement;
    }

    @Transactional
    public void delete(Settlement settlement) {
        // 대사를 지우면 그 대사가 만든 알림도 근거가 사라진다.
        alerts.resolve(settlement.getBusinessId(), AlertType.SETTLEMENT_MISMATCH,
                dedupKey(settlement.getId()));
        settlements.delete(settlement);
    }

    /** 매칭된 입금 합계. 대사 상세·목록 응답이 이 값을 쓴다. */
    @Transactional(readOnly = true)
    public BigDecimal actualDeposit(Long settlementId) {
        return links.findBySettlementId(settlementId).stream()
                .map(SettlementTransaction::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 매칭을 갈아 끼우고 상태를 다시 판정한다.
     *
     * <p><b>전량 교체다.</b> 부분 추가·삭제 API 를 두면 프론트가 두 목록의 차이를 계산해야 하고,
     * 그 계산이 틀리면 합계가 조용히 어긋난다. 명세도 {@code matchedTransactionIds} 배열
     * 하나만 받는다(API 14.1).
     */
    private void replaceMatches(Settlement settlement, List<Long> transactionIds) {
        links.deleteBySettlementId(settlement.getId());

        BigDecimal total = BigDecimal.ZERO;
        if (transactionIds != null) {
            for (Long transactionId : transactionIds.stream().distinct().toList()) {
                Transaction tx = transactions.findById(transactionId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND,
                                "매칭할 거래를 찾을 수 없습니다: " + transactionId));

                // 남의 사업장 거래를 정산에 붙이면 그 사업장의 입금액이 이쪽 대사에 섞인다.
                if (!tx.getBusinessId().equals(settlement.getBusinessId())) {
                    throw new BusinessException(ErrorCode.BUSINESS_ACCESS_DENIED);
                }
                // 출금을 정산 입금으로 매칭하면 부호가 뒤집혀 차액이 두 배로 벌어진다.
                if (tx.getFlowType() != FlowType.DEPOSIT) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "정산에 매칭할 수 있는 것은 입금 거래뿐입니다: " + transactionId);
                }

                links.save(SettlementTransaction.of(
                        settlement.getId(), transactionId, tx.getAmount()));
                total = total.add(tx.getAmount());
            }
        }

        SettlementStatus status = settlement.recalculate(
                total, transactionIds != null && !transactionIds.isEmpty());
        syncAlert(settlement, total, status);
    }

    /**
     * 불일치면 알림을 올리고, 해소되면 내린다.
     *
     * <p><b>내리는 쪽이 더 중요하다.</b> 금액을 고쳐 차이가 0 이 됐는데 알림이 남아 있으면
     * 해결한 것이 계속 경고로 뜨고, 사용자는 알림을 신뢰하지 않게 된다.
     *
     * <p>{@code dedupKey} 를 대사 id 로 두어 <b>같은 대사를 세 번 고쳐도 알림은 하나</b>다
     * (API 10.3 — 동일 원인 알림은 중복 생성하지 않고 갱신한다).
     */
    private void syncAlert(Settlement settlement, BigDecimal actualDeposit, SettlementStatus status) {
        String key = dedupKey(settlement.getId());
        if (status != SettlementStatus.MISMATCHED) {
            alerts.resolve(settlement.getBusinessId(), AlertType.SETTLEMENT_MISMATCH, key);
            return;
        }

        BigDecimal diff = settlement.difference(actualDeposit);
        String direction = diff.signum() < 0 ? "덜 들어왔습니다" : "더 들어왔습니다";

        alerts.raise(settlement.getBusinessId(), AlertType.SETTLEMENT_MISMATCH, key,
                "%s 정산 금액이 예상과 다릅니다".formatted(settlement.getPlatformName()),
                // 도메인 규칙 1 — 원인을 단정하지 않고 확인할 항목을 안내한다(API 14.1).
                "%s ~ %s 정산에서 예상 %s원 대비 %s원이 %s. 정산 지연·누락·추가 차감을 확인해 보세요."
                        .formatted(settlement.getSalesFrom(), settlement.getSalesTo(),
                                money(settlement.expectedPayout()), money(diff.abs()), direction),
                Map.of("settlementId", settlement.getId()),
                Map.of("expectedPayout", settlement.expectedPayout(),
                        "actualDeposit", actualDeposit,
                        "difference", diff));
    }

    private static String dedupKey(Long settlementId) {
        return "settlement:" + settlementId;
    }

    /** 금액 표기는 `1,340,000원` 형태다(CLAUDE.md 코드 컨벤션). */
    private static String money(BigDecimal amount) {
        return NumberFormat.getInstance(Locale.KOREA).format(amount.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    /**
     * 배분이 빠진 대사의 상태를 다시 판정한다 (계좌 삭제 · API 7.2 purge).
     *
     * <p>계좌를 지우면 그 계좌 입금이 배분돼 있던 대사에서 배분만 사라진다. 대사 자체는
     * 사업장 것이라 남는데, 그대로 두면 <b>{@code MATCHED} 인데 매칭이 하나도 없는</b>
     * 대사가 생긴다 — 「맞춰 봤고 맞았다」고 계속 말하지만 근거가 없다.
     *
     * <p>{@code recalculate} 를 그대로 쓴다. 남은 배분이 없으면 {@code PENDING} 으로,
     * 일부가 남았으면 그 합계로 다시 판정한다({@code Settlement#recalculate} — 매칭이 없을 때
     * {@code MISMATCHED} 로 만들지 않는 이유가 여기서도 그대로 통한다: 아직 입금 전인 정상
     * 상태와 구별되지 않는다).
     *
     * <p>알림도 함께 맞춘다. 불일치가 해소됐는데 경고가 남아 있으면 사용자는 알림을
     * 신뢰하지 않게 된다({@link #syncAlert} 주석).
     */
    @Transactional
    public void refreshAfterAllocationsRemoved(Collection<Long> settlementIds) {
        for (Long settlementId : settlementIds) {
            settlements.findById(settlementId).ifPresent(settlement -> {
                List<SettlementTransaction> remaining = links.findBySettlementId(settlementId);
                BigDecimal total = remaining.stream()
                        .map(SettlementTransaction::getAllocatedAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                SettlementStatus status = settlement.recalculate(total, !remaining.isEmpty());
                syncAlert(settlement, total, status);
            });
        }
    }
}
