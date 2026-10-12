package com.jangoyebo.forecast.pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 패턴이 어떤 과거 거래로 만들어졌나 (ERD {@code RECURRING_PATTERN_TRANSACTION}).
 *
 * <h2>왜 저장하나 — 잔여에서 빼야 하기 때문이다</h2>
 *
 * <p>잔여의 정의는 {@code 실제 − 예정 거래에 매칭된 것}인데, 예정 거래는
 * {@code next_expected_date} 부터 <b>앞으로만</b> 만들어진다
 * ({@code RecurringPatternService.generatePlanned}). 그래서 <b>과거에는 뺄 대상이 없다</b> —
 * 갓 승인한 패턴의 과거 거래가 통째로 잔여에 남아 예정 투영과 <b>이중 계상</b>된다
 * (설계노트 M-1 · S15P21C105-151).
 *
 * <p>탐지기는 이미 답을 갖고 있다. 패턴을 만든 표본이 곧 「이 패턴이 설명하는 과거 거래」다.
 * 그것을 여기 적어 두면 잔여 집계가 뺄 수 있다.
 *
 * <p><b>확정된 패턴의 것만 뺀다.</b> 후보는 아직 곡선에 안 들어가므로 그 거래는 잔여에
 * 남아 있어야 한다 — 빼면 반대로 곡선이 아래로 기운다.
 *
 * <h2>덮는 범위</h2>
 *
 * <p>탐지 창이 180일이고 잔여 창은 365일이다. <b>180일보다 오래된 거래는 여기 없다.</b>
 * 그 구간은 여전히 이중 계상되며, 완전히 덮으려면 과거 회차를 만들어 매칭하는 쪽으로
 * 가야 한다. 지금은 0일 덮던 것이 180일이 된다.
 */
@Entity
@Table(name = "recurring_pattern_transaction", schema = "forecast")
public class RecurringPatternTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recurring_pattern_id", nullable = false)
    private Long recurringPatternId;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    /**
     * 이 거래를 패턴에 넣은 확신도.
     *
     * <p>탐지기는 표본마다 점수를 따로 내지 않는다 — 묶음 전체를 보고 한 번 판정한다.
     * 그래서 패턴의 신뢰도를 그대로 쓴다. 컬럼이 {@code NOT NULL} 이라 비워둘 수 없다.
     */
    @Column(name = "match_score", nullable = false)
    private BigDecimal matchScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RecurringPatternTransaction() {
    }

    public static RecurringPatternTransaction of(Long patternId, Long transactionId, BigDecimal score) {
        RecurringPatternTransaction link = new RecurringPatternTransaction();
        link.recurringPatternId = patternId;
        link.transactionId = transactionId;
        link.matchScore = score;
        link.createdAt = Instant.now();
        return link;
    }

    public Long getId() {
        return id;
    }

    public Long getRecurringPatternId() {
        return recurringPatternId;
    }

    public Long getTransactionId() {
        return transactionId;
    }
}
