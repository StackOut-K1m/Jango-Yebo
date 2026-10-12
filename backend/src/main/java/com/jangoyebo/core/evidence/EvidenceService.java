package com.jangoyebo.core.evidence;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import com.jangoyebo.core.storage.UploadStorage;
import com.jangoyebo.core.transaction.TaxReviewService;
import com.jangoyebo.core.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 증빙 업로드 (API 13.1, P0-10).
 *
 * <p>SRS 가 이 기능을 P0 에 둔 이유는 시점이다 — <b>신고 직전에 몰아서 모으면 누락·중복이
 * 생기므로 평소에 축적한다.</b> 그래서 거래 연결 없이도 올릴 수 있고, 연결은 나중에 붙인다.
 */
@Service
public class EvidenceService {

    private final EvidenceRepository evidenceRepository;
    private final TransactionEvidenceRepository linkRepository;
    private final TransactionRepository transactionRepository;
    private final UploadStorage storage;
    private final JobService jobService;
    private final TaxReviewService taxReviewService;
    private final BusinessGuard guard;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransactionEvidenceRepository linkRepository,
                           TransactionRepository transactionRepository,
                           UploadStorage storage,
                           JobService jobService,
                           TaxReviewService taxReviewService,
                           BusinessGuard guard) {
        this.evidenceRepository = evidenceRepository;
        this.linkRepository = linkRepository;
        this.transactionRepository = transactionRepository;
        this.storage = storage;
        this.jobService = jobService;
        this.taxReviewService = taxReviewService;
        this.guard = guard;
    }

    /**
     * 파일을 저장하고 추출 Job 을 건다.
     *
     * <p><b>순서가 중요하다.</b> 행을 먼저 만들고(키 확정), 파일을 쓰고, Job 을 건다.
     *
     * <ul>
     *   <li>파일을 먼저 쓰면 커밋이 실패했을 때 <b>아무도 가리키지 않는 파일</b>이 남는다.
     *       반대로 파일 쓰기가 실패하면 예외가 올라가 행이 롤백되므로 유령 행이 안 생긴다.</li>
     *   <li>Job 을 파일보다 먼저 걸면 워커가 <b>아직 없는 파일</b>을 읽으러 가서 400 이 난다 —
     *       워커는 {@code < 500} 을 영구 실패로 보므로 재시도로 낫지 않는다.</li>
     * </ul>
     *
     * @return 만들어진 증빙과 추출 Job
     */
    @Transactional
    public Uploaded upload(Long businessId, MultipartFile file,
                           EvidenceType typeHint, Long transactionId) {
        String extension = storage.extensionOf(file);
        Long userId = guard.currentUser().userId();

        Evidence evidence = evidenceRepository.save(Evidence.uploaded(
                businessId, userId, typeHint,
                originalNameOf(file),
                Evidence.pendingKey(UUID.randomUUID().toString()),
                file.getContentType(), file.getSize()));
        // id 를 받아야 키를 만들 수 있다.
        evidenceRepository.flush();
        evidence.assignStorageKey(storage.evidenceKey(businessId, evidence.getId(), extension));

        if (transactionId != null) {
            linkRepository.save(TransactionEvidence.linkedByUser(
                    requireOwnedTransaction(businessId, transactionId), evidence.getId(), userId));
        }

        storage.write(evidence.getStorageKey(), file);

        Job job = jobService.enqueue(businessId, userId, JobType.EVIDENCE_EXTRACTION, Map.of(
                "evidenceId", evidence.getId(),
                "fileObjectKey", evidence.getStorageKey(),
                "evidenceTypeHint", evidence.getEvidenceType().name()));

        return new Uploaded(evidence, job, transactionId);
    }


    /**
     * 재추출 (13.7). 사용자가 검토 화면에서 「이 결과가 틀렸다」고 했을 때 같은 증빙을
     * {@code THOROUGH} 로 다시 읽는다 (명세 20장 v1.17).
     *
     * <p><b>{@code EXTRACTED} 에서만 된다.</b> 확정된 증빙은 거절한다 — 재추출은 새 행을 넣고
     * 이전 행을 {@code is_current = false} 로 내리므로, 확정본이 조용히 내려가 사장님이 승인한
     * 값이 화면에서 사라진다. 확정을 되돌리는 경로가 먼저 있어야 그다음이다.
     *
     * <p><b>이전 추출을 여기서 내리지 않는다.</b> 내리는 일은 콜백이 도착해 새 결과를 저장할 때
     * 한다({@code EvidenceExtractionService.apply}). {@code THOROUGH} 는 실패를 감추지 않아
     * 1차 결과로 폴백하지 않으므로(20장), 재추출이 실패하면 이전 결과가 그대로 남아야 한다.
     *
     * <p>파일은 이미 있다. 업로드와 달리 저장소에 쓰지 않고 같은 {@code storageKey} 를 준다.
     *
     * @return 새로 건 추출 Job
     */
    @Transactional
    public Job reExtract(Long evidenceId) {
        Evidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND));
        // 13.2·13.3 과 같다 — 경로에 사업장이 없으므로 소유 검증은 여기서 한다.
        guard.assertOwned(evidence);

        if (evidence.getProcessingStatus() != EvidenceStatus.EXTRACTED) {
            throw new BusinessException(ErrorCode.EVIDENCE_STATE_CONFLICT);
        }

        Long userId = guard.currentUser().userId();
        evidence.markProcessing();

        return jobService.enqueue(evidence.getBusinessId(), userId, JobType.EVIDENCE_EXTRACTION,
                Map.of(
                        "evidenceId", evidence.getId(),
                        "fileObjectKey", evidence.getStorageKey(),
                        "evidenceTypeHint", evidence.getEvidenceType().name(),
                        // 재추출에서만 올린다. 전건이면 이미지 토큰이 +52% 다(20장 v1.17).
                        "extractionMode", "THOROUGH"));
    }


    /**
     * 증빙 종류 수정 (13.5).
     *
     * <p>추출이 종류를 잘못 추정했을 때 사람이 고친다. 모르는 값이면 거절한다 —
     * 업로드 때의 {@code evidenceTypeHint} 와 달리 이건 <b>사용자가 확정하는 값</b>이라
     * 조용히 비우면 고친 줄 알고 넘어간다.
     */
    @Transactional
    public void changeType(Long evidenceId, EvidenceType type) {
        if (type == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Evidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND));
        guard.assertOwned(evidence);
        evidence.changeType(type);
    }


    /**
     * 증빙 삭제 (13.5). <b>소프트 삭제다.</b>
     *
     * <p>행도 파일도 지우지 않는다. 지우면 {@code EVIDENCE_CORRECTION} 이 함께 사라지는데,
     * 그 표가 실사용 정확도를 얻는 유일한 원천이다 — 실험 세트 정확도는 우리가 고른 이미지에
     * 대한 값이라 이것을 대신하지 못한다. 파일은 명세 13.5 가 아카이브에 남기라고 정했다.
     *
     * <p><b>거래 연결은 실제로 끊는다.</b> 지운 증빙이 거래에 붙어 있으면 화면이 "근거 있음"
     * 으로 세고, 신고 자료에 없는 증빙이 근거로 딸려 나간다. 흔적은 증빙 쪽에만 남긴다.
     *
     * <p>이미 지워진 것을 또 지워도 {@code 204} 다 — 화면이 두 번 눌렀을 뿐이다.
     */
    @Transactional
    public void softDelete(Long evidenceId) {
        Evidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND));
        guard.assertOwned(evidence);
        if (evidence.isDeleted()) {
            return;
        }
        var removed = linkRepository.findByEvidenceId(evidenceId).stream().toList();
        removed.forEach(linkRepository::delete);
        linkRepository.flush();
        evidence.softDelete(guard.currentUserId(), Instant.now());
        // 8.7 자동 판정 — 뗀 거래에 다른 증빙이 남아 있으면 「확인 완료」가 유지돼야 한다.
        removed.stream()
                .map(TransactionEvidence::getTransactionId)
                .distinct()
                .forEach(txId -> taxReviewService.refreshEvidenceJudgement(txId,
                        linkRepository.findByTransactionId(txId).stream()
                                .anyMatch(link -> link.getMatchStatus() == MatchStatus.LINKED)));
    }

    /**
     * 남의 거래에 증빙을 붙일 수 없다. {@code @CurrentBusiness} 가 사업장 소유는 이미
     * 검증했으므로 여기서는 <b>그 거래가 이 사업장 것인지</b>만 본다.
     */
    private Long requireOwnedTransaction(Long businessId, Long transactionId) {
        return transactionRepository.findById(transactionId)
                .filter(tx -> tx.getBusinessId().equals(businessId))
                .map(tx -> transactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));
    }

    /** 파일명이 없거나 경로가 섞여 오면 마지막 조각만 쓴다. 표시용이라 저장 경로와 무관하다. */
    private String originalNameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "증빙";
        }
        String last = name.replace('\\', '/');
        last = last.substring(last.lastIndexOf('/') + 1);
        return last.length() <= 255 ? last : last.substring(last.length() - 255);
    }

    public record Uploaded(Evidence evidence, Job job, Long linkedTransactionId) {
    }

    /**
     * 계좌 삭제로 고아가 된 증빙을 지운 것으로 표시한다 (API 7.2 purge).
     *
     * <p><b>사용자가 명시로 켰을 때만 불린다.</b> 기본값은 남기는 쪽이다 — 증빙은 계좌가 아니라
     * 사장님이 올린 것이고, 계좌를 지운다고 영수증이 무효가 되지는 않는다.
     *
     * <p>{@link #softDelete} 와 달리 <b>연결을 끊지 않는다.</b> 호출부가 거래를 통째로 지우는
     * 길이라 연결은 이미 벌크로 사라졌고, 그래서 {@code refreshEvidenceJudgement} 도 부르지
     * 않는다 — 판정을 갱신할 거래가 곧 없어진다. 넘어오는 것은
     * {@code TransactionEvidenceRepository#findEvidenceOnlyLinkedToAccount} 가 고른,
     * <b>이 계좌 밖으로 연결이 없는</b> 증빙뿐이라 살아 있는 거래의 근거를 건드리지 않는다.
     *
     * <p>행도 파일도 지우지 않는 이유는 {@link #softDelete} 와 같다 — {@code EVIDENCE_CORRECTION}
     * 이 실사용 정확도의 유일한 원천이다.
     */
    @Transactional
    public int softDeleteOrphans(Collection<Long> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return 0;
        }
        Long userId = guard.currentUserId();
        Instant now = Instant.now();
        int marked = 0;
        for (Evidence evidence : evidenceRepository.findAllById(evidenceIds)) {
            if (evidence.isDeleted()) {
                continue;
            }
            evidence.softDelete(userId, now);
            marked++;
        }
        return marked;
    }
}
