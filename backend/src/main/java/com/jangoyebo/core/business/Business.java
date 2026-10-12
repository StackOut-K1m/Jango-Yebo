package com.jangoyebo.core.business;

import com.jangoyebo.common.entity.BaseEntity;
import com.jangoyebo.common.tenancy.BusinessScoped;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 사업장 — 모든 금융·예측 데이터의 최상위 접근 범위 (ERD {@code BUSINESS}).
 *
 * <p>사용자와는 {@code owner_user_id} 를 통한 <b>1:N 소유</b>다. 멤버십(N:M) 테이블은 두지 않으며
 * 세무대리인 공유는 범위 밖이다(D-12). 공유가 필요해지면 별도 테이블로 확장한다.
 */
@Entity
@Table(name = "business", schema = "core")
public class Business extends BaseEntity implements BusinessScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * 사업자등록번호 암호문. 길이는 IV 16 + 평문 + GCM 태그 16 을 hex 로 쓴 값이라
     * 평문의 2배보다 64자 길다 — V1 의 {@code VARCHAR(64)} 로는 어떤 값도 들어가지 않았다(V4).
     */
    @Column(name = "registration_number", length = 512)
    private String registrationNumber;

    /**
     * 응답용 마스킹 값. 암호문은 임의 IV 라 검색·비교가 불가능하므로 쓰기 시점에 계산해 둔다.
     * {@code ACCOUNT.account_number_masked} 와 같은 방식이다.
     */
    @Column(name = "registration_number_masked", length = 32)
    private String registrationNumberMasked;

    @Column(name = "industry_code", length = 30)
    private String industryCode;

    @Column(length = 50)
    private String region;

    @Column(name = "taxation_type", nullable = false, length = 20)
    private String taxationType;

    @Column(name = "opened_on")
    private LocalDate openedOn;

    @Column(name = "annual_revenue")
    private BigDecimal annualRevenue;

    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Business() {
    }

    private Business(Long ownerUserId, String name, String taxationType) {
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.taxationType = taxationType;
        this.status = "ACTIVE";
    }

    public static Business create(Long ownerUserId, String name, String taxationType) {
        return new Business(ownerUserId, name, taxationType);
    }

    public void updateProfile(String name, String industryCode,
                              String region, String taxationType, LocalDate openedOn,
                              BigDecimal annualRevenue, Integer employeeCount) {
        if (name != null) this.name = name;
        if (industryCode != null) this.industryCode = industryCode;
        if (region != null) this.region = region;
        if (taxationType != null) this.taxationType = taxationType;
        if (openedOn != null) this.openedOn = openedOn;
        if (annualRevenue != null) this.annualRevenue = annualRevenue;
        if (employeeCount != null) this.employeeCount = employeeCount;
    }

    /**
     * 사업자등록번호를 갱신한다. <b>암호문과 마스킹은 반드시 함께 움직인다</b> — 따로 두면
     * 한쪽만 바뀌어 응답의 마스킹이 실제 저장값과 어긋난다. 그래서 {@code updateProfile} 에
     * 섞지 않고 별도 메서드로 뺐다.
     */
    public void updateRegistrationNumber(String encrypted, String masked) {
        if (encrypted == null) {
            return;
        }
        this.registrationNumber = encrypted;
        this.registrationNumberMasked = masked;
    }

    public Long getId() { return id; }
    @Override public Long getBusinessId() { return id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public String getName() { return name; }
    public String getRegistrationNumber() { return registrationNumber; }
    public String getRegistrationNumberMasked() { return registrationNumberMasked; }
    public String getIndustryCode() { return industryCode; }
    public String getRegion() { return region; }
    public String getTaxationType() { return taxationType; }
    public LocalDate getOpenedOn() { return openedOn; }
    public BigDecimal getAnnualRevenue() { return annualRevenue; }
    public Integer getEmployeeCount() { return employeeCount; }
}
