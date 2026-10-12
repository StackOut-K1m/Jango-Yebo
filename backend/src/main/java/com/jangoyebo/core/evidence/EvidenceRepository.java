package com.jangoyebo.core.evidence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EvidenceRepository extends JpaRepository<Evidence, Long> {

    /**
     * 증빙 목록 (API 13.2).
     *
     * <p>{@code linked} 는 연결 여부 필터다. <b>이것이 이 화면의 핵심</b>이다 —
     * "증빙 없는 거래"와 "거래에 안 붙은 증빙"을 좁히는 것이 P0-10 의 목적이라
     * 서브쿼리로 판정한다. 조인하면 연결이 여러 건인 증빙이 중복 행으로 나온다.
     *
     * <p>기간은 발행일이 아니라 <b>업로드 시각</b> 기준이다. 발행일은 추출 후에 채워지므로
     * 업로드 직후에는 null 이고, null 을 기간 조건에 걸면 갓 올린 증빙이 목록에서 사라진다.
     *
     * <p><b>{@code CAST(... AS Instant)} 를 빼지 말 것.</b> Hibernate 는 같은 이름 파라미터의
     * 각 등장을 별도 {@code ?} 로 렌더링하고, 첫 등장이 {@code ? IS NULL} 이라 타입 문맥이 없다.
     * Postgres 가 {@code could not determine data type of parameter $2} 로 거부한다 —
     * {@code TransactionRepository} 의 {@code :keyword} 와 같은 함정이며, 비교에 쓰인다는
     * 사실만으로는 해결되지 않는다(실측). 캐스트가 첫 등장에 타입을 주는 것이 요점이다.
     *
     * <p>날짜를 {@code Instant} 로 받는다 — 변환({@code LocalDate} → {@code Instant})은
     * 컨트롤러가 한다. null 판정용 {@code LocalDate} 파라미터를 따로 두면 그 파라미터가
     * 같은 문제를 다시 만든다.
     *
     * <p>{@code needsReview} 는 검산이 걸린 건만 좁히는 필터다(13.2). <b>정렬은 여기서
     * 하지 않는다</b> — {@code Evidence} 의 매핑된 속성이라 호출자가
     * {@code sort=needsReview,desc} 로 {@link Pageable} 에 실으면 그대로 먹는다.
     * 정렬 키가 컬럼이 된 경위는 {@code V9__evidence_needs_review.sql} 에 있다.
     */
    @Query("""
            SELECT e FROM Evidence e
             WHERE e.businessId = :businessId
               AND e.deletedAt IS NULL
               AND (CAST(:fromInstant AS Instant) IS NULL OR e.createdAt >= :fromInstant)
               AND (CAST(:toInstant AS Instant) IS NULL OR e.createdAt < :toInstant)
               AND (:status IS NULL OR e.processingStatus = :status)
               AND (:type IS NULL OR e.evidenceType = :type)
               AND (:needsReview IS NULL OR e.needsReview = :needsReview)
               AND (:linked IS NULL
                    OR (:linked = TRUE  AND EXISTS (SELECT 1 FROM TransactionEvidence te
                                                     WHERE te.evidenceId = e.id
                                                       AND te.matchStatus = com.jangoyebo.core.evidence.MatchStatus.LINKED))
                    OR (:linked = FALSE AND NOT EXISTS (SELECT 1 FROM TransactionEvidence te
                                                         WHERE te.evidenceId = e.id
                                                           AND te.matchStatus = com.jangoyebo.core.evidence.MatchStatus.LINKED)))
            """)
    Page<Evidence> search(@Param("businessId") Long businessId,
                          @Param("fromInstant") Instant fromInstant,
                          @Param("toInstant") Instant toInstant,
                          @Param("status") EvidenceStatus status,
                          @Param("type") EvidenceType type,
                          @Param("linked") Boolean linked,
                          @Param("needsReview") Boolean needsReview,
                          Pageable pageable);

    /** 같은 파일을 두 번 올리는 것을 막는 판정. {@code uk_evidence_storage} 와 짝이다. */
    Optional<Evidence> findByStorageKey(String storageKey);
}
