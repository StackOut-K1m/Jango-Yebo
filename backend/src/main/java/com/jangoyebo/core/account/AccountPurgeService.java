package com.jangoyebo.core.account;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.AccountPurgeParticipant;
import com.jangoyebo.core.TransactionsChanged;
import com.jangoyebo.core.evidence.EvidenceService;
import com.jangoyebo.core.evidence.TransactionEvidenceRepository;
import com.jangoyebo.core.loan.LoanRepaymentRepository;
import com.jangoyebo.core.loan.LoanRepository;
import com.jangoyebo.core.settlement.SettlementService;
import com.jangoyebo.core.settlement.SettlementTransactionRepository;
import com.jangoyebo.core.transaction.TransactionClassificationHistoryRepository;
import com.jangoyebo.core.transaction.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 계좌 삭제 (API 7.2 {@code POST /accounts/{accountId}/purge}).
 *
 * <p><b>연동 해제와 다른 일이다.</b> 해제({@link AccountService#disconnect})는 계좌를
 * {@code DISCONNECTED} 로 옮기고 거래를 보존한다 — 되돌릴 수 있는 것이 그쪽의 값어치다.
 * 이쪽은 계좌와 거래를 실제로 지운다. <b>되돌릴 수 없다.</b>
 *
 * <p><b>둘은 경쟁하지 않고 이어진다.</b> {@code CONNECTED} 계좌는 지울 수 없어
 * ({@link #assertNotConnected}) 해제가 앞 단계가 된다 — 되돌릴 수 있는 쪽을 먼저 거치게
 * 하는 것이 부수효과이고, 본래 목적은 자동 반영 워커와의 경합을 닫는 것이다.
 *
 * <h2>순서가 전부다</h2>
 *
 * <p>계좌를 가리키는 외래키가 다섯, 거래를 가리키는 것이 여섯이고 {@code ON DELETE CASCADE} 는
 * 하나도 걸려 있지 않다. 한 칸만 어긋나도 제약 위반으로 트랜잭션이 통째로 되감긴다.
 *
 * <p><b>DB 에 CASCADE 를 깔지 않은 이유.</b> 표마다 「지운다」와 「연결만 끊는다」가 갈린다 —
 * 대출과 상환 회차는 남기고 연결만 끊어야 하고(빚은 계좌와 함께 사라지지 않는다), 증빙은 행을
 * 남기고, 예정 거래는 상태를 되돌린다. CASCADE 는 이 구분을 표현하지 못할 뿐 아니라, 한 번
 * 깔면 <b>앞으로 모든 삭제가 조용히 번진다.</b>
 *
 * <h2>{@code forecast} 는 여기서 직접 못 지운다</h2>
 *
 * <p>{@code core → forecast} 참조가 막혀 있어({@code package-info} 단방향 규칙) 예정 거래·패턴을
 * 여기서 지울 수 없다. {@link AccountPurgeParticipant} 로 뒤집어 부른다 — 사정은 그 인터페이스
 * 머리말에 있다.
 */
@Service
public class AccountPurgeService {

    private static final Logger log = LoggerFactory.getLogger(AccountPurgeService.class);

    private final AccountRepository accounts;
    private final AccountVerificationStore verificationStore;
    private final TransactionRepository transactions;
    private final TransactionClassificationHistoryRepository classificationHistory;
    private final TransactionEvidenceRepository evidenceLinks;
    private final EvidenceService evidenceService;
    private final SettlementTransactionRepository settlementLinks;
    private final SettlementService settlementService;
    private final LoanRepository loans;
    private final LoanRepaymentRepository loanRepayments;
    private final List<AccountPurgeParticipant> participants;
    private final BusinessGuard guard;
    private final ApplicationEventPublisher events;

    public AccountPurgeService(AccountRepository accounts,
                               AccountVerificationStore verificationStore,
                               TransactionRepository transactions,
                               TransactionClassificationHistoryRepository classificationHistory,
                               TransactionEvidenceRepository evidenceLinks,
                               EvidenceService evidenceService,
                               SettlementTransactionRepository settlementLinks,
                               SettlementService settlementService,
                               LoanRepository loans,
                               LoanRepaymentRepository loanRepayments,
                               List<AccountPurgeParticipant> participants,
                               BusinessGuard guard,
                               ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.verificationStore = verificationStore;
        this.transactions = transactions;
        this.classificationHistory = classificationHistory;
        this.evidenceLinks = evidenceLinks;
        this.evidenceService = evidenceService;
        this.settlementLinks = settlementLinks;
        this.settlementService = settlementService;
        this.loans = loans;
        this.loanRepayments = loanRepayments;
        this.participants = participants;
        this.guard = guard;
        this.events = events;
    }

    /**
     * 계좌와 그 계좌에서 비롯된 모든 것을 지운다.
     *
     * @param accountId           지울 계좌
     * @param confirm             계좌 별칭. 오조작 방어이며 틀리면 {@code 400}
     * @param purgeOrphanEvidence 이 계좌 거래에만 붙어 있던 증빙도 지운 것으로 표시할지.
     *                            <b>기본은 {@code false}</b> — 증빙은 계좌가 아니라 사장님 것이다
     * @return 무엇을 얼마나 지웠는지. 화면이 "거래 N건을 지웠습니다"로 되짚어 준다
     */
    @Transactional
    public PurgeResult purge(Long accountId, String confirm, boolean purgeOrphanEvidence) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        // 직접 경로(/accounts/{id})는 @CurrentBusiness 자동 검증이 걸리지 않는다.
        guard.assertOwned(account);
        assertNotConnected(account);
        assertConfirmed(account, confirm);

        Long businessId = account.getBusinessId();

        // 1. 계좌 밖에 있는 것들 — 연결만 끊는다.
        //    빚과 상환 일정은 계좌가 사라져도 남는다. 지우면 곡선에서 나갈 돈이 빠져
        //    없는 여유를 있다고 보여준다.
        int repaymentsDetached = loanRepayments.detachTransactionsOfAccount(accountId);
        int loansDetached = loans.detachWithdrawalAccount(accountId);

        // 2. forecast — 예정 거래·실행 원장·반복 패턴.
        //    거래를 지우기 전에 끝나야 한다. 그쪽이 core.transaction 을 외래키로 잡고 있다.
        participants.forEach(p -> p.purgeAccount(businessId, accountId));

        // 3. 정산 — 배분을 지운다. 영향받은 대사 id 를 먼저 받아 둔다.
        //    지운 뒤에는 어느 대사가 흔들렸는지 되짚을 방법이 없다.
        List<Long> affectedSettlements = settlementLinks.findSettlementIdsOfAccount(accountId);
        int allocationsRemoved = settlementLinks.deleteAllocationsOfAccount(accountId);

        // 4. 증빙 — 연결만 끊는다. 행과 파일은 남는다.
        //    고아 후보도 링크를 지우기 전에 골라야 한다. 지운 뒤에는 무엇이 고아였는지 모른다.
        List<Long> orphanEvidence = purgeOrphanEvidence
                ? evidenceLinks.findEvidenceOnlyLinkedToAccount(accountId)
                : List.of();
        int linksRemoved = evidenceLinks.deleteLinksOfAccount(accountId);
        int evidenceMarked = evidenceService.softDeleteOrphans(orphanEvidence);

        // 5. 거래와 그 분류 이력.
        int historyRemoved = classificationHistory.deleteByAccountId(accountId);
        int transactionsRemoved = transactions.deleteByAccountId(accountId);

        // 6. 계좌.
        accounts.delete(account);
        verificationStore.clear(accountId);

        // 배분이 빠진 대사의 상태·알림을 맞춘다. 배분을 지운 뒤라야 남은 것이 정확하다.
        settlementService.refreshAfterAllocationsRemoved(affectedSettlements);

        // 커밋되는 트랜잭션 안에서 발행한다 — 듣는 쪽이 AFTER_COMMIT 이라 밖에서 쏘면 버려진다.
        events.publishEvent(TransactionsChanged.byAccountDeletion(businessId, accountId));

        log.info("계좌 {} 삭제 — 거래 {}, 분류 이력 {}, 증빙 연결 {}(고아 표시 {}), 정산 배분 {}, "
                        + "상환 회차 {}, 대출 {}",
                accountId, transactionsRemoved, historyRemoved, linksRemoved, evidenceMarked,
                allocationsRemoved, repaymentsDetached, loansDetached);

        return new PurgeResult(transactionsRemoved, linksRemoved, evidenceMarked,
                allocationsRemoved, affectedSettlements.size());
    }

    /**
     * 살아 있는 연결은 먼저 끊게 한다 ({@code 409 ACCOUNT_STATE_CONFLICT}).
     *
     * <p><b>친절이 아니라 경합을 닫는 장치다.</b> 자동 반영 워커가 계좌를 고르는 조건이
     * {@code auto_execute_planned = true AND status = 'CONNECTED'} 라
     * ({@code AccountExecutionService#autoExecuteAccounts}), {@code CONNECTED} 계좌를 지우는
     * 동안 워커가 <b>금융망에 실제로 출금을 보낼 수 있다.</b> 우리 트랜잭션이 되감겨도
     * 나간 돈은 돌아오지 않는다 — DB 밖의 부수효과다.
     *
     * <p>{@link Account#disconnect()} 가 {@code autoExecutePlanned} 를 함께 끄므로, 해제를
     * 거치게 하면 그 경로가 <b>구조적으로</b> 닫힌다. 화면에서만 막으면 API 를 직접 부르는
     * 쪽에는 창이 그대로 열려 있다.
     *
     * <p><b>{@code DISCONNECTED} 로 좁히지 않는다.</b> 인증 대기·만료 계좌는 동기화도 자동
     * 반영도 대상이 아니라(둘 다 {@code CONNECTED} 를 요구한다) 막을 이유가 없다. 계좌번호를
     * 잘못 쳐서 인증 대기에 걸린 계좌에까지 「연결 해제」를 먼저 시키는 것은,
     * <b>연결된 적 없는 계좌의 연결을 끊으라는 말</b>이 된다.
     *
     * <p>동기화 Job 은 여기서 막지 않아도 안전하다. {@code AccountSyncService#begin} 이
     * 계좌가 없으면 {@code ACCOUNT_NOT_FOUND} 로 끝내고, 구간별 INSERT 는 외래키에 막혀
     * 되감기므로 고아 거래가 남지 않는다.
     */
    private void assertNotConnected(Account account) {
        if (account.isConnected()) {
            throw new BusinessException(ErrorCode.ACCOUNT_STATE_CONFLICT,
                    "연결된 계좌는 바로 지울 수 없습니다. 연동을 먼저 해제해 주세요.");
        }
    }

    /**
     * 계좌 별칭을 되받아 오조작을 막는다.
     *
     * <p>돌이킬 수 없는 삭제라 <b>버튼 하나로 일어나서는 안 된다.</b>
     *
     * <p><b>계좌번호를 묻지 않는 이유.</b> 화면에는 마스킹밖에 없고({@code accountNoMasked})
     * 그 마스킹은 <b>앞 3자리와 뒤 3자리를 남긴다</b> — {@code 110-123-456789} 가
     * {@code 110-***-***789} 다. 「뒤 몇 자리」를 물으면 사용자가 보는 숫자가 앞뒤로 갈려 있어
     * 무엇을 적으라는 건지 모호하고, 마스킹에서 숫자만 뽑으면 앞 3자리까지 딸려 와
     * <b>사용자가 화면에서 읽을 수 없는 값</b>이 된다. 별칭은 사용자가 직접 지었고 목록에
     * 그대로 떠 있다.
     *
     * <p>앞뒤 공백은 흘린다 — 이것은 비밀이 아니라 「같은 것을 두 번 말하게 하는」 장치이고,
     * 공백 하나로 막으면 확인이 아니라 방해가 된다.
     *
     * <p>{@code ACCOUNT_NOT_FOUND} 가 아니라 {@code VALIDATION_FAILED} 다 — 여기 오는 사람은
     * 이미 소유가 확인된 계좌를 보고 있고, 틀린 것은 확인 입력이지 계좌가 아니다.
     */
    private void assertConfirmed(Account account, String confirm) {
        if (confirm == null || !account.getName().strip().equals(confirm.strip())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "계좌 별칭이 일치하지 않습니다.");
        }
    }

    /** 무엇을 얼마나 지웠는지. 화면이 사용자에게 되짚어 준다. */
    public record PurgeResult(int transactions, int evidenceLinks, int evidenceMarkedDeleted,
                              int settlementAllocations, int settlementsRefreshed) {
    }
}
