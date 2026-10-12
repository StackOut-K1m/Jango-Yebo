package com.jangoyebo.forecast.run;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface ForecastRunRepository extends JpaRepository<ForecastRun, Long> {

    /**
     * 조회 API 가 읽는 유일한 실행 (공통규약 2.6 — 조회는 계산하지 않는다).
     *
     * <p>{@code ix_fr_latest} 가 {@code WHERE status = 'COMPLETED'} 부분 인덱스라 상태 조건이
     * 함께 있어야 탄다. 파생 쿼리로 쓰는 이유는 HQL {@code LIMIT} 절의 Hibernate 버전 의존을
     * 피하기 위해서다 — 사업장당 하루 몇 건 규모라 계획 차이가 실질적으로 없다.
     */
    Optional<ForecastRun> findFirstByBusinessIdAndStatusOrderByBaseAtDesc(
            Long businessId, ForecastRunStatus status);

    /**
     * 30일 지난 실행 정리 ({@code ix_fr_cleanup}).
     *
     * <p><b>사업장별 최신 {@code COMPLETED} 1건은 오래됐어도 남긴다.</b> 지우면 한동안 쓰지 않은
     * 사업장이 다시 들어왔을 때 화면이 통째로 빈다 — 곡선이 없으면 조회가 409 를 준다.
     *
     * <p>독립 실행형 정리라 트랜잭션을 스스로 연다 — 호출자(새벽 배치)가 다른 일과 묶을
     * 이유가 없고, {@code @Modifying} 은 트랜잭션 없이는 던진다.
     *
     * <p>일별 곡선은 {@code fk_dbf_run} 의 {@code ON DELETE CASCADE} 가 함께 지운다.
     * {@code SCENARIO} 는 재계산 모델이라 실행을 참조하지 않으므로 확인할 것이 없다.
     */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            DELETE FROM forecast.forecast_run r
             WHERE r.created_at < :cutoff
               AND r.id NOT IN (
                   SELECT DISTINCT ON (business_id) id
                     FROM forecast.forecast_run
                    WHERE status = 'COMPLETED'
                    ORDER BY business_id, base_at DESC
               )
            """)
    int deleteOlderThanKeepingLatest(@Param("cutoff") Instant cutoff);

    /**
     * 사업장의 저장된 실행을 전부 지운다 — <b>연결된 계좌가 하나도 남지 않았을 때만</b> 쓴다.
     *
     * <p>{@link #deleteOlderThanKeepingLatest} 가 최신 1건을 일부러 남기는 것과 정반대다.
     * 그쪽은 「한동안 안 쓴 사업장이 돌아왔을 때 화면이 비지 않게」가 목적이라 남기는 것이
     * 맞지만, 계좌가 0개면 그 1건은 <b>사라진 계좌로 계산된 곡선</b>이라 남길 이유가 없다.
     *
     * <p>일별 곡선은 {@code fk_dbf_run} 의 {@code ON DELETE CASCADE} 가 함께 가져간다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ForecastRun r where r.businessId = :businessId")
    int deleteByBusinessId(@Param("businessId") Long businessId);
}
