package com.jangoyebo.forecast.planned;

import com.jangoyebo.common.domain.FlowType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface PlannedTransactionRepository extends JpaRepository<PlannedTransaction, Long> {

    /**
     * 예정 거래 목록 (API 9.4).
     *
     * <p>{@code from}·{@code to} 는 필수다 — 예정 거래는 미래로 무한히 뻗을 수 있어
     * 범위를 안 주면 롤링 배치가 만든 90일치가 통째로 나온다. 상한(92일)은 컨트롤러가 막는다.
     */
    @Query("""
            SELECT p FROM PlannedTransaction p
             WHERE p.businessId = :businessId
               AND p.expectedDate BETWEEN :from AND :to
               AND (:status IS NULL OR p.status = :status)
               AND (:sourceType IS NULL OR p.sourceType = :sourceType)
               AND (:flowType IS NULL OR p.flowType = :flowType)
            """)
    Page<PlannedTransaction> search(@Param("businessId") Long businessId,
                                    @Param("from") LocalDate from,
                                    @Param("to") LocalDate to,
                                    @Param("status") PlannedStatus status,
                                    @Param("sourceType") PlannedSourceType sourceType,
                                    @Param("flowType") FlowType flowType,
                                    Pageable pageable);

    /**
     * 닿을 다시 재는 매처가 밀 대상 — 그 패턴의 앞으로의 회차 (API 9.5).
     *
     * <p>{@code EXPECTED} 만 고른다 — 사용자가 확정·수정한 것({@code CONFIRMED})이나
     * 이미 매칭된 것({@code MATCHED})까지 옮기면 사용자의 손을 배치가 되돌리는 셈이 된다.
     *
     * <p>패턴 중지는 이제 이걸로 취소하지 않고 {@link #deleteRegenerableOf} 로 걷어낸다.
     */
    List<PlannedTransaction> findByRecurringPatternIdAndStatusAndExpectedDateGreaterThanEqual(
            Long recurringPatternId, PlannedStatus status, LocalDate from);

    /**
     * 잔액 곡선(P0-07)의 입력.
     *
     * <p><b>{@code EXPECTED}·{@code CONFIRMED} 만 넘긴다.</b> {@code MATCHED} 는 실제로 일어나
     * 이미 잔액에 반영돼 있고 {@code CANCELLED}·{@code MISSED} 는 일어나지 않는다 —
     * 넣으면 이중 계상이다.
     */
    List<PlannedTransaction> findByBusinessIdAndStatusInAndExpectedDateBetween(
            Long businessId, Collection<PlannedStatus> statuses, LocalDate from, LocalDate to);

    /**
     * 이미 예정 거래에 붙어 있는 실제 거래 (API 9.5).
     *
     * <p>두 곳에서 쓴다 — 매처가 같은 거래를 두 번 붙이지 않게 거르고, 잔여 집계가
     * 「예정 거래가 설명한 몫」으로 빼낸다. 부분 UK(uk_pt_matched)가 1:1 을 강제하므로
     * 이 집합에 중복이 없다.
     */
    @Query("""
            SELECT p.matchedTransactionId FROM PlannedTransaction p
             WHERE p.businessId = :businessId
               AND p.matchedTransactionId IS NOT NULL
            """)
    Set<Long> findMatchedTransactionIds(@Param("businessId") Long businessId);

    /**
     * 자동 반영이 훑는 창 (API 9.6). <b>사업장 단위로 읽고 계좌는 호출부가 가른다.</b>
     *
     * <p>계좌로 거르지 않는 이유는 <b>계좌가 비어 있는 행</b> 때문이다. 웹 등록 폼이 계좌를
     * 보내기 전에 만들어진 예정 거래가 그렇고, 그 행을 어느 계좌로 볼지는 「그 사업장에
     * 자동 반영을 켠 계좌가 하나뿐인가」에 달렸다 — 저장소가 알 수 없는 판단이다.
     *
     * <p><b>원장 유무로 거르지 않는다.</b> 이미 실행된 행도 함께 읽어야 같은
     * {@code (예정일, 방향, 금액, 적요)} 안의 순번({@code ordinal})을 <b>실행 이력과 무관하게</b>
     * 같은 값으로 계산할 수 있다 — 거르고 세면 한 건이 실행될 때마다 나머지의 순번이 밀린다.
     *
     * <p><b>{@code PATTERN}·{@code USER} 만 본다.</b> 둘 다 사람이 명시적으로 정한 것이다 —
     * 앞은 탐지된 후보를 사장님이 승인한 것이고 뒤는 직접 적은 것이다. 반면 {@code TAX}·
     * {@code LOAN} 은 서버가 일정에서 만든 것이라 <b>사장님이 주장한 적 없는 돈</b>이다.
     *
     * <p>정렬을 id 로 시작하지 않는 이유는 <b>로컬 DB 와 배포 DB 의 id 가 다르기</b> 때문이다.
     * 두 서버가 같은 계좌를 볼 때 순번이 갈리면 채번이 갈리고, 중복 방지의 근거가 사라진다.
     */
    @Query("""
            select p from PlannedTransaction p
             where p.businessId = :businessId
               and p.sourceType in (com.jangoyebo.forecast.planned.PlannedSourceType.PATTERN,
                                    com.jangoyebo.forecast.planned.PlannedSourceType.USER)
               and p.status in (com.jangoyebo.forecast.planned.PlannedStatus.EXPECTED,
                                com.jangoyebo.forecast.planned.PlannedStatus.CONFIRMED)
               and p.expectedDate between :from and :to
             order by p.expectedDate, p.name, p.amountExpected, p.id
            """)
    List<PlannedTransaction> findForExecutionWindow(@Param("businessId") Long businessId,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);

    /**
     * 패턴이 만든 <b>아직 안 일어난</b> 예정 거래를 지운다. 달력이 바뀌어 금액을 다시 만들 때 쓴다.
     *
     * <p>지우는 범위를 좁게 잡는다 — {@code EXPECTED} 이고, 매칭이 안 됐고, 출처가
     * {@code PATTERN} 이고, 기준일 이후인 것만. <b>사용자가 손댄 것과 이미 일어난 것은
     * 건드리지 않는다.</b>
     *
     * <p><b>금융망에 이미 반영된 것은 남긴다.</b> 지우면 그 회차의 원장 행이 가리키는 예정
     * 거래가 사라져 외래키가 깨지고(달력 수정이 500 이 된다), 새 id 로 다시 만들어진 행에는
     * 원장이 없어 <b>같은 회차가 한 번 더 나간다.</b> {@code uk_pe_pattern_due} 가 돈을 막지만
     * 그건 마지막 방어선이고, 애초에 그 자리에 가지 않는 편이 맞다.
     *
     * <p>벌크 삭제여야 한다 ({@link com.jangoyebo.forecast.pattern.RecurringPatternTransactionRepository}
     * 와 같은 이유) — 삭제보다 삽입이 먼저 flush 되면 같은 날짜가 겹친다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PlannedTransaction p
            where p.businessId = :businessId
              and p.status = com.jangoyebo.forecast.planned.PlannedStatus.EXPECTED
              and p.sourceType = com.jangoyebo.forecast.planned.PlannedSourceType.PATTERN
              and p.matchedTransactionId is null
              and p.expectedDate >= :from
              and not exists (select 1 from PlannedExecution e
                               where e.plannedTransactionId = p.id)
            """)
    void deleteRegenerable(@Param("businessId") Long businessId, @Param("from") LocalDate from);

    /**
     * <b>한 패턴이 만든</b> 아직 안 일어난 예정 거래를 지운다 — 패턴을 멈추거나 조건을 고칠 때.
     *
     * <p>술어는 {@link #deleteRegenerable} 과 같고 범위만 사업장에서 패턴 하나로 좁혔다.
     *
     * <p><b>취소가 아니라 삭제인 이유.</b> 취소로 남기면 그 행이 {@code CANCELLED} 더미에 섞여
     * <b>사용자가 직접 끈 회차와 구별되지 않는다</b> — 패턴 생성분은 삭제가 막혀 있어
     * ({@code PLANNED_TX_NOT_DELETABLE}) 사용자에게도 취소가 곧 삭제이기 때문이다. 그 상태로
     * 되돌리면서 되살리면 <b>사장님이 끈 회차까지 살아나고</b>, 자동 반영 계좌라면 실제로 나간다
     * (도메인 규칙 5). 지워 두면 남은 {@code CANCELLED} 는 전부 사용자 것이고, 생성기는
     * {@link #findOccupiedDates} 로 그 날짜를 비켜 가기만 하면 된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PlannedTransaction p
            where p.recurringPatternId = :patternId
              and p.status = com.jangoyebo.forecast.planned.PlannedStatus.EXPECTED
              and p.sourceType = com.jangoyebo.forecast.planned.PlannedSourceType.PATTERN
              and p.matchedTransactionId is null
              and p.expectedDate >= :from
              and not exists (select 1 from PlannedExecution e
                               where e.plannedTransactionId = p.id)
            """)
    void deleteRegenerableOf(@Param("patternId") Long patternId, @Param("from") LocalDate from);

    /**
     * 그 패턴이 <b>이미 자리를 잡은</b> 날짜 — 생성기가 여기를 비켜 간다.
     *
     * <p>삭제가 지우지 않고 남긴 것들이다. 사용자가 끈 회차({@code CANCELLED}), 이미 일어난 것
     * ({@code MATCHED}·{@code MISSED}), 금융망에 나간 것. 그 날짜에 새 {@code EXPECTED} 를
     * 하나 더 만들면 <b>사용자가 끈 회차가 되살아나고</b> 나간 회차는 <b>곡선에 두 번</b>
     * 들어간다 — {@code EXPECTED} 와 {@code CONFIRMED} 를 곡선이 함께 더하기 때문이다.
     */
    @Query("""
            select p.expectedDate from PlannedTransaction p
             where p.recurringPatternId = :patternId
               and p.expectedDate >= :from
            """)
    Set<LocalDate> findOccupiedDates(@Param("patternId") Long patternId,
                                     @Param("from") LocalDate from);

    /**
     * <b>한 대출이 만든</b> 아직 안 일어난 회차를 지운다 — 대출 조건이 바뀌거나 다시 동기화할 때.
     *
     * <p>술어가 {@link #deleteRegenerableOf} 와 같은 이유도 같다. 다만 대출은
     * {@code PlannedExecution} 이 생길 수 없다 — 자동 반영({@link #findForExecutionWindow})이
     * {@code PATTERN}·{@code USER} 만 보기 때문이다. <b>금융망이 매일 08:30 에 스스로 빼므로
     * 우리가 또 빼면 하루에 두 번 나간다.</b> 그래도 술어에 남겨 둔다 — 그 조건이 언젠가
     * 바뀌면 여기가 조용히 돈을 두 번 내보내는 자리가 된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PlannedTransaction p
            where p.loanId = :loanId
              and p.status = com.jangoyebo.forecast.planned.PlannedStatus.EXPECTED
              and p.sourceType = com.jangoyebo.forecast.planned.PlannedSourceType.LOAN
              and p.matchedTransactionId is null
              and p.expectedDate >= :from
              and not exists (select 1 from PlannedExecution e
                               where e.plannedTransactionId = p.id)
            """)
    void deleteRegenerableOfLoan(@Param("loanId") Long loanId, @Param("from") LocalDate from);

    /**
     * <b>사업장의</b> 대출 예정 거래를 통째로 지운다 — 다시 만들기 직전에 한 번.
     *
     * <p>{@link #deleteRegenerableOfLoan} 처럼 대출 하나씩 지우면 <b>완납·해지된 대출의
     * 미래 회차가 영영 남는다.</b> 그 대출은 더 이상 목록에 없어 생성기가 손댈 일이 없기
     * 때문이다 — 곡선이 갚을 필요 없는 돈을 계속 뺀다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PlannedTransaction p
            where p.businessId = :businessId
              and p.status = com.jangoyebo.forecast.planned.PlannedStatus.EXPECTED
              and p.sourceType = com.jangoyebo.forecast.planned.PlannedSourceType.LOAN
              and p.matchedTransactionId is null
              and p.expectedDate >= :from
              and not exists (select 1 from PlannedExecution e
                               where e.plannedTransactionId = p.id)
            """)
    void deleteRegenerableLoansOf(@Param("businessId") Long businessId, @Param("from") LocalDate from);

    /** 그 대출이 <b>이미 자리를 잡은</b> 날짜. {@link #findOccupiedDates} 와 같은 이유로 비켜 간다. */
    @Query("""
            select p.expectedDate from PlannedTransaction p
             where p.loanId = :loanId
               and p.expectedDate >= :from
            """)
    Set<LocalDate> findOccupiedLoanDates(@Param("loanId") Long loanId,
                                         @Param("from") LocalDate from);

    /**
     * 지워질 거래에 붙어 있던 매칭을 뗀다 (계좌 삭제 · API 7.2 purge).
     *
     * <p><b>여기 걸리는 것은 다른 계좌의 예정 거래다.</b> 지우는 계좌 자신의 예정 거래는
     * {@link #deleteOfAccount} 가 통째로 가져간다. 남는 경우는 사업장 단위 예정 거래
     * ({@code account_id} 가 {@code null})나 다른 계좌 것이 이 계좌의 실제 거래에 매칭된
     * 상태인데, 그 거래가 사라지면 <b>{@code MATCHED} 인데 가리키는 거래가 없는</b> 행이 된다.
     *
     * <p>{@code EXPECTED} 로 되돌리는 것이 맞다. 매칭은 「이 예정 거래는 이미 일어났다」는
     * 주장이고 그 근거가 사라졌으므로 주장도 함께 거둔다 — 남겨 두면 곡선에서 계속 빠진 채로
     * 있어(<b>매칭된 예정 거래는 이중 계상을 피하려 곡선에서 제외된다</b>) 다시는 더해지지 않는다.
     * {@code CONFIRMED} 로 되돌리지 않는 이유는 확정 여부를 알 수 없기 때문이다.
     *
     * <p><b>네이티브다.</b> 술어가 {@code core.transaction} 을 봐야 하는데 그 엔티티는
     * {@code core} 소유라 JPQL 에서 이름을 부를 수 없다({@code package-info} 의 단방향 규칙).
     * id 목록을 인자로 받는 방법도 있지만 계좌 하나에 거래가 수천 건이라 {@code IN} 이 그만큼
     * 길어진다. SQL 이 테이블명을 적는 것은 <b>이미 외래키로 있는 결합</b>이라 새로 생기는
     * 의존이 아니다 — Modulith 도 자바 참조만 본다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE forecast.planned_transaction
               SET matched_transaction_id = NULL,
                   matched_date_diff_days = NULL,
                   matched_amount_diff    = NULL,
                   status                 = 'EXPECTED',
                   updated_at             = now()
             WHERE matched_transaction_id IN (SELECT id FROM core.transaction WHERE account_id = :accountId)
            """)
    int unmatchTransactionsOf(@Param("accountId") Long accountId);

    /**
     * 이 계좌의 예정 거래를 지운다 (계좌 삭제 · API 7.2 purge).
     *
     * <p>계좌를 직접 가리키는 것과, <b>이 계좌의 반복 패턴이 만든 것</b>을 함께 가져간다.
     * 뒤엣것을 빼면 패턴을 지울 때 {@code fk_pt_pattern} 이 걸린다 — 패턴 생성분은
     * {@code account_id} 가 비어 있을 수 있어 앞 조건만으로는 잡히지 않는다.
     *
     * <p>{@code deleteRegenerable*} 과 달리 <b>술어를 좁히지 않는다.</b> 그쪽은 「다시 만들
     * 수 있는 것만」 골라내는 재생성용이고, 여기는 계좌가 통째로 없어지는 자리라
     * {@code MATCHED}·{@code CANCELLED}·사용자가 손댄 것까지 전부 대상이다. 남기면 지워진
     * 계좌를 가리키는 예정 거래가 달력에 계속 뜬다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from PlannedTransaction p
            where p.accountId = :accountId
               or p.recurringPatternId in (select r.id from RecurringPattern r
                                            where r.accountId = :accountId)
            """)
    int deleteOfAccount(@Param("accountId") Long accountId);
}
