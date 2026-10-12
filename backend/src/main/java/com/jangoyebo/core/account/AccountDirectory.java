package com.jangoyebo.core.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 금융망 계좌번호로 우리 계좌를 되짚는다.
 *
 * <p><b>대출이 이것을 필요로 한다.</b> {@code LOAN_08} 은 {@code userKey} 로 조회해서
 * 사업장 개념이 없다 — 가게가 둘인 사장님의 대출이 한 목록에 섞여 온다. 어느 사업장 것인지는
 * {@code withdrawalAccountNo} 가 우리 어느 계좌인지로만 알 수 있다.
 *
 * <p>{@link AccountNumbers} 를 쓰는 것이 핵심이다 — 계좌를 연결할 때 만든 해시와
 * <b>같은 규칙</b>으로 계산해야 찾아진다. 규칙이 갈리면 오류가 아니라 「못 찾음」이 되어
 * 대출이 조용히 어디에도 안 붙는다.
 */
@Service
public class AccountDirectory {

    private final AccountRepository accounts;

    public AccountDirectory(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * 금융망 계좌번호 원문으로 찾는다.
     *
     * <p><b>상태를 가리지 않는다.</b> 연동을 끊은 계좌도 「어느 사업장인가」는 답할 수 있고,
     * 대출을 어디에 붙일지는 그것으로 충분하다. 예보에 넣을지는 부르는 쪽이
     * {@link OwnedAccount#connected} 를 보고 정한다 — 끊긴 계좌는 잔액이 갱신되지 않아
     * 시작 잔액에 들어가지 않으므로, 그 계좌의 지출만 곡선에 넣으면 <b>없는 공백이 생긴다.</b>
     */
    @Transactional(readOnly = true)
    public Optional<OwnedAccount> findByExternalNumber(String rawAccountNo) {
        if (rawAccountNo == null || rawAccountNo.isBlank()) {
            return Optional.empty();
        }
        return accounts.findByAccountNumberHash(AccountNumbers.hashOf(rawAccountNo))
                .map(a -> new OwnedAccount(a.getId(), a.getBusinessId(), a.isConnected()));
    }

    /**
     * @param connected 연동이 살아 있는가. 끊긴 계좌는 잔액이 더 이상 갱신되지 않는다
     */
    public record OwnedAccount(Long accountId, Long businessId, boolean connected) {
    }
}
