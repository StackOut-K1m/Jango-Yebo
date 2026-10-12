package com.jangoyebo.core.storage;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

/**
 * 업로드 원본 파일을 디스크에 쓴다 — 증빙(13.1)과 매출 인쇄물(14.7)이 함께 쓴다.
 *
 * <p><b>호스트 경로를 코드가 알면 안 된다</b>(인프라 제약 §4.1 #9). 컨테이너 내부 고정 경로
 * {@code /data/uploads} 만 보고, 그 아래 상대 경로가 {@code EVIDENCE.storage_key} 다.
 * 워커는 같은 디렉터리를 {@code :ro} 로 붙여 같은 키로 읽는다.
 *
 * <p>키 형식 두 가지를 합의 ⑧-1 에서 확정했다.
 *
 * <pre>
 * evidences/{businessId}/{evidenceId}.{ext}
 * product-sales/{businessId}/{jobId}-{n}.{ext}
 * </pre>
 *
 * <p>사업장으로 한 단계 나눈 것은 운영 중에 사람이 찾기 쉽고 사업장 단위 정리·용량 산정이
 * 되기 때문이다. <b>원본 파일명을 키에 넣지 않는다</b> — 한글·공백·경로 문자가 섞이면
 * {@code uk_evidence_storage} 와 URL 인코딩에서 번거로워진다. 원본 이름은
 * {@code EVIDENCE.original_filename} 에 따로 있다(매출 인쇄물은 원본 이름을 남기지 않는다 —
 * 저장하는 것이 파일이 아니라 <b>읽어낸 숫자</b>라 되짚을 일이 없다).
 *
 * <p>⚠ <b>워커는 키를 해석하지 않는다</b>(계약 v1.17). {@code STORAGE_ROOT} 아래 상대
 * 경로로만 보고 절대 경로·{@code ..}·드라이브 문자를 거절한다. 즉 위 형식은 계약이 아니라
 * <b>운영 편의</b>다 — 그래도 여기서만 만들어야 두 곳이 갈라지지 않는다.
 */
@Component
public class UploadStorage {

    private static final Logger log = LoggerFactory.getLogger(UploadStorage.class);

    /** 명세 13.1 — JPG/PNG/PDF, 최대 10MB. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /**
     * 확장자는 <b>선언된 MIME 에서 뽑는다.</b> 사용자가 준 파일명을 믿으면 `.jpg` 로 올린
     * 실행 파일이 그대로 저장된다. 워커도 확장자로 형식을 판단하지 않고 바이트를 읽는다.
     */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "application/pdf", "pdf");

    /** 브라우저·모바일이 jpeg 대신 보내는 것들. 같은 형식이다. */
    private static final Set<String> JPEG_ALIASES = Set.of("image/jpg", "image/pjpeg");

    private final Path root;

    public UploadStorage(@Value("${jango.storage.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    /**
     * 확장자를 미리 정한다 — 키에 들어가는 {@code evidenceId}·{@code jobId} 를 받기 전에는
     * 키를 만들 수 없어서 검증만 먼저 떼어 둔다.
     */
    public String extensionOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.EVIDENCE_FILE_UNSUPPORTED, "빈 파일입니다.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.EVIDENCE_FILE_UNSUPPORTED,
                    "파일이 10MB 를 넘습니다.");
        }
        String mime = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (JPEG_ALIASES.contains(mime)) {
            mime = "image/jpeg";
        }
        String extension = EXTENSIONS.get(mime);
        if (extension == null) {
            throw new BusinessException(ErrorCode.EVIDENCE_FILE_UNSUPPORTED,
                    "JPG·PNG·PDF 만 올릴 수 있습니다.");
        }
        return extension;
    }

    /** 증빙 한 장 (13.1). */
    public String evidenceKey(Long businessId, Long evidenceId, String extension) {
        return "evidences/%d/%d.%s".formatted(businessId, evidenceId, extension);
    }

    /**
     * 매출 인쇄물 한 장 (14.7). 여러 장이 한 Job 에 묶이므로 <b>순번이 붙는다.</b>
     *
     * @param index 0-based. 사용자가 올린 순서이고, 워커가 장 경계를 이어 붙일 때 그 순서를 쓴다
     */
    public String productSalesKey(Long businessId, Long jobId, int index, String extension) {
        return "product-sales/%d/%d-%d.%s".formatted(businessId, jobId, index + 1, extension);
    }

    /**
     * 신고 준비자료 패키지 (14.2).
     *
     * <p><b>여기 있는 것만 사용자가 올린 파일이 아니다 — 우리가 만든 것이다.</b> 그래도 같은
     * 루트에 두는 이유는 인프라가 볼륨 하나만 마운트하기 때문이다. 워커는 {@code :ro} 로 붙어
     * 있고 키를 해석하지 않으므로 이 경로를 건드릴 일이 없다.
     *
     * <p>만료된 파일은 {@code ExportCleanupScheduler} 가 지운다. Job 행은 이력이라 남는다.
     */
    public String exportKey(Long businessId, Long jobId) {
        return "exports/%d/%d.zip".formatted(businessId, jobId);
    }

    /**
     * 쓰기 스트림을 연다 — 부모 디렉터리를 먼저 만든다.
     *
     * <p><b>{@link #write} 와 달리 바이트를 다 받아 두지 않는다.</b> 준비자료 패키지는 증빙
     * 원본이 들어가면 수백 MB 가 되어 {@code byte[]} 로 들면 힙이 버티지 못한다. 부르는 쪽이
     * try-with-resources 로 닫는다.
     */
    public OutputStream openWrite(String storageKey) {
        Path target = pathOf(storageKey);
        try {
            Files.createDirectories(target.getParent());
            return Files.newOutputStream(target, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("파일을 쓰지 못했다 — key={}", storageKey, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "파일을 저장하지 못했습니다.");
        }
    }

    /** 읽기 스트림. 증빙 원본을 패키지에 넣을 때와 내려받을 때 쓴다. */
    public InputStream openRead(String storageKey) {
        try {
            return Files.newInputStream(pathOf(storageKey));
        } catch (IOException e) {
            log.error("파일을 읽지 못했다 — key={}", storageKey, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "파일을 읽지 못했습니다.");
        }
    }

    public boolean exists(String storageKey) {
        return Files.isRegularFile(pathOf(storageKey));
    }

    public long sizeOf(String storageKey) {
        try {
            return Files.size(pathOf(storageKey));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "파일 크기를 읽지 못했습니다.");
        }
    }

    /** 만료 정리용. 이미 없으면 조용히 지나간다 — 두 번 돌아도 같은 결과여야 한다. */
    public boolean deleteIfExists(String storageKey) {
        try {
            return Files.deleteIfExists(pathOf(storageKey));
        } catch (IOException e) {
            log.warn("만료 파일을 지우지 못했다 — key={}", storageKey, e);
            return false;
        }
    }

    /**
     * 키를 실제 경로로 바꾼다.
     *
     * <p>루트 밖으로 나가는 키는 여기서 막는다. 키는 우리가 만들지만 {@code storage_key} 는
     * DB 를 거쳐 돌아오므로, 그 사이에 값이 바뀌었을 때 파일시스템 전체가 열리지 않게 한다.
     */
    private Path pathOf(String storageKey) {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("저장 경로가 루트를 벗어난다: " + storageKey);
        }
        return target;
    }

    /**
     * 파일을 쓴다. <b>DB 커밋 뒤에 부른다</b> — 순서가 반대면 커밋이 실패했을 때 아무도
     * 가리키지 않는 파일이 남는다. 반대로 이 호출이 실패하면 예외가 올라가 행이 되돌아간다.
     *
     * <p>⚠ <b>퍼미션을 코드로 설정하지 않는다. 인프라가 default ACL 로 해결했다</b>
     * (합의 ⑧-2, 인프라 회신 2026-09-10). {@link Files#copy} 를 그대로 쓰고
     * {@code PosixFilePermission}·{@code FileAttribute} 를 붙이지 않는다.
     *
     * <p>왜 붙이면 안 되는가 — 워커 컨테이너는 <b>uid 10001</b> 로 돌고 볼륨을 {@code :ro} 로
     * 붙이는데, 앱이 퍼미션을 직접 정하면 ACL 이 준 기본값을 덮어써 워커가 못 읽는다. 그리고
     * {@code PosixFilePermission} 은 POSIX 전용이라 <b>Windows 로컬 개발에서 즉시 터진다.</b>
     *
     * <p>이 자리가 함정인 것은 증상이 늦게 나타나서다. 로컬에서는 같은 사용자라 안 드러나고,
     * stub 은 파일을 읽지 않아 통합 검증에서도 안 걸리고, <b>배포 live 첫 실행에서만</b>
     * 모든 추출이 400 이 된다. 그때 여기에 퍼미션 코드를 넣는 것이 자연스러워 보이지만
     * 그게 틀린 답이다 — 원인은 앱이 아니라 볼륨 쪽이다.
     */
    public void write(String storageKey, MultipartFile file) {
        // 키는 우리가 만들므로 루트를 벗어날 일이 없다. 걸리면 keyOf 가 바뀐 것이다.
        Path target = pathOf(storageKey);
        try {
            Files.createDirectories(target.getParent());
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("업로드 파일을 쓰지 못했다 — key={}", storageKey, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "파일을 저장하지 못했습니다.");
        }
    }
}
