package com.jangoyebo.forecast.scenario;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 저장한 What-if 시나리오 (ERD {@code SCENARIO}, API 11.2).
 *
 * <p><b>파라미터만 저장하고 결과는 저장하지 않는다.</b> 어제 만든 시나리오를 오늘 열면
 * 어제의 예측과 비교하게 되기 때문이다 — 사용자가 보려는 것은 「지금 이 가정이면 어떻게
 * 되는가」이지 「어제 이 가정이었으면」이 아니다. 그래서 기준 실행 FK 도, 결과 요약도,
 * 일별 결과도 두지 않는다. {@code save = false} 계산은 이 행조차 만들지 않는다.
 *
 * <p>대신 <b>열 때마다 다시 계산한다.</b> 응답의 {@code baseTime} 이 그 계산이 어느 실행
 * 위에서 이뤄졌는지 말한다.
 */
@Entity
@Table(name = "scenario", schema = "forecast")
public class Scenario extends BaseEntity implements BusinessScoped {

    /** 이름을 비워 보냈을 때. 화면 목록이 빈 줄로 보이지 않게 서버가 정한다(DB 는 NOT NULL). */
    static final String DEFAULT_NAME = "이름 없는 시나리오";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, updatable = false)
    private Long businessId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(nullable = false, length = 100)
    private String name;

    /** 7·14·30 ({@code ck_scenario_days}). 저장 당시의 창을 기억한다. */
    @Column(nullable = false)
    private int days;

    protected Scenario() {
    }

    private Scenario(Long businessId, Long createdBy, String name, int days) {
        this.businessId = businessId;
        this.createdBy = createdBy;
        this.name = name;
        this.days = days;
    }

    public static Scenario of(Long businessId, Long createdBy, String name, int days) {
        return new Scenario(businessId, createdBy, nameOrDefault(name), days);
    }

    /** 이름만 바꾼다 (11.2 PATCH). 파라미터도 결과도 건드리지 않는다. */
    public void rename(String name) {
        this.name = nameOrDefault(name);
    }

    private static String nameOrDefault(String name) {
        return name == null || name.isBlank() ? DEFAULT_NAME : name.strip();
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return businessId; }
    public Long getCreatedBy() { return createdBy; }
    public String getName() { return name; }
    public int getDays() { return days; }
}
