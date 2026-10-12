package com.jangoyebo.core.transaction;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.account.Account;
import com.jangoyebo.core.account.AccountRepository;
import com.jangoyebo.core.TransactionsChanged;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * CSV 거래 업로드 (API 명세 7.4, P0-01).
 *
 * <p>인수 조건 <b>"CSV 거래를 업로드하면 중복 없이 계좌·사업장에 귀속된다"</b>를 만족해야 한다.
 * 중복 방지는 2중이다 — 애플리케이션이 미리 걸러 통계를 내고, DB 부분 유일 인덱스
 * ({@code uk_tx_dedup})가 최종적으로 막는다.
 *
 * <p>파일 크기가 10MB 로 제한되어 있어(수천 행 수준) 파싱은 동기로 하고 저장만 Job 으로 남긴다.
 * 파싱 단계에서 규격 오류를 즉시 {@code 400} 으로 돌려줘야 사용자가 바로 고칠 수 있기 때문이다.
 */
@Service
public class TransactionImportService {

    private static final Logger log = LoggerFactory.getLogger(TransactionImportService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final TransactionBulkWriter bulkWriter;
    private final AccountRepository accountRepository;
    private final JobService jobService;
    private final BusinessGuard guard;
    private final ApplicationEventPublisher events;

    public TransactionImportService(TransactionBulkWriter bulkWriter,
                                    AccountRepository accountRepository,
                                    JobService jobService,
                                    BusinessGuard guard,
                                    ApplicationEventPublisher events) {
        this.bulkWriter = bulkWriter;
        this.accountRepository = accountRepository;
        this.jobService = jobService;
        this.guard = guard;
        this.events = events;
    }

    @Transactional
    public Job importCsv(Long accountId, MultipartFile file) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        // 직접 경로(/accounts/{id})라 자동 검증이 걸리지 않는다. 명시적으로 확인한다.
        guard.assertOwned(account);

        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_INVALID_FORMAT, "파일이 비어 있습니다.");
        }

        var parsed = parse(file);

        Job job = jobService.enqueue(account.getBusinessId(), guard.currentUserId(),
                JobType.TRANSACTION_IMPORT,
                Map.of("accountId", accountId, "fileName", String.valueOf(file.getOriginalFilename())));

        var result = persist(account, parsed.rows());
        var importResult = new TransactionDtos.ImportResult(
                result.imported(), result.duplicates(), parsed.rejectedRows());

        jobService.complete(job.getId(), Map.of(
                "accountId", accountId,
                "importedCount", importResult.importedCount(),
                "duplicateCount", importResult.duplicateCount(),
                "rejectedRows", importResult.rejectedRows()));

        // 예측 재계산 트리거(공통규약 2.6). 거래 INSERT 가 같은 트랜잭션이라
        // AFTER_COMMIT 시점에 새 거래가 보인다. 한 건도 안 들어왔으면 곡선이 바뀌지 않는다.
        if (result.imported() > 0) {
            events.publishEvent(TransactionsChanged.byCsv(account.getBusinessId(), accountId));
        }

        log.info("CSV 임포트 완료 account={} 저장={} 중복={} 거부={}",
                accountId, result.imported(), result.duplicates(), parsed.rejectedRows().size());
        return job;
    }

    private CsvTransactionParser.ParseResult parse(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return new CsvTransactionParser().parse(in);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.IMPORT_INVALID_FORMAT, "파일을 읽을 수 없습니다.");
        }
    }

    /**
     * <b>{@code saveAll} 에서 {@link TransactionBulkWriter} 로 옮겼다.</b> {@code Transaction} PK 가
     * {@code IDENTITY} 라 Hibernate 가 JDBC 배치를 스스로 꺼서, 행 수만큼 왕복이 나가고 있었다
     * (ERD·기술스택 §4.1 이 대량 삽입만 JPA 를 우회하라고 정한 근거).
     *
     * <p>같이 사라진 것이 둘 있다 — <b>사전 조회</b>와 <b>파일 내부 중복용 {@code HashSet}</b>.
     * {@code ON CONFLICT DO NOTHING} 이 둘을 한꺼번에 흡수한다(배치 안의 두 행도 별개 문장이라
     * 뒤엣것이 충돌로 걸린다). 사전 조회는 조회와 삽입 사이 경쟁을 어차피 못 막았고, 그 경쟁이
     * 나면 {@code DataIntegrityViolationException} 으로 트랜잭션 전체가 롤백됐다.
     */
    private PersistResult persist(Account account, List<CsvTransactionParser.ParsedRow> rows) {
        if (rows.isEmpty()) {
            return new PersistResult(0, 0);
        }
        List<CsvTransactionRow> toInsert = rows.stream().map(row -> toRow(account, row)).toList();
        int inserted = bulkWriter.insertCsvRows(toInsert);
        return new PersistResult(inserted, rows.size() - inserted);
    }

    /**
     * ⚠ {@code transactionDate} 를 {@code Instant} 에서 역산하지 않는다 — zone 변환 <b>전</b>
     * {@code LocalDateTime} 에서 뽑는다. 그래야 서버 시간대와 무관하게 파일에 적힌 벽시계 날짜가
     * 그대로 들어간다.
     */
    private static CsvTransactionRow toRow(Account account, CsvTransactionParser.ParsedRow row) {
        return new CsvTransactionRow(account.getBusinessId(), account.getId(),
                deduplicationKey(row),
                row.occurredAt().atZone(BUSINESS_ZONE).toInstant(),
                row.occurredAt().toLocalDate(),
                row.flowType(), row.amount(), row.balanceAfter(), row.description());
    }

    /**
     * 중복 판정 키 = {@code (거래일시, 금액, 적요)} 해시 (API 7.4).
     *
     * <p>계좌는 부분 유일 인덱스 {@code (account_id, deduplication_key)} 가 함께 잡으므로
     * 키 자체에는 넣지 않는다.
     */
    private static String deduplicationKey(CsvTransactionParser.ParsedRow row) {
        LocalDateTime at = row.occurredAt();
        BigDecimal amount = row.amount().stripTrailingZeros();
        String raw = at + "|" + row.flowType() + "|" + amount.toPlainString()
                + "|" + (row.description() == null ? "" : row.description().trim());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record PersistResult(int imported, int duplicates) {
    }
}
