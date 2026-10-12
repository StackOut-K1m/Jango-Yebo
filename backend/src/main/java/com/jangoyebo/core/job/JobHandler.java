package com.jangoyebo.core.job;

import java.util.Map;

/**
 * 작업 유형 하나를 실제로 처리한다. {@link JobWorker} 가 획득한 Job 을 넘긴다.
 *
 * <p>구현체를 {@code @Component} 로 두기만 하면 워커가 자동으로 등록한다. 중앙 등록 파일이
 * 없으므로 "만들었는데 등록을 잊었다"가 생기지 않는다. 같은 {@link #jobType()} 을 반환하는
 * 구현이 둘이면 부팅에 실패한다 — 어느 쪽이 이기는지가 빈 순서에 달려 재현이 안 되기 때문이다.
 *
 * <p><b>제약 — {@code BusinessGuard} 를 타는 서비스를 호출하지 않는다.</b> 워커 스레드에는
 * SecurityContext 가 없어 {@code BusinessGuard.currentUser()} 가 {@code AUTH_TOKEN_INVALID}
 * 를 던진다. Job 이 {@code businessId} 를 직접 들고 있으므로 그것으로 범위를 잡는다.
 * 같은 이유로 {@code JobService.getOwnedJob} 도 부르면 안 된다.
 *
 * <p><b>제약 — {@code stall-timeout}(기본 10분) 안에 끝나야 한다.</b> 넘기면 아직 실행 중인데
 * 스위퍼가 회수해 다른 워커가 다시 집는다. 긴 작업은 Job 을 여러 개로 쪼개거나 멱등하게 만든다.
 */
public interface JobHandler {

    JobType jobType();

    /**
     * @param job 이미 {@code PROCESSING} 으로 전이된 detached 엔티티다. <b>읽기만 한다</b> —
     *            {@code job.complete()} 를 직접 불러도 managed 가 아니라 아무 일도 일어나지
     *            않는다. 완결은 반환값으로 알리고, 실패는 예외를 던진다
     *            ({@code BusinessException} 이면 그 {@code ErrorCode} 가 보존된다).
     */
    Outcome handle(Job job);

    /** 처리 결과. 동기 완결형과 콜백 대기형 두 가지뿐이다. */
    sealed interface Outcome {

        /** 콜백 대기형. Job 을 {@code PROCESSING} 에 남기고 외부가 완결한다(명세 20.2). */
        Outcome AWAITING_CALLBACK = new AwaitingCallback();

        /**
         * 접수되지 않았다. 대기열로 되돌려 잠시 뒤 다시 시도한다.
         *
         * <p><b>{@link #AWAITING_CALLBACK} 과 가르는 기준은 "외부에 일이 떠 있는가" 다.</b>
         * 연결조차 안 됐으면 워커는 이 작업을 모르므로 다시 보내도 중복 과금이 없고, 워커가
         * 배포·재시작 중이면 수 초 뒤 살아난다. 반대로 접수된(202) 뒤 콜백이 없는 것은
         * 워커가 이미 일하고 있을 수 있어 스위퍼의 10분을 기다려야 한다.
         *
         * <p>실측으로 이 구분이 없어서 <b>분류가 20분 멈췄다</b> — 갇힌 Job 을
         * {@code enqueueOrCurrent} 가 계속 돌려줘 8.5 버튼까지 먹통이 됐다.
         */
        Outcome NOT_ACCEPTED = new NotAccepted();

        /** 동기 완결형. 워커가 즉시 {@code JobService.complete} 를 부른다. */
        static Outcome completed(Map<String, Object> result) {
            return new Completed(result);
        }

        record Completed(Map<String, Object> result) implements Outcome {
        }

        record AwaitingCallback() implements Outcome {
        }

        record NotAccepted() implements Outcome {
        }
    }
}
