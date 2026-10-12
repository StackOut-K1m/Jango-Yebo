package com.jangoyebo.core.evidence;

import com.jangoyebo.NeedsDocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 증빙 저장 계층 (P0-10 ①).
 *
 * <p><b>엔드포인트 전에 이것부터 검증한다.</b> {@code Evidence} 매핑과 목록 쿼리가
 * 실제 PostgreSQL 에서 도는지를 먼저 굳혀 두면, 업로드 API 를 붙일 때 실패 원인이
 * 파일 처리 쪽으로 좁혀진다.
 *
 * <p>특히 {@code search} 의 {@code linked} 필터가 위험하다 — 연결 여부를 조인으로 판정하면
 * 연결이 여러 건인 증빙이 <b>중복 행으로 나와 목록 개수가 틀린다.</b> 서브쿼리로 쓴 것이
 * 실제로 그 문제를 피하는지 확인한다.
 */
@NeedsDocker
@SpringBootTest
@Transactional
class EvidencePersistenceTests {

    @Autowired private EvidenceRepository evidences;
    @Autowired private TransactionEvidenceRepository links;
    @Autowired private JdbcTemplate jdbc;

    private Long businessId;
    private Long userId;
    private Long accountId;

    @BeforeEach
    void setUp() {
        userId = jdbc.queryForObject("""
                INSERT INTO core.users (email, password_hash, name)
                VALUES (?, 'x', '증빙') RETURNING id
                """, Long.class, "ev-" + System.nanoTime() + "@test.local");
        businessId = jdbc.queryForObject("""
                INSERT INTO core.business (owner_user_id, name) VALUES (?, '가게') RETURNING id
                """, Long.class, userId);
        accountId = jdbc.queryForObject("""
                INSERT INTO core.account
                    (business_id, bank_code, account_number_encrypted, account_number_hash,
                     account_number_masked, name)
                VALUES (?, '088', 'enc', ?, '***1234', '주거래') RETURNING id
                """, Long.class, businessId, "hash-" + System.nanoTime());
    }

    /** 업로드 직후 상태는 QUEUED 이고 종류를 안 주면 OTHER 다. */
    @Test
    void savesUploadedEvidenceWithDefaults() {
        Evidence saved = evidences.save(upload(null, "receipt.jpg", "evidences/1.jpg"));
        evidences.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEvidenceType()).isEqualTo(EvidenceType.OTHER);
        assertThat(saved.getProcessingStatus()).isEqualTo(EvidenceStatus.QUEUED);
        assertThat(saved.getIssuedOn()).isNull();
    }

    /** 8종·6종 enum 값이 DB CHECK 과 맞는지 — 하나라도 어긋나면 삽입이 거부된다. */
    @Test
    void acceptsEveryEnumValue() {
        int i = 0;
        for (EvidenceType type : EvidenceType.values()) {
            evidences.save(upload(type, "f" + i + ".jpg", "evidences/t" + i++ + ".jpg"));
        }
        for (EvidenceStatus status : EvidenceStatus.values()) {
            Evidence e = evidences.save(upload(null, "s.jpg", "evidences/s" + i++ + ".jpg"));
            applyStatus(e, status);
        }
        evidences.flush();

        assertThat(evidences.findAll()).hasSizeGreaterThanOrEqualTo(
                EvidenceType.values().length + EvidenceStatus.values().length);
    }

    /** 같은 storage_key 를 두 행이 가리키면 한쪽을 지울 때 다른 쪽 파일이 사라진다. */
    @Test
    void rejectsDuplicateStorageKey() {
        evidences.save(upload(null, "a.jpg", "evidences/same.jpg"));
        evidences.flush();

        assertThatThrownBy(() -> {
            evidences.save(upload(null, "b.jpg", "evidences/same.jpg"));
            evidences.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 추출 완료와 사용자 확정을 나눈다 — 도메인 규칙 4. */
    @Test
    void separatesExtractionFromConfirmation() {
        Evidence e = evidences.save(upload(null, "receipt.jpg", "evidences/ex.jpg"));

        e.applyExtraction(EvidenceType.RECEIPT, LocalDate.of(2026, 8, 25), Boolean.TRUE);
        assertThat(e.getProcessingStatus()).isEqualTo(EvidenceStatus.EXTRACTED);
        assertThat(e.getEvidenceType()).isEqualTo(EvidenceType.RECEIPT);
        assertThat(e.getIssuedOn()).isEqualTo(LocalDate.of(2026, 8, 25));

        e.confirm();
        assertThat(e.getProcessingStatus()).isEqualTo(EvidenceStatus.CONFIRMED);
    }

    /**
     * {@code linked} 필터의 핵심 — <b>연결이 두 건인 증빙이 한 행으로 나와야 한다.</b>
     *
     * <p>조인으로 판정했다면 여기서 2가 되어 목록 개수가 틀린다.
     */
    @Test
    void linkedFilterDoesNotDuplicateRows() {
        Evidence linkedTwice = evidences.save(upload(null, "split.jpg", "evidences/split.jpg"));
        Evidence unlinked = evidences.save(upload(null, "alone.jpg", "evidences/alone.jpg"));
        evidences.flush();

        long tx1 = insertTransaction("분할 1");
        long tx2 = insertTransaction("분할 2");
        links.save(TransactionEvidence.linkedByUser(tx1, linkedTwice.getId(), userId));
        links.save(TransactionEvidence.linkedByUser(tx2, linkedTwice.getId(), userId));
        links.flush();

        var linked = evidences.search(businessId, null, null, null, null,
                Boolean.TRUE, null, PageRequest.of(0, 20));
        assertThat(linked.getTotalElements()).isEqualTo(1);
        assertThat(linked.getContent().get(0).getId()).isEqualTo(linkedTwice.getId());

        var notLinked = evidences.search(businessId, null, null, null, null,
                Boolean.FALSE, null, PageRequest.of(0, 20));
        assertThat(notLinked.getTotalElements()).isEqualTo(1);
        assertThat(notLinked.getContent().get(0).getId()).isEqualTo(unlinked.getId());
    }

    /**
     * 거절된 추천은 연결로 세지 않는다.
     *
     * <p>{@code CANDIDATE}·{@code REJECTED} 행이 있어도 <b>증빙 없는 것</b>이다 —
     * 이걸 연결로 세면 "증빙 누락" 목록에서 빠져 사용자가 못 찾는다.
     */
    @Test
    void candidateAndRejectedDoNotCountAsLinked() {
        Evidence e = evidences.save(upload(null, "cand.jpg", "evidences/cand.jpg"));
        evidences.flush();
        long tx = insertTransaction("후보");

        links.save(TransactionEvidence.candidate(tx, e.getId(), new BigDecimal("0.7")));
        links.flush();

        assertThat(evidences.search(businessId, null, null, null, null,
                Boolean.FALSE, null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);

        TransactionEvidence link = links.findByTransactionIdAndEvidenceId(tx, e.getId()).orElseThrow();
        link.reject(userId);
        links.flush();

        assertThat(evidences.search(businessId, null, null, null, null,
                Boolean.FALSE, null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);

        link.accept(userId);
        links.flush();

        assertThat(evidences.search(businessId, null, null, null, null,
                Boolean.TRUE, null, PageRequest.of(0, 20)).getTotalElements()).isEqualTo(1);
    }

    /** 상태·종류 필터도 함께 걸린다. */
    @Test
    void filtersByStatusAndType() {
        Evidence receipt = evidences.save(upload(EvidenceType.RECEIPT, "r.jpg", "evidences/r.jpg"));
        evidences.save(upload(EvidenceType.CONTRACT, "c.pdf", "evidences/c.pdf"));
        receipt.applyExtraction(null, null, null);
        evidences.flush();

        assertThat(evidences.search(businessId, null, null,
                EvidenceStatus.EXTRACTED, null, null, null, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(1);
        assertThat(evidences.search(businessId, null, null,
                null, EvidenceType.CONTRACT, null, null, PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(1);
    }

    /**
     * 기간은 발행일이 아니라 업로드 시각 기준이다.
     *
     * <p>발행일은 추출 후에 채워지므로 업로드 직후에는 null 이고, null 을 기간 조건에 걸면
     * <b>갓 올린 증빙이 목록에서 사라진다.</b>
     */
    @Test
    void filtersByUploadInstantNotIssuedDate() {
        evidences.save(upload(null, "today.jpg", "evidences/today.jpg"));
        evidences.flush();

        LocalDate today = LocalDate.now();
        var found = evidences.search(businessId,
                today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(),
                today.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant(),
                null, null, null, null, PageRequest.of(0, 20));

        // issued_on 이 null 인데도 걸려야 한다.
        assertThat(found.getTotalElements()).isEqualTo(1);
        assertThat(found.getContent().get(0).getIssuedOn()).isNull();
    }

    /** 같은 쌍을 두 번 연결할 수 없다({@code uk_te_pair}). */
    @Test
    void rejectsDuplicateLink() {
        Evidence e = evidences.save(upload(null, "dup.jpg", "evidences/dup.jpg"));
        evidences.flush();
        long tx = insertTransaction("중복 연결");

        links.save(TransactionEvidence.linkedByUser(tx, e.getId(), userId));
        links.flush();

        assertThatThrownBy(() -> {
            links.save(TransactionEvidence.linkedByUser(tx, e.getId(), userId));
            links.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 사용자가 직접 연결한 것은 matchScore 가 null 이다 — 0 이면 "일치도 0%"와 헷갈린다. */
    @Test
    void userLinkHasNoMatchScore() {
        Evidence e = evidences.save(upload(null, "u.jpg", "evidences/u.jpg"));
        evidences.flush();
        long tx = insertTransaction("직접 연결");

        TransactionEvidence link = links.save(
                TransactionEvidence.linkedByUser(tx, e.getId(), userId));
        links.flush();

        assertThat(link.getMatchScore()).isNull();
        assertThat(link.getMatchStatus()).isEqualTo(MatchStatus.LINKED);
        assertThat(link.getConfirmedBy()).isEqualTo(userId);
        assertThat(link.getCreatedAt()).isNotNull();
    }

    // --- helpers -------------------------------------------------------------

    private Evidence upload(EvidenceType type, String filename, String storageKey) {
        return Evidence.uploaded(businessId, userId, type, filename, storageKey, "image/jpeg", 1024L);
    }

    private void applyStatus(Evidence e, EvidenceStatus status) {
        switch (status) {
            case QUEUED -> { }
            case PROCESSING -> e.markProcessing();
            case EXTRACTED -> e.applyExtraction(null, null, null);
            case CONFIRMED -> e.confirm();
            case FAILED -> e.markFailed();
            case SKIPPED -> e.markSkipped();
        }
    }

    private long insertTransaction(String description) {
        return jdbc.queryForObject("""
                INSERT INTO core.transaction
                    (business_id, account_id, external_transaction_id, source_type, occurred_at,
                     transaction_date, flow_type, amount, description, classification_status,
                     needs_confirmation)
                VALUES (?, ?, ?, 'FIN_API', now(), current_date, 'WITHDRAWAL', 10000, ?,
                        'CONFIRMED', FALSE) RETURNING id
                """, Long.class, businessId, accountId, "ext-" + System.nanoTime(), description);
    }

    @SuppressWarnings("unused")
    private static Instant unused() {
        return Instant.now();
    }
}
