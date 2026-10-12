package com.jangoyebo.core.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, Long> {

    List<EvidenceItem> findByExtractionIdOrderByIdAsc(Long extractionId);
}
