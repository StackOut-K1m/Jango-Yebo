package com.jangoyebo.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 공통 감사 컬럼 (ERD 1.1 — 변경 가능한 업무 테이블).
 *
 * <p>DDL이 {@code DEFAULT now()}를 갖지만 그것은 최초 삽입에만 적용된다.
 * {@code updated_at} 갱신은 JPA Auditing이 담당한다.
 *
 * <p>append-only 테이블({@code transaction_classification_history} 등)은 이 클래스를 쓰지 않고
 * {@code created_at}만 직접 매핑한다 — 수정되지 않는 것이 설계 의도다.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
