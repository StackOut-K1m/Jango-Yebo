package com.jangoyebo.core.user;

import com.jangoyebo.common.entity.BaseEntity;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * 로그인 사용자 (ERD {@code USER}).
 *
 * <p>물리 테이블명은 {@code users} 다 — {@code user} 가 PostgreSQL 예약어이기 때문이며
 * 프로젝트 전체에서 유일한 명명 예외다(ERD v1.4).
 */
@Entity
@Table(name = "users", schema = "core")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 20)
    private String phone;

    /**
     * 금융망 {@code userKey}. 사실상 계좌 접근권이므로 암호화해 저장하고 응답에 노출하지 않는다.
     * 가입 시 발급이 실패하면 null 로 두고 계좌 연결 시점에 재시도한다(API 6.1).
     */
    @Column(name = "fin_user_key", length = 512)
    private String finUserKey;

    @Column(name = "default_business_id")
    private Long defaultBusinessId;

    @Column(name = "system_role", nullable = false, length = 20)
    private String systemRole;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {
    }

    private User(String email, String passwordHash, String name, String phone) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.phone = phone;
        this.systemRole = "USER";
        this.status = "ACTIVE";
    }

    public static User signUp(String email, String passwordHash, String name, String phone) {
        return new User(email, passwordHash, name, phone);
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public void updateProfile(String name, String phone, Long defaultBusinessId) {
        if (name != null) this.name = name;
        if (phone != null) this.phone = phone;
        if (defaultBusinessId != null) this.defaultBusinessId = defaultBusinessId;
    }

    public void linkFinUserKey(String encryptedUserKey) {
        this.finUserKey = encryptedUserKey;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getFinUserKey() { return finUserKey; }
    public Long getDefaultBusinessId() { return defaultBusinessId; }
    public Instant getLastLoginAt() { return lastLoginAt; }
}
