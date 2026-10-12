package com.jangoyebo.forecast.run;

import com.jangoyebo.core.SafetyLineChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 안전선이 바뀌면 하회 알림을 다시 맞춘다 (API 12.1).
 *
 * <p><b>{@link ForecastTriggerListener} 와 따로 둔 이유.</b> 저쪽이 받는 사건은 전부
 * 「곡선이 낡았다」이고 하는 일이 재계산 예약 하나다. 안전선 변경은 <b>곡선을 낡게 하지
 * 않는다</b>(공통규약 2.6) — 같은 클래스에 섞으면 다음 사람이 재계산 트리거로 읽는다.
 *
 * <p><b>{@code AFTER_COMMIT} 인 이유.</b> 커밋 전에 맞추면 아직 저장되지 않은 안전선을 읽어
 * 옛 기준으로 알림을 만든다. 발행 쪽이 롤백되면 이벤트도 함께 사라져야 한다.
 *
 * <p><b>여기서 곡선을 계산하지 않는다.</b> 하는 일은 일별 행 30개를 읽고 알림을 upsert 하는
 * 것뿐이라 발행 스레드(안전선 저장 요청)에서 그대로 돌아도 부담이 없다.
 *
 * <p><b>예외를 밖으로 내지 않는다.</b> {@code AFTER_COMMIT} 리스너가 던지면 이미 커밋된
 * 트랜잭션은 되돌아가지 않으면서 호출자만 실패한다 — <b>안전선은 저장됐는데 사용자에게는
 * 실패로 보이는</b> 상태가 된다. 알림이 한 박자 늦는 것은 그 정도 값어치가 없고,
 * 04:00 배치가 회수한다.
 */
@Component
public class SafetyLineTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(SafetyLineTriggerListener.class);

    private final ForecastRunService runService;

    public SafetyLineTriggerListener(ForecastRunService runService) {
        this.runService = runService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(SafetyLineChanged event) {
        if (event.businessId() == null) {
            return;
        }
        try {
            runService.resyncSafetyLineAlerts(event.businessId());
        } catch (RuntimeException e) {
            log.warn("안전선 하회 알림을 다시 맞추지 못했다 — 사업장 {}: {} (04:00 배치가 회수한다)",
                    event.businessId(), e.toString());
        }
    }
}
