package com.jangoyebo.core.export;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.core.storage.UploadStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 신고 준비자료 패키지를 ZIP 으로 쓴다 (API 14.2).
 *
 * <p><b>메모리에 다 올리지 않는다.</b> 증빙 원본이 들어가면 수백 MB 가 되므로 저장소가 준
 * 스트림에 곧바로 쓴다. 14.3 초안 내보내기가 {@code byte[]} 인 것과 다른데, 저쪽은 시트
 * 한 장이고 저장조차 하지 않는 요청-응답 경로다.
 *
 * <p>시트 일곱 장과 증빙 원본 폴더가 모두 들어간다. 안내문이 <b>무엇이 들어 있고 무엇이
 * 빠졌는지</b>를 적는다 — 증빙 원본은 요청에서 끄면 안 들어가는데, 그것을 말하지 않으면 받는
 * 쪽이 증빙이 아예 없는 줄 안다.
 */
@Component
public class ExportPackageWriter {

    private static final Logger log = LoggerFactory.getLogger(ExportPackageWriter.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);

    /** 증빙 원본이 들어가는 폴더. */
    private static final String EVIDENCE_FOLDER = "08_증빙원본/";

    /** 안내문에 적는 목록. 시트 쪽은 렌더러가 알아서 넣으므로 여기는 사람이 읽을 이름이다. */
    private static final String[] CONTENTS = {
            "01_연간_매출요약.xlsx",
            "02_사업용계좌_거래내역.xlsx",
            "03_필요경비_후보명세서.xlsx",
            "04_플랫폼별_정산대사표.xlsx",
            "05_고정비_반복지출.xlsx",
            "06_증빙누락_확인필요.xlsx",
            "07_거래증빙_연결목록.xlsx"
    };

    private final UploadStorage storage;
    private final TransactionSheetBuilder transactionSheets;
    private final SummarySheetBuilder summarySheets;
    private final EvidenceFileCollector evidenceFiles;
    private final TableXlsxRenderer renderer;

    public ExportPackageWriter(UploadStorage storage, TransactionSheetBuilder transactionSheets,
                               SummarySheetBuilder summarySheets,
                               EvidenceFileCollector evidenceFiles, TableXlsxRenderer renderer) {
        this.storage = storage;
        this.transactionSheets = transactionSheets;
        this.summarySheets = summarySheets;
        this.evidenceFiles = evidenceFiles;
        this.renderer = renderer;
    }

    /**
     * @param businessName 파일명과 안내문에 쓴다
     * @return 쓴 파일의 크기(바이트)
     */
    public long write(String storageKey, Long businessId, String businessName,
                      LocalDate from, LocalDate to, ZonedDateTime generatedAt,
                      boolean includeEvidenceFiles) {
        try (OutputStream out = storage.openWrite(storageKey);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {

            zip.putNextEntry(new ZipEntry("00_안내.txt"));
            zip.write(manifest(businessName, from, to, generatedAt, includeEvidenceFiles)
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            // 한 장씩 그려서 곧바로 흘려보낸다. 다 모아 두면 시트가 늘어날수록 힙에 쌓인다.
            List<TableDocument> documents = new ArrayList<>(
                    summarySheets.build(businessId, from, to, generatedAt));
            documents.addAll(transactionSheets.build(businessId, from, to, generatedAt));
            documents.sort(Comparator.comparing(TableDocument::fileName));
            for (TableDocument document : documents) {
                zip.putNextEntry(new ZipEntry(document.fileName()));
                zip.write(renderer.render(document));
                zip.closeEntry();
            }

            if (includeEvidenceFiles) {
                writeEvidenceFiles(zip, businessId, from, to);
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "패키지를 만들지 못했습니다.");
        }
        return storage.sizeOf(storageKey);
    }

    /**
     * 증빙 원본을 폴더에 담는다.
     *
     * <p><b>못 읽은 파일을 조용히 넘기지 않는다.</b> DB 에는 증빙이 있는데 디스크에 파일이 없는
     * 경우가 있고(볼륨 교체·수동 삭제), 그냥 넘어가면 세무대리인은 <b>애초에 증빙이 없었다고</b>
     * 생각한다. 빠진 목록을 폴더 안에 파일로 남긴다.
     *
     * <p>한 건이 실패해도 나머지를 계속 담는다 — 한 파일 때문에 패키지 전체가 없어지는 쪽이
     * 훨씬 나쁘다.
     */
    private void writeEvidenceFiles(ZipOutputStream zip, Long businessId, LocalDate from,
                                    LocalDate to) throws IOException {
        List<String> unreadable = new ArrayList<>();
        for (EvidenceFileCollector.EvidenceFile file : evidenceFiles.collect(businessId, from, to)) {
            if (!storage.exists(file.storageKey())) {
                unreadable.add(file.entryName());
                continue;
            }
            zip.putNextEntry(new ZipEntry(EVIDENCE_FOLDER + file.entryName()));
            try (var in = storage.openRead(file.storageKey())) {
                in.transferTo(zip);
            } catch (RuntimeException e) {
                log.warn("증빙 원본을 담지 못했다 — key={}", file.storageKey(), e);
                unreadable.add(file.entryName());
            }
            zip.closeEntry();
        }

        if (!unreadable.isEmpty()) {
            StringBuilder text = new StringBuilder("담지 못한 증빙 원본\n====================\n\n");
            text.append("아래 증빙은 기록은 있으나 파일을 읽지 못했습니다. ");
            text.append("증빙이 없었다는 뜻이 아니므로 원본을 따로 확인하십시오.\n\n");
            unreadable.forEach(name -> text.append("  - ").append(name).append('\n'));
            zip.putNextEntry(new ZipEntry(EVIDENCE_FOLDER + "_읽지_못한_파일.txt"));
            zip.write(text.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /**
     * 파일명 (14.2 {@code fileName}).
     *
     * <p>사람이 메일에 붙이는 이름이라 한글을 쓴다. 저장소 키와 <b>다른 이름</b>인데, 키는
     * 충돌하지 않아야 하고 이 이름은 읽히기만 하면 되기 때문이다.
     */
    public String fileNameOf(String businessName, LocalDate from, LocalDate to) {
        String period = from.getYear() == to.getYear()
                ? String.valueOf(from.getYear())
                : from.getYear() + "-" + to.getYear();
        return "신고준비자료_%s_%s.zip".formatted(businessName, period);
    }

    private String manifest(String businessName, LocalDate from, LocalDate to,
                            ZonedDateTime generatedAt, boolean includeEvidenceFiles) {
        StringBuilder text = new StringBuilder();
        text.append("신고 준비자료 패키지\n");
        text.append("====================\n\n");
        text.append("사업장    : ").append(businessName).append('\n');
        text.append("대상 기간 : ").append(from).append(" ~ ").append(to).append('\n');
        text.append("생성 시각 : ").append(STAMP.format(generatedAt)).append(" (KST)\n");
        text.append("증빙 원본 : ").append(includeEvidenceFiles ? "포함" : "미포함").append("\n\n");

        text.append("[담긴 자료]\n");
        for (String name : CONTENTS) {
            text.append("  - ").append(name).append('\n');
        }
        text.append("  - ").append(EVIDENCE_FOLDER)
                .append(includeEvidenceFiles ? "" : "  (요청에서 제외했습니다)").append("\n\n");

        text.append("[안내]\n");
        text.append("이 패키지는 세무대리인 전달용 준비자료입니다."
                + " 홈택스 직접 신고용 주 산출물은 신고서 초안입니다.\n");
        text.append("필요경비는 후보이며 확정된 판단이 아닙니다."
                + " 세액을 계산하거나 신고를 대신하지 않습니다.\n");
        text.append("개인정보가 포함되어 있습니다."
                + " 전달 대상과 보관 기간을 확인하고, 불필요해지면 파기하십시오.\n");
        return text.toString();
    }
}
