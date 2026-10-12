package com.jangoyebo.common.tenancy;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.security.AuthUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 사업장 접근 통제.
 *
 * <p>인수 조건 "권한 없는 사용자는 다른 사업장 거래를 조회할 수 없다"를 지키는 장치이며,
 * <b>두 경로를 서로 다르게 막는다.</b>
 *
 * <table>
 *   <tr><th>경로</th><th>방법</th></tr>
 *   <tr><td>{@code /businesses/{businessId}/**}</td>
 *       <td>{@link CurrentBusinessArgumentResolver}가 주입 시점에 자동 검증</td></tr>
 *   <tr><td>{@code /transactions/{id}} 등 직접 경로</td>
 *       <td>서비스에서 {@link #assertOwned}를 <b>명시적으로</b> 호출</td></tr>
 * </table>
 *
 * <p>후자를 자동화하지 못하는 이유는 Hibernate {@code @Filter}가 {@code findById}에는
 * 적용되지 않기 때문이다. 여기가 유일하게 사람이 지켜야 하는 지점이므로
 * <b>직접 경로 조회를 추가할 때는 리뷰에서 확인한다.</b>
 */
@Component
public class BusinessGuard {

    private final BusinessOwnershipReader ownershipReader;

    public BusinessGuard(BusinessOwnershipReader ownershipReader) {
        this.ownershipReader = ownershipReader;
    }

    /** 현재 로그인 사용자. 인증되지 않았으면 401. */
    public AuthUser currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser user)) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        return user;
    }

    public Long currentUserId() {
        return currentUser().userId();
    }

    /**
     * 사업장 소유 여부를 <b>묻는다.</b> 막지 않고 참·거짓만 돌려준다.
     *
     * <p>{@link #assertOwnsBusiness} 로 충분하지 않은 자리가 있다 — 응답을 소유 여부에 따라
     * <b>다르게</b> 만들어야 할 때다({@code AccountService.link} 의 중복 연결 판정이 유일한
     * 사례다: 내 다른 사업장이면 409, 남이면 계좌 존재를 흘리지 않으려 400 으로 뭉갠다).
     * 접근을 막는 자리에는 쓰지 말 것 — 그쪽은 던지는 쪽이 맞다.
     */
    public boolean ownsBusiness(Long businessId) {
        return ownershipReader.isOwnedBy(businessId, currentUserId());
    }

    /** 사업장 소유 여부 검증. 남의 사업장이면 존재 여부를 흘리지 않기 위해 일괄 403이다. */
    public void assertOwnsBusiness(Long businessId) {
        if (!ownershipReader.isOwnedBy(businessId, currentUserId())) {
            throw new BusinessException(ErrorCode.BUSINESS_ACCESS_DENIED);
        }
    }

    /**
     * 직접 경로로 가져온 리소스의 소유권 검증.
     *
     * <pre>{@code
     * Transaction tx = repository.findById(id)
     *         .orElseThrow(() -> new BusinessException(TRANSACTION_NOT_FOUND));
     * guard.assertOwned(tx);   // ← 빠뜨리면 남의 거래가 그대로 나간다
     * }</pre>
     */
    public void assertOwned(BusinessScoped resource) {
        assertOwnsBusiness(resource.getBusinessId());
    }
}
