package com.jangoyebo.core.evidence;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.transaction.Transaction;
import com.jangoyebo.core.transaction.TaxReviewService;
import com.jangoyebo.core.transaction.TransactionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 증빙 ↔ 거래 매칭 (13.4).
 *
 * <p>증빙을 읽어 놓고도 <b>어느 거래의 근거인지 말할 수 없으면 쓸 데가 없다.</b> 신고 자료는
 * "이 지출에 이 영수증" 짝으로 나가지, 영수증 더미로 나가지 않는다.
 *
 * <p><b>점수는 사람이 고르는 것을 돕는 값이지 판정이 아니다.</b> 자동 연결은 아래 세 조건이
 * 모두 맞을 때만 켜진다(13.4 — 그 외에는 사용자 승인).
 */
@Service
public class EvidenceMatchService {

    /** 후보를 찾는 날짜 창(발행일 기준 ±). 카드 매입은 하루이틀 뒤에 찍히고, 주말이 끼면 더 밀린다. */
    private static final int WINDOW_DAYS = 3;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 신호별 가중치. 합이 1.0 이다.
     *
     * <p>금액이 가장 무겁다 — 날짜와 상호는 우연히 겹치지만 <b>원 단위까지 같은 금액은 잘 안
     * 겹친다.</b> 상호가 가장 가볍다: 통장 적요는 「서울우유광주」처럼 잘리고 붙어서 오고,
     * 영수증 상호는 「(주)서울우유 광주대리점」이라 글자 그대로는 거의 안 맞는다.
     */
    private static final BigDecimal W_AMOUNT = new BigDecimal("0.60");
    private static final BigDecimal W_SAME_DAY = new BigDecimal("0.25");
    private static final BigDecimal W_NEAR_DAY = new BigDecimal("0.10");
    private static final BigDecimal W_MERCHANT = new BigDecimal("0.15");

    private final EvidenceRepository evidenceRepository;
    private final EvidenceExtractionRepository extractionRepository;
    private final TransactionEvidenceRepository linkRepository;
    private final TransactionRepository transactionRepository;
    private final TaxReviewService taxReviewService;
    private final BusinessGuard guard;

    public EvidenceMatchService(EvidenceRepository evidenceRepository,
                                EvidenceExtractionRepository extractionRepository,
                                TransactionEvidenceRepository linkRepository,
                                TransactionRepository transactionRepository,
                                TaxReviewService taxReviewService,
                                BusinessGuard guard) {
        this.evidenceRepository = evidenceRepository;
        this.extractionRepository = extractionRepository;
        this.linkRepository = linkRepository;
        this.transactionRepository = transactionRepository;
        this.taxReviewService = taxReviewService;
        this.guard = guard;
    }

    /**
     * 매칭 후보 (13.4).
     *
     * <p>발행일을 모르면 후보를 낼 수 없다 — 창을 어디에 놓을지가 정해지지 않는다. 그때는
     * 빈 목록이고, 오류가 아니다(화면구성도 6.4 — 빈 상태는 throw 하지 않는다).
     */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(Long evidenceId) {
        Evidence evidence = find(evidenceId);
        LocalDate issued = issuedDateOf(evidence);
        if (issued == null) {
            return List.of();
        }

        BigDecimal amount = totalAmountOf(evidenceId);
        String supplier = supplierNameOf(evidenceId);

        Instant from = issued.minusDays(WINDOW_DAYS).atStartOfDay(KST).toInstant();
        Instant to = issued.plusDays(WINDOW_DAYS + 1L).atStartOfDay(KST).toInstant();

        List<Candidate> scored = transactionRepository
                .findForEvidenceMatch(evidence.getBusinessId(), from, to).stream()
                .map(tx -> score(tx, issued, amount, supplier))
                .sorted(Comparator.comparing(Candidate::score).reversed())
                .toList();

        return markAutoLinkEligible(scored);
    }

    /**
     * 거래 연결 (13.4).
     *
     * <p>이미 다른 거래에 붙어 있으면 {@code 409} 다 — 조용히 옮기지 않는다. 증빙 하나가 두
     * 거래의 근거일 수 없고, 옮기는 것은 사용자가 뗀 뒤에 다시 붙이는 일이어야 한다.
     * 같은 거래에 다시 붙이는 것은 성공으로 둔다(멱등).
     */
    @Transactional
    public void link(Long evidenceId, Long transactionId) {
        Evidence evidence = find(evidenceId);
        Transaction transaction = transactionRepository.findById(transactionId)
                .filter(tx -> tx.getBusinessId().equals(evidence.getBusinessId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));

        Optional<TransactionEvidence> existing = linkRepository.findByEvidenceId(evidenceId).stream()
                .filter(link -> link.getMatchStatus() == MatchStatus.LINKED)
                .findFirst();
        if (existing.isPresent()) {
            if (existing.get().getTransactionId().equals(transactionId)) {
                return;
            }
            throw new BusinessException(ErrorCode.EVIDENCE_ALREADY_LINKED);
        }

        linkRepository.save(TransactionEvidence.linkedByUser(
                transaction.getId(), evidenceId, guard.currentUserId()));
        // 8.7 자동 판정 — 사용자가 지정해 둔 값은 안에서 지켜진다.
        taxReviewService.refreshEvidenceJudgement(transaction.getId(), true);
    }

    /**
     * 연결 해제 (13.4).
     *
     * <p>붙어 있지 않아도 {@code 204} 다. 화면이 두 번 눌렀거나 다른 창에서 이미 뗐을 뿐인데
     * 오류를 띄울 이유가 없다.
     */
    @Transactional
    public void unlink(Long evidenceId) {
        find(evidenceId);
        List<TransactionEvidence> removed = linkRepository.findByEvidenceId(evidenceId).stream()
                .filter(link -> link.getMatchStatus() == MatchStatus.LINKED)
                .toList();
        removed.forEach(linkRepository::delete);
        linkRepository.flush();
        removed.forEach(link -> refreshJudgement(link.getTransactionId()));
    }

    /**
     * 8.7 자동 판정을 다시 건다.
     *
     * <p><b>거래 하나에 증빙이 여럿 붙을 수 있다.</b> 하나를 뗐다고 「증빙 누락」으로 바꾸면,
     * 아직 붙어 있는 다른 영수증이 없는 것처럼 보인다. 남은 것이 있는지 세고 나서 판정한다.
     */
    private void refreshJudgement(Long transactionId) {
        boolean stillHasEvidence = linkRepository.findByTransactionId(transactionId).stream()
                .anyMatch(link -> link.getMatchStatus() == MatchStatus.LINKED);
        taxReviewService.refreshEvidenceJudgement(transactionId, stillHasEvidence);
    }

    // ------------------------------------------------------------------ 점수

    private Candidate score(Transaction tx, LocalDate issued, BigDecimal amount, String supplier) {
        List<String> matchedBy = new java.util.ArrayList<>();
        BigDecimal score = BigDecimal.ZERO;

        if (amount != null && tx.getAmount() != null
                && amount.compareTo(tx.getAmount().abs()) == 0) {
            matchedBy.add("AMOUNT_EXACT");
            score = score.add(W_AMOUNT);
        }

        LocalDate txDate = tx.getOccurredAt().atZone(KST).toLocalDate();
        if (txDate.equals(issued)) {
            matchedBy.add("DATE_SAME_DAY");
            score = score.add(W_SAME_DAY);
        } else {
            matchedBy.add("DATE_NEAR");
            score = score.add(W_NEAR_DAY);
        }

        if (merchantSimilar(supplier, tx.getDescription())) {
            matchedBy.add("MERCHANT_SIMILAR");
            score = score.add(W_MERCHANT);
        }

        return new Candidate(tx.getId(), tx.getOccurredAt(), tx.getDescription(),
                tx.getAmount(), tx.getFlowType().name(), score, List.copyOf(matchedBy), false);
    }

    /**
     * 자동 연결 자격 — <b>금액이 정확히 맞고, 같은 날이고, 그런 후보가 하나뿐</b>일 때만.
     *
     * <p>유일성이 안전망이다. 같은 금액·같은 날 거래가 둘이면 어느 쪽인지 서버가 알 수 없고,
     * 그중 하나가 입금이고 하나가 출금인 경우도 여기서 같이 걸린다 — 방향을 따로 보지 않는
     * 이유다. 둘 다 후보로 내고 사람이 고른다.
     */
    private List<Candidate> markAutoLinkEligible(List<Candidate> scored) {
        List<Candidate> strong = scored.stream()
                .filter(c -> c.matchedBy().contains("AMOUNT_EXACT")
                        && c.matchedBy().contains("DATE_SAME_DAY"))
                .toList();
        if (strong.size() != 1) {
            return scored;
        }
        Long eligibleId = strong.get(0).transactionId();
        return scored.stream()
                .map(c -> c.transactionId().equals(eligibleId) ? c.eligible() : c)
                .toList();
    }

    /**
     * 통장 적요와 영수증 상호가 같은 곳을 가리키나.
     *
     * <p>글자 그대로 비교하면 거의 안 맞는다 — 적요는 「서울우유광주」로 잘려 오고 상호는
     * 「(주)서울우유 광주대리점」이다. 공백과 법인 표기를 걷어내고 <b>한쪽이 다른 쪽에
     * 들어 있는지</b>만 본다. 느슨하지만 가중치가 0.15 라 이것만으로 후보가 올라오지 않는다.
     */
    private boolean merchantSimilar(String supplier, String description) {
        String a = normalize(supplier);
        String b = normalize(description);
        if (a.length() < 2 || b.length() < 2) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\\s()\\[\\]·.,-]", "")
                .replace("주식회사", "")
                .replace("㈜", "")
                .replace("(주)", "");
    }

    // ------------------------------------------------------------------ 읽기

    private LocalDate issuedDateOf(Evidence evidence) {
        return evidence.getIssuedOn();
    }

    private BigDecimal totalAmountOf(Long evidenceId) {
        Object value = headerValue(evidenceId, "totalAmount");
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }

    private String supplierNameOf(Long evidenceId) {
        Object value = headerValue(evidenceId, "supplierName");
        return value instanceof String text ? text : null;
    }

    private Object headerValue(Long evidenceId, String key) {
        Map<String, Object> header = extractionRepository
                .findByEvidenceIdAndCurrentTrue(evidenceId)
                .map(EvidenceExtraction::getHeaderData)
                .orElse(null);
        return header == null ? null : header.get(key);
    }

    private Evidence find(Long evidenceId) {
        Evidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND));
        guard.assertOwned(evidence);
        if (evidence.isDeleted()) {
            throw new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND);
        }
        return evidence;
    }

    /** 매칭 후보 한 건 (13.4). */
    public record Candidate(Long transactionId,
                            Instant occurredAt,
                            String description,
                            BigDecimal amount,
                            String direction,
                            BigDecimal score,
                            List<String> matchedBy,
                            boolean autoLinkEligible) {

        Candidate eligible() {
            return new Candidate(transactionId, occurredAt, description, amount, direction,
                    score, matchedBy, true);
        }
    }
}
