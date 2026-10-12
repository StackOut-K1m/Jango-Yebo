package com.jangoyebo.core.evidence;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.storage.UploadStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;

/**
 * 증빙 원본 파일 (API 13.1 {@code fileUrl}).
 *
 * <p><b>P0-10 의 반쪽이 비어 있었다.</b> 올리기·추출·검토·매칭은 다 되는데 원본을 볼 경로가
 * 없어, 검토 화면이 「추출된 값이 맞나」를 눈으로 대조할 수 없었다. 파일은 줄곧
 * {@code /data/uploads} 에 있었고 내주는 길만 없었다.
 *
 * <p><b>기록은 있는데 파일이 없는 경우를 따로 답한다.</b> {@code EVIDENCE_NOT_FOUND} 로 뭉개면
 * 화면은 「증빙 자체가 없다」로 읽어 목록에서 지워야 할지 판단하지 못한다. 사장님이 할 수 있는
 * 일도 다르다 — 이쪽은 다시 올리는 것이다.
 */
@Service
public class EvidenceFileService {

    private final EvidenceRepository evidences;
    private final UploadStorage storage;
    private final BusinessGuard guard;

    public EvidenceFileService(EvidenceRepository evidences, UploadStorage storage,
                               BusinessGuard guard) {
        this.evidences = evidences;
        this.storage = storage;
        this.guard = guard;
    }

    /**
     * 내보낼 파일 하나.
     *
     * <p><b>스트림을 여기서 열지 않는다.</b> 트랜잭션 안에서 열면 응답을 다 쓰기 전에 닫히거나,
     * 반대로 커넥션을 파일 전송 시간만큼 붙잡는다. 컨트롤러가 받아서 연다.
     */
    @Transactional(readOnly = true)
    public StoredFile locate(Long evidenceId) {
        Evidence evidence = evidences.findById(evidenceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND));
        // 직접 경로다 — Hibernate 필터가 걸리지 않으므로 명시 검증이 필요하다.
        guard.assertOwned(evidence);
        // 지운 증빙은 없는 것과 같다(13.5). 상세가 404 인 것과 같은 규칙이라, 목록에서 사라진
        // 증빙의 미리보기만 살아 있는 일이 없다.
        if (evidence.isDeleted()) {
            throw new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND);
        }
        if (!storage.exists(evidence.getStorageKey())) {
            throw new BusinessException(ErrorCode.EVIDENCE_FILE_MISSING);
        }
        return new StoredFile(evidence.getStorageKey(), evidence.getOriginalFilename(),
                evidence.getMimeType(), storage.sizeOf(evidence.getStorageKey()));
    }

    public InputStream open(String storageKey) {
        return storage.openRead(storageKey);
    }

    public record StoredFile(String storageKey, String fileName, String mimeType, long size) {
    }
}
