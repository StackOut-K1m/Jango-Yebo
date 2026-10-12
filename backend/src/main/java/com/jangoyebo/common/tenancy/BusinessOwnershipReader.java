package com.jangoyebo.common.tenancy;

/**
 * 사업장 소유권 조회 포트.
 *
 * <p>{@code common}은 도메인 엔티티를 참조하지 않으므로 인터페이스만 두고,
 * 구현은 {@code core}가 제공한다. 이 방향이라야 모듈 의존이 {@code core → common}으로
 * 유지된다(반대가 되면 경계가 깨진다).
 */
public interface BusinessOwnershipReader {

    /** 삭제되지 않은 사업장이며 소유자가 {@code userId}인가. */
    boolean isOwnedBy(Long businessId, Long userId);
}
