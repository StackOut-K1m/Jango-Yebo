package com.jangoyebo.core.ai;

import com.jangoyebo.core.TransactionsChanged;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.transaction.TransactionClassificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * 거래가 들어오면 분류 작업을 큐에 넣는다 (API 8.5 — "동기화·CSV 업로드 완료 시 자동 실행").
 *
 * <h2>기본값은 꺼짐이다</h2>
 *
 * <p>{@code jango.classification.auto-trigger} 가 켜져 있을 때만 넣는다. 거래가 들어올 때마다
 * LLM 을 부르면 <b>시드를 넣고 빼며 시험하는 동안 그것이 그대로 토큰</b>이 된다. 꺼 두면
 * 분류는 8.5 버튼으로만 돈다({@code POST /businesses/{id}/classification-jobs}) — 언제 값을
 * 치를지 사람이 정한다.
 *
 * <p>⚠ <b>이 스위치가 생기기 전에는 「켜져 있는데 안 도는」 상태였다.</b> 코드는 여기서 Job 을
 * 넣고 있었지만 {@code AFTER_COMMIT} 안에서 연 {@code REQUIRED} 트랜잭션이 <b>이미 커밋된
 * 트랜잭션에 합류</b>해 INSERT 가 예외도 로그도 없이 사라졌다({@link
 * JobService#enqueueIfIdleInNewTransaction} 에 자세히 적었다). 지금은 꺼져 있으면 <b>꺼져
 * 있어서</b> 안 돌고, 켜면 실제로 돈다 — 그 둘을 테스트가 양쪽으로 잡는다.
 *
 * <p><b>어떤 거래를 분류할지는 여기서 정하지 않는다.</b> 이벤트에 거래 id 가 없고, 핸들러가
 * 그때의 미분류 거래를 조회한다(합의 ④). 그래서 이 이벤트가 <b>유실돼도 손실이 없다</b> —
 * 다음 수집이나 8.5 재실행이 같은 거래를 그대로 주워간다. {@code TransactionsChanged} 가
 * 발행을 영속화하지 않는 전제가 분류에서도 유지된다.
 *
 * <p><b>{@code AFTER_COMMIT} 인 이유.</b> 커밋 전에 Job 이 뜨면 워커가 아직 없는 거래를
 * 조회한다. 발행 쪽이 롤백되면 Job 도 함께 사라져야 한다.
 *
 * <p><b>예외를 밖으로 내지 않는다.</b> {@code AFTER_COMMIT} 리스너가 던지면 이미 커밋된
 * 트랜잭션은 되돌아가지 않으면서 호출자만 실패한다 — <b>거래 수집은 끝났는데 사용자에게는
 * 실패로 보이는</b> 상태가 된다. 분류는 미뤄도 되는 일이다({@code ForecastTriggerListener}
 * 와 같은 판단).
 */
@Component
public class TransactionClassificationTriggerListener {

    private static final Logger log =
            LoggerFactory.getLogger(TransactionClassificationTriggerListener.class);

    private final JobService jobService;
    private final TransactionClassificationProperties properties;

    public TransactionClassificationTriggerListener(JobService jobService,
                                                    TransactionClassificationProperties properties) {
        this.jobService = jobService;
        this.properties = properties;
    }

    /**
     * {@code fallbackExecution = true} — 트랜잭션 없이 발행되는 경로가 생겨도 조용히 버려지지
     * 않게 한다. 가드가 중복을 막으므로 한 번 더 도는 쪽이 안 도는 쪽보다 낫다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(TransactionsChanged event) {
        if (event.source() == TransactionsChanged.Source.ACCOUNT_DISCONNECTED) {
            // 거래 행은 하나도 안 바뀌었다. 오히려 그 계좌의 거래는 이제 분류 대상에서
            // 빠지므로(TransactionRepository#findUnclassified) 분류할 것이 없는 Job 만 뜬다.
            return;
        }
        Long businessId = event.businessId();
        if (businessId == null) {
            return;
        }
        if (!properties.autoTrigger()) {
            // 끈 것이지 고장난 것이 아니다. 8.5 버튼이 같은 거래를 그대로 주워간다.
            log.debug("자동 분류가 꺼져 있다 — 사업장 {} / {} (jango.classification.auto-trigger)",
                    businessId, event.source());
            return;
        }
        try {
            // 새 트랜잭션에서 넣는다 — 이유는 그 메서드에 적었다. REQUIRED 로 부르면
            // 이 자리의 INSERT 가 조용히 사라져 자동 분류가 아예 돌지 않는다.
            jobService.enqueueIfIdleInNewTransaction(
                            businessId, null, JobType.TRANSACTION_CLASSIFICATION, Map.of())
                    .ifPresentOrElse(
                            job -> log.debug("거래 분류 작업 접수 — 사업장 {} / {} / jobId={}",
                                    businessId, event.source(), job.getId()),
                            () -> log.debug("거래 분류 작업이 이미 돌고 있다 — 사업장 {} / {}",
                                    businessId, event.source()));
        } catch (RuntimeException e) {
            // 수집은 이미 끝났다. 분류를 못 넣었다고 그 결과를 실패로 만들지 않는다 —
            // 다음 수집이나 8.5 재실행이 같은 거래를 주워간다.
            log.warn("거래 분류 작업을 넣지 못했다 — 사업장 {} / {}: {}",
                    businessId, event.source(), e.toString());
        }
    }
}
