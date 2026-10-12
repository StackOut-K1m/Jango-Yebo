package com.jangoyebo.core.category;

import jakarta.persistence.*;

/**
 * 거래 카테고리 (ERD {@code TRANSACTION_CATEGORY}, API 8.1의 23종).
 *
 * <p>시드 데이터라 애플리케이션이 생성하지 않는다. 값 추가·비활성·개명은
 * 마이그레이션으로 하며 {@code code} 는 안정 식별자다 — 규칙셋·LLM few-shot·분류 이력이
 * 여기에 묶이므로 바꾸면 학습 자산이 깨진다.
 */
@Entity
@Table(name = "transaction_category", schema = "core")
public class TransactionCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "flow_type", nullable = false, length = 20)
    private String flowType;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected TransactionCategory() {
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getFlowType() { return flowType; }
    public boolean isActive() { return active; }
    public int getSortOrder() { return sortOrder; }
}
