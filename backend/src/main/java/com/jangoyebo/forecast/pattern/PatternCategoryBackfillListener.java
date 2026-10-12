package com.jangoyebo.forecast.pattern;

import com.jangoyebo.core.TransactionsClassified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 거래 분류가 끝나면 반복 패턴의 빈 분류를 채운다
 * ({@link RecurringPatternService#backfillCategories}).
 *
 * <p><b>왜 자동인가.</b> 분류 Job 은 수집 직후 비동기로 돌고 탐지는 사용자가 누른다. 순서가
 * 뒤집히면 분류가 빈 패턴이 남고, 그 패턴이 만든 예정 입금은 시나리오의 매출 변화에서
 * 빠진다(11.1). 사용자에게는 「거래는 카드 정산인데 매출 변화가 안 먹는다」로만 보이고,
 * 화면에는 패턴의 분류를 고칠 자리가 없어 <b>스스로 빠져나올 수 없는 상태</b>다.
 *
 * <p><b>{@code AFTER_COMMIT} 인 이유.</b> 커밋 전에 읽으면 방금 붙인 분류가 아직 안 보인다 —
 * 채울 것이 없다고 판단하고 그냥 끝난다. {@code ForecastTriggerListener} 와 같은 판단이다.
 *
 * <p><b>예외를 밖으로 내지 않는다.</b> {@code AFTER_COMMIT} 리스너가 던지면 이미 커밋된
 * 분류는 되돌아가지 않으면서 호출자(워커 콜백·PATCH)만 실패한다. 백필은 미뤄도 되는 일이고,
 * 다음 분류나 재탐지가 같은 자리를 다시 지난다.
 */
@Component
public class PatternCategoryBackfillListener {

    private static final Logger log = LoggerFactory.getLogger(PatternCategoryBackfillListener.class);

    private final RecurringPatternService service;
    private final Clock clock;

    public PatternCategoryBackfillListener(RecurringPatternService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /**
     * <b>여는 트랜잭션은 {@link RecurringPatternService#backfillCategories} 쪽에 있다.</b>
     * {@code AFTER_COMMIT} 콜백은 방금 커밋된 트랜잭션이 아직 정리되지 않은 자리에서 돌아,
     * 기본값({@code REQUIRED})이면 쓴 것이 <b>오류 없이 사라진다.</b> 그 자리를 여기가 아니라
     * 서비스가 잡는 이유는 아래 {@code try/catch} 가 <b>커밋 실패까지</b> 감싸야 하기
     * 때문이다 — 리스너에 걸면 커밋이 프록시 경계에서 일어나 예외가 그 바깥으로 샌다.
     *
     * <p>{@code fallbackExecution = true} — 트랜잭션 없이 발행되는 경로가 생겨도 조용히 버려지지
     * 않게 한다. 빈 분류만 채우므로 한 번 더 도는 쪽이 안 도는 쪽보다 낫다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(TransactionsClassified event) {
        if (event.businessId() == null) {
            return;
        }
        try {
            service.backfillCategories(event.businessId(), LocalDate.now(clock));
        } catch (RuntimeException e) {
            log.warn("패턴 분류 백필에 실패했다 — 사업장 {}: {}", event.businessId(), e.toString());
        }
    }
}
