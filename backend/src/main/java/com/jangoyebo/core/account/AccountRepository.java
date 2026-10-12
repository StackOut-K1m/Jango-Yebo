package com.jangoyebo.core.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByBusinessIdOrderByIdAsc(Long businessId);

    /**
     * 계좌번호 해시로 찾는다. {@code uk_account_number} 가 전역 유일이라 결과는 하나뿐이다.
     *
     * <p>존재 여부만 보던 것을 엔티티로 바꾼 이유는 <b>세 갈래를 구분해야 하기 때문이다</b> —
     * 내 사업장의 되살릴 수 있는 행인지, 중복 연결인지, 남의 계좌인지. 응답이 각각 다르다
     * ({@link AccountService#link}).
     */
    Optional<Account> findByAccountNumberHash(String accountNumberHash);

    /**
     * 예정 거래 자동 반영 대상 (API 9.6).
     *
     * <p>{@code CONNECTED} 를 함께 보는 이유는 <b>플래그만으로는 부족하기 때문</b>이다 —
     * 해제·재연결이 플래그를 끄지만, 인증 대기 중인 계좌가 어떤 경로로든 켜진 채 남는다면
     * 우리가 소유를 확인하지 못한 계좌에 돈이 나간다.
     */
    List<Account> findByAutoExecutePlannedTrueAndStatusOrderByIdAsc(String status);

    long countByBusinessId(Long businessId);

    /**
     * 연결된 계좌 수 (API 7.1 {@code accountCount}).
     *
     * <p><b>상태를 본다.</b> 연동을 끊은 계좌는 행이 남지만 잔액·거래가 돈 세는 자리에서 빠지므로
     * (§3.3 · 2026-09-11), 개수만 그대로 세면 화면이 「계좌 3개」라 하고 곡선은 2개로 돈다.
     * 잔액 합계가 {@code CONNECTED} 만 세는 것과 같은 기준이다.
     */
    long countByBusinessIdAndStatus(Long businessId, String status);
}
