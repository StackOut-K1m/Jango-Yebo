package com.jangoyebo.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {

    List<Loan> findByBusinessIdOrderByIdAsc(Long businessId);

    /**
     * 동기화가 「이미 아는 대출인가」를 묻는 경로. {@code uk_loan_external} 을 그대로 탄다.
     *
     * <p>사업장까지 함께 보는 이유는 <b>계좌번호가 전역 유일이 아니기 때문</b>이 아니라,
     * 남의 사업장 대출을 갱신하는 일이 애초에 일어날 수 없게 하기 위해서다.
     */
    Optional<Loan> findByBusinessIdAndExternalLoanNo(Long businessId, String externalLoanNo);

    /**
     * 만기가 지났는데 아직 안 닫힌 대출. 금융망에 「완납」 상태가 없어 우리가 판정한다.
     *
     * <p>연체는 제외한다 — 만기가 지나도 갚지 않은 돈이 남아 있고 곡선에서 사라지면 안 된다.
     */
    List<Loan> findByStatusNotInAndMaturityDateBefore(List<String> statuses, LocalDate date);

    /**
     * 출금 계좌를 지울 때 대출에서 그 연결을 뗀다 (계좌 삭제 · API 7.2 purge).
     *
     * <p><b>대출은 지우지 않는다.</b> 계좌가 사라져도 빚은 남는다 — 지우면 예정 거래와
     * 상환 일정이 통째로 사라져 곡선에서 <b>나갈 돈이 빠진다.</b> 없는 여유를 있다고 보여주는
     * 쪽이 훨씬 위험하다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Loan l SET l.withdrawalAccountId = null WHERE l.withdrawalAccountId = :accountId")
    int detachWithdrawalAccount(@Param("accountId") Long accountId);
}
