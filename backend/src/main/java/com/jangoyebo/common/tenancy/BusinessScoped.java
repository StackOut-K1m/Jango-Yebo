package com.jangoyebo.common.tenancy;

/**
 * 사업장 범위에 속하는 엔티티.
 *
 * <p>{@code /transactions/{id}} 같은 <b>직접 경로</b>로 조회한 리소스는
 * {@link BusinessGuard#assertOwned}로 소유권을 확인해야 한다. Hibernate 필터는
 * {@code findById}에 적용되지 않으므로 이 경로는 자동으로 막히지 않는다.
 */
public interface BusinessScoped {

    Long getBusinessId();
}
