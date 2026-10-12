package com.jangoyebo.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, Long> {

    List<LoanRepayment> findByLoanIdOrderByDueDateAsc(Long loanId);

    /**
     * 같은 회차를 두 번 쌓지 않는다. {@code uk_lr_due} 가 마지막 방어선이지만 거기서 걸리면
     * 트랜잭션이 통째로 죽어 나머지 회차도 같이 사라진다.
     */
    Optional<LoanRepayment> findByLoanIdAndDueDateAndInstallmentNo(Long loanId, LocalDate dueDate,
                                                                  Integer installmentNo);

    /** 실패한 회차. 「우리 예보는 그 전날 경고했는가」를 대조하는 입력이다. */
    List<LoanRepayment> findByLoanIdAndStatusOrderByDueDateAsc(Long loanId, String status);

    /**
     * 지워질 거래를 가리키던 회차를 놓아준다 (계좌 삭제 · API 7.2 purge).
     *
     * <p><b>회차는 지우지 않는다.</b> 상환 일정은 대출의 것이고 대출은 계좌가 아니라 사업장에
     * 속한다 — 출금 계좌를 지웠다고 갚아야 할 돈이 없어지지 않는다. 연결만 끊어 「어느 거래로
     * 나갔는지 모르게 됐다」는 상태로 둔다.
     *
     * <p>{@code status} 는 건드리지 않는다. {@code SUCCESS} 였다면 실제로 상환된 것이 맞고,
     * 그 사실까지 되돌리면 <b>이미 갚은 회차가 미납으로 되살아난다.</b>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE LoanRepayment r SET r.transactionId = null
             WHERE r.transactionId IN (SELECT t.id FROM Transaction t WHERE t.accountId = :accountId)
            """)
    int detachTransactionsOfAccount(@Param("accountId") Long accountId);
}
