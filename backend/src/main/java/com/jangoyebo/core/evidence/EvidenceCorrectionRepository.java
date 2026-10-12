package com.jangoyebo.core.evidence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvidenceCorrectionRepository extends JpaRepository<EvidenceCorrection, Long> {

    List<EvidenceCorrection> findByExtractionIdOrderByIdAsc(Long extractionId);
}
