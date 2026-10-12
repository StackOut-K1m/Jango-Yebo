package com.jangoyebo.core.settlement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findByBusinessIdOrderBySalesToDesc(Long businessId);
}
