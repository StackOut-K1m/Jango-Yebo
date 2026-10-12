package com.jangoyebo.core.job;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    /** 스위퍼용 — 시작한 지 오래된 PROCESSING 작업. 콜백 유실 감지에 쓴다. */
    List<Job> findByStatusAndStartedAtBefore(JobStatus status, Instant threshold);

    /**
     * 같은 사업장에서 아직 끝나지 않은 같은 종류의 작업 (거래 분류 합의 ④).
     *
     * <p>{@link JobService#enqueueIfIdle} 와 {@link JobService#enqueueOrCurrent} 가 쓴다 —
     * 왜 필요한지와 이 판정의 한계는 거기에 적어 두었다.
     *
     * <p>있으면 하나면 된다. 둘 이상이면 가드가 새어 나간 것인데(그 창은
     * {@code enqueueIfIdle} 에 적어 뒀다) 그때는 <b>가장 최근 것</b>을 본다 — 사용자가 방금
     * 누른 재실행이 그쪽이다.
     */
    Optional<Job> findFirstByBusinessIdAndJobTypeAndStatusInOrderByIdDesc(
            Long businessId, JobType jobType, Collection<JobStatus> statuses);

    /**
     * 사업장의 같은 종류 작업 전부, 최근 것부터 (API 14.2 내보내기 이력).
     *
     * <p>내보내기는 전용 테이블이 없고 {@code JOB} 이 이력 그 자체다(ERD §1.4). 페이징을 두지
     * 않는 것은 한 사업장이 신고 준비자료를 만드는 횟수가 한 해에 몇 번뿐이기 때문이다.
     */
    List<Job> findByBusinessIdAndJobTypeOrderByIdDesc(Long businessId, JobType jobType);

    /** 만료 정리용 — 끝난 지 오래된 작업. 파일만 지우고 행은 이력으로 남긴다. */
    List<Job> findByJobTypeAndStatusAndCompletedAtBefore(
            JobType jobType, JobStatus status, Instant threshold);

    /**
     * 콜백 수신용 행 잠금 조회.
     *
     * <p>스위퍼({@code findByStatusAndStartedAtBefore} + {@code requeue})와 콜백이 같은 행을
     * 동시에 만질 수 있다. 잠금 없이 두면 완료 처리와 회수가 겹쳐 last-writer-wins 가 된다.
     * 잠금 대기는 일시적 실패이므로 5xx 로 나가 워커가 재시도한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Job> findWithLockById(Long id);
}
