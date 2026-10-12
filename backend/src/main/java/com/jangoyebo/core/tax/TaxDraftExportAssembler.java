package com.jangoyebo.core.tax;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 내보낼 문서를 조립한다 (API 14.3 {@code export}).
 *
 * <p><b>홈택스 입력 순서로 늘어놓는다.</b> 74호서식 순서가 아니다 — 명세가 「기본사항 →
 * 사업소득명세서 → 총수입금액 및 필요경비명세서 → 소득공제 → 세액공제·감면 → 기납부세액」
 * 을 요구한다. 이 파일을 보며 홈택스 화면에 옮겨 적는 것이 쓰임새라 <b>화면에 나오는
 * 순서</b>가 맞다.
 *
 * <p><b>뒤쪽 셋은 우리가 계산하지 않는다.</b> 소득금액까지만 채운다는 원칙(SRS 1.3) 때문이다.
 * 그래도 줄은 넣는다 — 옮겨 적는 사람이 <b>어디서 멈추고 무엇을 직접 넣어야 하는지</b>
 * 알아야 한다.
 */
@Service
public class TaxDraftExportAssembler {

    private static final Logger log = LoggerFactory.getLogger(TaxDraftExportAssembler.class);

    /** 기본사항에 넣을 항목과 그 순서 (홈택스 화면 순서). */
    private static final List<String> IDENTITY_ORDER = List.of(
            "NAME", "BIRTH_DATE", "HOME_ADDRESS", "PHONE",
            "BIZ_REG_NO", "BIZ_ADDRESS", "INDUSTRY", "INDUSTRY_CODE",
            "TAX_PERIOD", "INCOME_TYPE");

    private final TaxDraftQueryService query;
    private final TaxFilingDraftRepository drafts;
    private final JdbcTemplate jdbc;
    private final BusinessGuard guard;

    public TaxDraftExportAssembler(TaxDraftQueryService query,
                                   TaxFilingDraftRepository drafts,
                                   JdbcTemplate jdbc,
                                   BusinessGuard guard) {
        this.query = query;
        this.drafts = drafts;
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /**
     * @param sensitiveValues 요청 본문에만 존재한다. <b>저장하지 않고 로그에도 값을 남기지
     *                        않는다</b> — 남는 것은 필드 키와 시각뿐이다(14.3)
     */
    @Transactional
    public ExportDocument assemble(Long draftId, List<Long> includeBusinessIds,
                                   Map<String, String> sensitiveValues) {

        // detail() 이 상함 검사를 먼저 돌린다. 그래서 「상했는데 내보내기는 됐다」가 없다.
        List<Map<String, Object>> perBusiness = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        Map<String, Object> primary = query.detail(draftId);
        requireReady(primary, draftId);
        perBusiness.add(primary);
        columns.add(businessName(drafts.findById(draftId).orElseThrow().getBusinessId()));

        for (Long businessId : includeBusinessIds) {
            TaxFilingDraft draft = drafts.findById(draftId).orElseThrow();
            if (businessId.equals(draft.getBusinessId())) {
                continue;
            }
            // 서식이 개인 단위라 남의 사업장을 합칠 수는 없다.
            guard.assertOwnsBusiness(businessId);
            TaxFilingDraft other = drafts
                    .findFirstByBusinessIdAndTaxYearAndTemplateIdOrderByIdDesc(
                            businessId, draft.getTaxYear(), draft.getTemplateId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TAX_DRAFT_NOT_READY,
                            "사업장 %d 에는 %d년 귀속 초안이 없습니다. 먼저 생성해 주세요."
                                    .formatted(businessId, draft.getTaxYear())));
            Map<String, Object> detail = query.detail(other.getId());
            requireReady(detail, other.getId());
            perBusiness.add(detail);
            columns.add(businessName(businessId));
        }

        boolean merged = perBusiness.size() > 1;
        if (merged) {
            columns.add("합계");
        }

        Map<String, Field> byKey = new LinkedHashMap<>();
        for (int i = 0; i < perBusiness.size(); i++) {
            collect(perBusiness.get(i), i, perBusiness.size(), byKey);
        }

        int columnCount = columns.size();
        List<ExportDocument.Section> sections = new ArrayList<>();
        sections.add(identity(byKey, columnCount, merged, sensitiveValues));
        sections.add(businessIncome(byKey, columnCount, merged));
        sections.add(revenueAndExpense(byKey, columnCount, merged));
        sections.add(income(byKey, columnCount, merged));
        sections.addAll(manualSections(columnCount));

        TaxFilingDraft draft = drafts.findById(draftId).orElseThrow();
        // **값은 남기지 않는다.** 어떤 민감 항목을 실어 보냈는지와 시각만 남긴다(14.3).
        log.info("신고서 초안 내보내기 — draftId={} businesses={} sensitiveFields={}",
                draftId, columns, sensitiveValues.keySet());

        return new ExportDocument(
                "간편장부소득금액계산서 · 총수입금액 및 필요경비명세서 (%d년 귀속)"
                        .formatted(draft.getTaxYear()),
                "초안 · 생성 기준일 %s · 대상 기간 %s ~ %s · 확정 세무 자료가 아닙니다"
                        .formatted(LocalDate.now(), draft.getPeriodStart(), draft.getPeriodEnd()),
                columns,
                sections,
                TaxDraftQueryService.DISCLAIMER);
    }

    private void requireReady(Map<String, Object> detail, Long draftId) {
        String status = String.valueOf(detail.get("status"));
        if (!"READY".equals(status)) {
            throw new BusinessException(ErrorCode.TAX_DRAFT_NOT_READY,
                    "초안 %d 이 %s 상태입니다. 재생성한 뒤 내보내 주세요.".formatted(draftId, status));
        }
    }

    private String businessName(Long businessId) {
        return jdbc.queryForObject("SELECT name FROM core.business WHERE id = ?",
                String.class, businessId);
    }

    // --- 항목 모으기 ----------------------------------------------------------

    /** 사업장별 상세에서 항목을 뽑아 열 자리에 꽂는다. */
    @SuppressWarnings("unchecked")
    private void collect(Map<String, Object> detail, int column, int businessCount,
                         Map<String, Field> byKey) {
        for (Object rawSection : (List<Object>) detail.getOrDefault("sections", List.of())) {
            Map<String, Object> section = (Map<String, Object>) rawSection;
            for (Object rawField : (List<Object>) section.getOrDefault("fields", List.of())) {
                Map<String, Object> field = (Map<String, Object>) rawField;
                String fieldKey = String.valueOf(field.get("fieldKey"));
                Field entry = byKey.computeIfAbsent(fieldKey,
                        key -> new Field(String.valueOf(field.get("label")), businessCount));
                entry.values[column] = field.get("value");
                entry.source = String.valueOf(field.getOrDefault("source", ""));
                Object basis = field.get("basisSummary");
                if (basis instanceof Map<?, ?> summary
                        && summary.get("transactionCount") instanceof Number count) {
                    entry.basisCount = (entry.basisCount == null ? 0 : entry.basisCount)
                            + count.intValue();
                }
                Object note = field.get("reviewNote");
                if (note != null) {
                    entry.note = String.valueOf(note);
                }
            }
        }
    }

    // --- 섹션 ----------------------------------------------------------------

    private ExportDocument.Section identity(Map<String, Field> byKey, int columnCount,
                                            boolean merged, Map<String, String> sensitiveValues) {
        List<ExportDocument.Row> rows = new ArrayList<>();
        for (String fieldKey : IDENTITY_ORDER) {
            Field field = byKey.get(fieldKey);
            if (field == null) {
                continue;
            }
            // 민감 항목은 저장하지 않으므로 값이 비어 있다. 요청에 실려 온 값이 있으면
            // 이 파일에만 채우고, 없으면 「직접 기재」로 남긴다(명세 14.3).
            if (sensitiveValues.containsKey(fieldKey)) {
                rows.add(ExportDocument.Row.of(field.label,
                        filled(sensitiveValues.get(fieldKey), columnCount),
                        "직접 입력", null, null));
            } else if ("USER_SENSITIVE".equals(field.source)) {
                rows.add(ExportDocument.Row.manual(field.label, columnCount, "직접 기재"));
            } else {
                rows.add(field.toRow(columnCount, merged));
            }
        }
        // 별지 제40호서식(1) 기본사항 항목이라 74호서식에 칸이 없다. 홈택스에서 직접 넣는다.
        rows.add(ExportDocument.Row.manual("주민등록번호", columnCount, "홈택스에서 직접 입력"));
        rows.add(ExportDocument.Row.manual("환급금 계좌", columnCount, "홈택스에서 직접 입력"));
        return new ExportDocument.Section("1. 기본사항",
                "민감 항목은 서버에 저장하지 않습니다. 이 파일에만 있습니다.", rows);
    }

    /** 대조 행. 본표 ⑭ 와 40호 ⑨ 가 어긋나면 홈택스가 받지 않는다(본표 작성방법 6). */
    private ExportDocument.Section businessIncome(Map<String, Field> byKey, int columnCount,
                                                  boolean merged) {
        List<ExportDocument.Row> rows = new ArrayList<>();
        Field adjusted = byKey.get("ADJUSTED_REVENUE");
        if (adjusted != null) {
            rows.add(ExportDocument.Row.of("⑨ 총수입금액", adjusted.cells(columnCount, merged),
                    "대조", null, "74호 본표 ⑭ 세무조정 후 수입금액과 같아야 합니다"));
        }
        return new ExportDocument.Section("2. 사업소득명세서 [별지 제40호서식(1)]",
                "74호 본표 ⑭ = 40호 ⑨. 두 값이 다르면 홈택스가 받지 않습니다.", rows);
    }

    private ExportDocument.Section revenueAndExpense(Map<String, Field> byKey, int columnCount,
                                                     boolean merged) {
        List<String> order = new ArrayList<>(List.of(
                "SALES", "OTHER_REVENUE", "TOTAL_REVENUE",
                "OPENING_INVENTORY", "PURCHASES", "CLOSING_INVENTORY", "COST_OF_SALES",
                "PAYROLL", "TAX_DUES", "RENT", "INTEREST", "ENTERTAINMENT", "DONATION",
                "DEPRECIATION", "VEHICLE", "COMMISSION", "SUPPLIES", "WELFARE", "TRANSPORT",
                "ADVERTISING", "TRAVEL", "OTHER_EXPENSE", "GENERAL_EXPENSE_TOTAL",
                "TOTAL_EXPENSE"));
        return new ExportDocument.Section("3. 총수입금액 및 필요경비명세서 [별지 제74호서식 부표]",
                "제조비용란(⑱~㉔)은 제조업 전용이라 음식점업은 기재하지 않습니다.",
                rows(byKey, order, columnCount, merged));
    }

    private ExportDocument.Section income(Map<String, Field> byKey, int columnCount,
                                          boolean merged) {
        List<String> order = List.of(
                "REVENUE_EXCLUDED", "REVENUE_ADDED", "ADJUSTED_REVENUE",
                "EXPENSE_EXCLUDED", "EXPENSE_ADDED", "ADJUSTED_EXPENSE",
                "NET_INCOME_BEFORE_DONATION", "DONATION_EXCESS", "DONATION_CARRYOVER",
                "NET_INCOME");
        return new ExportDocument.Section("4. 소득금액 [별지 제74호서식 본표]",
                "세액은 계산하지 않습니다. 소득금액까지입니다.",
                rows(byKey, order, columnCount, merged));
    }

    /** 여기서부터는 우리가 값을 갖고 있지 않다. 옮겨 적는 사람이 멈추는 자리다. */
    private List<ExportDocument.Section> manualSections(int columnCount) {
        return List.of(
                new ExportDocument.Section("5. 소득공제", "홈택스에서 직접 입력합니다.",
                        List.of(ExportDocument.Row.manual("인적공제·연금보험료·특별소득공제 등",
                                columnCount, "서비스가 계산하지 않습니다"))),
                new ExportDocument.Section("6. 세액공제·감면", "홈택스에서 직접 입력합니다.",
                        List.of(ExportDocument.Row.manual("자녀세액공제·연금계좌·표준세액공제 등",
                                columnCount, "서비스가 계산하지 않습니다"))),
                new ExportDocument.Section("7. 기납부세액", "홈택스에서 직접 입력합니다.",
                        List.of(ExportDocument.Row.manual("중간예납·원천징수·수시부과 세액",
                                columnCount, "서비스가 계산하지 않습니다"))));
    }

    private List<ExportDocument.Row> rows(Map<String, Field> byKey, List<String> order,
                                          int columnCount, boolean merged) {
        List<ExportDocument.Row> rows = new ArrayList<>();
        for (String fieldKey : order) {
            Field field = byKey.get(fieldKey);
            if (field != null) {
                rows.add(field.toRow(columnCount, merged));
            }
        }
        return rows;
    }

    private static List<Object> filled(Object value, int columnCount) {
        List<Object> values = new ArrayList<>(java.util.Collections.nCopies(columnCount, null));
        values.set(0, value);
        return values;
    }

    /** 한 항목의 사업장별 값. */
    private static final class Field {

        private final String label;
        private final Object[] values;
        private String source = "";
        private Integer basisCount;
        private String note;

        private Field(String label, int businessCount) {
            this.label = label;
            this.values = new Object[businessCount];
        }

        ExportDocument.Row toRow(int columnCount, boolean merged) {
            return ExportDocument.Row.of(label, cells(columnCount, merged), source, basisCount, note);
        }

        /** 사업장 열 + (여럿이면) 합계 열. 합계는 숫자 칸만 더한다. */
        List<Object> cells(int columnCount, boolean merged) {
            List<Object> cells = new ArrayList<>();
            java.util.Collections.addAll(cells, values);
            while (cells.size() < columnCount) {
                cells.add(null);
            }
            if (merged) {
                BigDecimal total = null;
                for (Object value : values) {
                    if (value instanceof Number number) {
                        total = (total == null ? BigDecimal.ZERO : total)
                                .add(new BigDecimal(number.toString()));
                    }
                }
                cells.set(columnCount - 1, total);
            }
            return cells;
        }
    }
}
