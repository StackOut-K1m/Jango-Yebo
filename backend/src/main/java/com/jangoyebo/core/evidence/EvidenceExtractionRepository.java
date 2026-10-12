package com.jangoyebo.core.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EvidenceExtractionRepository extends JpaRepository<EvidenceExtraction, Long> {

    /**
     * 증빙의 현재 결과. 부분 유일 인덱스({@code uk_extraction_current})가 하나만 있음을 보장하므로
     * {@code Optional} 이 맞다 — 여러 건이 돌아오면 그건 인덱스가 깨진 것이다.
     */
    Optional<EvidenceExtraction> findByEvidenceIdAndCurrentTrue(Long evidenceId);

    /**
     * 목록 한 페이지분의 현재 결과를 한 번에 읽는다 (13.2).
     *
     * <p>행마다 {@link #findByEvidenceIdAndCurrentTrue} 를 부르면 20건짜리 페이지에 쿼리가
     * 21번 나간다. 목록이 검산 결과({@code needsReview})를 함께 내보내야 해서 생기는 비용이다.
     */
    List<EvidenceExtraction> findByEvidenceIdInAndCurrentTrue(Collection<Long> evidenceIds);
}
