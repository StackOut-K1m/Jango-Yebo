package com.jangoyebo.core.business;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    List<Business> findByOwnerUserIdAndDeletedAtIsNullOrderByIdAsc(Long ownerUserId);

    Optional<Business> findByIdAndDeletedAtIsNull(Long id);

    long countByOwnerUserIdAndDeletedAtIsNull(Long ownerUserId);
}
