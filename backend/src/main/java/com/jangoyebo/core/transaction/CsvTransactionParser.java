package com.jangoyebo.core.transaction;

import com.jangoyebo.common.domain.FlowType;
import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CSV 거래 파서 (API 명세 7.4).
 *
 * <p>한글·영문 헤더를 모두 받는다 — 은행별 내보내기 형식이 제각각이라 사용자가
 * 헤더를 손보지 않아도 되게 하기 위해서다. 거래일시도 같은 이유로 여러 표기를 받는다
 * ({@link CsvTimestamps}) — 엑셀로 한 번 열었다 저장하면 형식이 바뀐다.
 *
 * <p>형식 오류 행은 <b>Job 을 실패시키지 않고</b> 건너뛰며 사유를 모은다. 200행 중 3행이
 * 깨졌다고 197행을 버리면 사용자가 할 수 있는 게 없다. 파일 자체가 규격이 아닐 때만
 * {@code IMPORT_INVALID_FORMAT} 으로 즉시 거부한다.
 */
public class CsvTransactionParser {

    /** 헤더 별칭. 소문자·공백 제거 후 비교한다. */
    private static final Map<String, String> HEADER_ALIASES = Map.of(
            "transactionat", "transactionAt", "거래일시", "transactionAt",
            "description", "description", "적요", "description",
            "depositamount", "depositAmount", "입금액", "depositAmount",
            "withdrawalamount", "withdrawalAmount", "출금액", "withdrawalAmount"
    );
    private static final Map<String, String> BALANCE_ALIASES = Map.of(
            "balanceafter", "balanceAfter", "거래후잔액", "balanceAfter");

    private static final List<String> REQUIRED = List.of(
            "transactionAt", "description", "depositAmount", "withdrawalAmount");

    public ParseResult parse(InputStream inputStream) {
        List<ParsedRow> rows = new ArrayList<>();
        List<TransactionDtos.RejectedRow> rejected = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new BusinessException(ErrorCode.IMPORT_INVALID_FORMAT, "빈 파일입니다.");
            }
            Map<String, Integer> columns = mapHeader(headerLine);

            String line;
            int rowNumber = 1;   // 헤더가 1행
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    rows.add(parseRow(splitCsv(line), columns));
                } catch (IllegalArgumentException e) {
                    // 형식이 깨진 행 하나가 나머지를 막지 않는다. 사유는 사용자에게 나간다.
                    rejected.add(new TransactionDtos.RejectedRow(rowNumber, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.IMPORT_INVALID_FORMAT, "파일을 읽을 수 없습니다.");
        }

        return new ParseResult(rows, rejected);
    }

    private Map<String, Integer> mapHeader(String headerLine) {
        Map<String, Integer> columns = new HashMap<>();
        String[] headers = splitCsv(headerLine);

        for (int i = 0; i < headers.length; i++) {
            String key = normalize(headers[i]);
            String canonical = HEADER_ALIASES.get(key);
            if (canonical == null) {
                canonical = BALANCE_ALIASES.get(key);
            }
            if (canonical != null) {
                columns.put(canonical, i);
            }
        }

        List<String> missing = REQUIRED.stream().filter(c -> !columns.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_INVALID_FORMAT,
                    "필수 컬럼이 없습니다: " + String.join(", ", missing));
        }
        return columns;
    }

    private ParsedRow parseRow(String[] cells, Map<String, Integer> columns) {
        LocalDateTime occurredAt = CsvTimestamps.parse(required(cells, columns, "transactionAt"));
        String description = value(cells, columns, "description");

        BigDecimal deposit = amount(value(cells, columns, "depositAmount"));
        BigDecimal withdrawal = amount(value(cells, columns, "withdrawalAmount"));

        // 정확히 하나만 양수여야 한다(API 7.4).
        boolean hasDeposit = deposit.signum() > 0;
        boolean hasWithdrawal = withdrawal.signum() > 0;
        if (hasDeposit == hasWithdrawal) {
            throw new IllegalArgumentException("입금액·출금액 중 정확히 하나만 양수여야 합니다.");
        }

        Integer balanceIndex = columns.get("balanceAfter");
        BigDecimal balanceAfter = null;
        if (balanceIndex != null && balanceIndex < cells.length && !cells[balanceIndex].isBlank()) {
            balanceAfter = amount(cells[balanceIndex]);
        }

        return new ParsedRow(occurredAt,
                hasDeposit ? FlowType.DEPOSIT : FlowType.WITHDRAWAL,
                hasDeposit ? deposit : withdrawal,
                description, balanceAfter);
    }

    private static String required(String[] cells, Map<String, Integer> columns, String key) {
        String v = value(cells, columns, key);
        if (v.isBlank()) {
            throw new IllegalArgumentException(key + " 값이 비어 있습니다.");
        }
        return v;
    }

    private static String value(String[] cells, Map<String, Integer> columns, String key) {
        Integer index = columns.get(key);
        return index == null || index >= cells.length ? "" : cells[index].trim();
    }

    private static BigDecimal amount(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        // 은행 내보내기는 천 단위 구분자와 통화기호가 섞여 있는 경우가 많다.
        String cleaned = raw.replaceAll("[,\\s₩원]", "");
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("금액 형식이 올바르지 않습니다: " + raw);
        }
    }

    /** 따옴표로 감싼 셀 안의 쉼표를 보존하는 최소 구현. */
    private static String[] splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                // "" 는 이스케이프된 따옴표다.
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells.toArray(String[]::new);
    }

    private static String normalize(String header) {
        // BOM 과 공백을 제거한다. 엑셀에서 저장한 UTF-8 CSV 는 앞에 BOM 이 붙는다.
        return header.replace("﻿", "").replaceAll("\\s", "").toLowerCase();
    }

    public record ParsedRow(LocalDateTime occurredAt, FlowType flowType, BigDecimal amount,
                            String description, BigDecimal balanceAfter) {
    }

    public record ParseResult(List<ParsedRow> rows, List<TransactionDtos.RejectedRow> rejectedRows) {
    }
}
