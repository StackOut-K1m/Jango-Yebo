package com.jangoyebo.forecast.loan;

import com.jangoyebo.core.LoansChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 대출이 바뀌면 예정 거래를 다시 만든다 (P1-13).
 *
 * <p>{@code core} 는 {@code forecast} 를 참조할 수 없어({@code package-info} 의 단방향 규칙)
 * 동기화가 생성기를 직접 부를 수 없다. {@code TransactionClassificationTriggerListener} 와
 * 같은 이음매다.
 *
 * <p><b>커밋 뒤에 돈다.</b> 커밋 전이면 아직 안 보이는 대출로 회차를 만든다.
 *
 * <p>실패해도 <b>동기화를 되돌리지 않는다.</b> 대출은 저장됐고 회차는 파생이라, 다음 동기화나
 * 매일 04:00 배치가 같은 일을 다시 한다 — 여기서 던지면 커밋된 트랜잭션 밖이라 아무도 못 받고
 * 로그만 지저분해진다.
 */
@Component
public class LoanPlannedListener {

    private static final Logger log = LoggerFactory.getLogger(LoanPlannedListener.class);

    /** 금융망은 한국시간 벽시계로 상환한다. 서버 시간대에 기대지 않는다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LoanPlannedGenerator generator;

    public LoanPlannedListener(LoanPlannedGenerator generator) {
        this.generator = generator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(LoansChanged event) {
        try {
            generator.regenerate(event.businessId(), LocalDate.now(KST));
        } catch (RuntimeException e) {
            log.warn("사업장 {} 대출 예정 거래 재생성 실패 — 다음 동기화가 다시 한다: {}",
                    event.businessId(), e.toString());
        }
    }
}
