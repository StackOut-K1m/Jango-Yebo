package com.jangoyebo.forecast.run;

import com.jangoyebo.core.TransactionsChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 곡선을 낡게 만드는 사건을 한 곳에서 받는다 (공통규약 2.6 실행 트리거).
 *
 * <p><b>{@code AFTER_COMMIT} 인 이유.</b> 커밋 전에 재계산이 돌면 아직 없는 거래를 읽는다.
 * 발행 쪽이 롤백되면 이벤트도 함께 사라져야 한다.
 *
 * <p><b>{@code fallbackExecution = true} 인 이유.</b> 트랜잭션 없이 발행되는 경로가 있다 —
 * 지금은 없지만, 없으면 그런 발행이 <b>조용히 버려진다.</b> 재계산은 멱등이라 한 번 더 도는
 * 쪽이 안 도는 쪽보다 낫다.
 *
 * <p><b>여기서 계산하지 않는다.</b> 하는 일은 Redis 표시와 5초 예약뿐이라 수 밀리초다.
 * 발행 스레드(웹 요청·Job 워커)에서 그대로 돌아도 부담이 없고, 실제 계산은 forecast 전용
 * 스레드가 한다. {@code @EnableAsync} 를 켜지 않는 이유이기도 하다.
 *
 * <p><b>예외를 밖으로 내지 않는다.</b> {@code AFTER_COMMIT} 리스너가 던지면 이미 커밋된
 * 트랜잭션은 되돌아가지 않으면서 호출자만 실패한다 — <b>동기화는 끝났는데 사용자에게는
 * 실패로 보이는</b> 상태가 된다. 재계산 예약 실패는 그 정도 값어치가 없다. 04:00 배치가
 * 회수한다.
 */
@Component
public class ForecastTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(ForecastTriggerListener.class);

    private final ForecastRunWorker worker;

    public ForecastTriggerListener(ForecastRunWorker worker) {
        this.worker = worker;
    }

    /** 거래가 늘었다 — 동기화·CSV. 잔여 집계와 시작 잔액이 함께 바뀐다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(TransactionsChanged event) {
        request(event.businessId(), "거래 변경(" + event.source() + ")");
    }

    /** 예정 거래 원장이 바뀌었다 — 패턴 승인·중지, 예정 거래 CUD. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ForecastInputsChanged event) {
        request(event.businessId(), "예측 입력 변경(" + event.reason() + ")");
    }

    private void request(Long businessId, String why) {
        if (businessId == null) {
            return;
        }
        try {
            worker.requestRecalculation(businessId);
            log.debug("예측 재계산 예약 — 사업장 {} / {}", businessId, why);
        } catch (RuntimeException e) {
            log.warn("예측 재계산을 예약하지 못했다 — 사업장 {} / {}: {} (04:00 배치가 회수한다)",
                    businessId, why, e.toString());
        }
    }
}
