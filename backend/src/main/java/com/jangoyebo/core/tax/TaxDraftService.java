package com.jangoyebo.core.tax;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.tenancy.BusinessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jangoyebo.core.job.Job;
import com.jangoyebo.core.job.JobService;
import com.jangoyebo.core.job.JobType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 신고서 초안 생성 접수 (API 14.3). */
@Service
public class TaxDraftService {

    private static final Logger log = LoggerFactory.getLogger(TaxDraftService.class);

    private final TaxFormTemplateRepository templates;
    private final TaxFilingDraftRepository drafts;
    private final JobService jobService;
    private final BusinessGuard guard;

    public TaxDraftService(TaxFormTemplateRepository templates,
                           TaxFilingDraftRepository drafts,
                           JobService jobService,
                           BusinessGuard guard) {
        this.templates = templates;
        this.drafts = drafts;
        this.jobService = jobService;
        this.guard = guard;
    }

    /**
     * 초안을 만들고 집계 Job 을 건다.
     *
     * <p><b>같은 서식·연도로 집계 중인 초안이 있으면 그것을 돌려준다.</b> 버튼을 두 번 눌러도
     * 사장님이 보려는 것은 같은 진행률이다 — 8.5 분류가 같은 판단을 했다. 완료된 초안은
     * 대상이 아니라서 재생성은 새 행을 만든다(ERD — 이력).
     *
     * <p><b>행을 먼저 만들고 Job 을 건다.</b> 응답이 {@code draftId} 를 실어야 하는데, Job
     * 부터 걸면 워커가 먼저 집어 갈 수 있어 적을 행이 없다.
     */
    @Transactional
    public TaxFilingDraft request(Long businessId, Long requestedBy, int taxYear, String formType,
                                  LocalDate periodStart, LocalDate periodEnd,
                                  Map<String, Object> hometaxReference) {

        TaxFormTemplate template = templates
                .findByFormTypeAndTaxYearAndActiveTrue(formType, taxYear)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "%d년 귀속 %s 서식이 없습니다.".formatted(taxYear, formType)));

        // 목록에는 나오지만 항목을 채우지 않는 유형이다. 조용히 빈 초안을 만들면
        // 화면이 「집계가 0원」으로 읽는다.
        if (!template.supportsDraft()) {
            throw new BusinessException(ErrorCode.FORM_NOT_SUPPORTED);
        }

        if (periodEnd.isBefore(periodStart)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "집계 종료일이 시작일보다 앞섭니다.");
        }
        // 귀속연도 밖의 거래가 그 해 신고서에 들어가면 안 된다. 부분 기간은 허용한다
        // (14.2 내보내기가 1~8월 같은 예를 든다).
        if (periodStart.getYear() != taxYear || periodEnd.getYear() != taxYear) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "집계 기간이 귀속연도(%d) 밖입니다.".formatted(taxYear));
        }

        var inFlight = drafts.findFirstByBusinessIdAndTaxYearAndTemplateIdAndStatusOrderByIdDesc(
                businessId, taxYear, template.getId(), TaxDraftStatus.PROCESSING);
        if (inFlight.isPresent()) {
            return inFlight.get();
        }

        // 직전 초안이 있으면 사장님이 적어 둔 값과 홈택스 참고값을 물려받는다. 숫자만 새로
        // 집계하면 되는 일에 입력을 처음부터 다시 시키지 않는다.
        var previous = drafts.findFirstByBusinessIdAndTaxYearAndTemplateIdOrderByIdDesc(
                businessId, taxYear, template.getId());

        TaxFilingDraft draft = drafts.save(TaxFilingDraft.queued(
                businessId, template.getId(), taxYear, periodStart, periodEnd,
                hometaxReference != null ? hometaxReference
                        : previous.map(TaxFilingDraft::getHometaxReference).orElse(null)));
        previous.ifPresent(earlier -> draft.inherit(earlier.getOverrides()));

        Job job = jobService.enqueue(businessId, requestedBy, JobType.TAX_DRAFT_GENERATION,
                Map.of("draftId", draft.getId()));
        draft.attachJob(job.getId());
        return draft;
    }

    /**
     * 항목 수정·참고값 입력 (14.3 {@code PATCH}).
     *
     * <p><b>집계값을 덮어쓰지 않는다.</b> 수정은 {@code overrides} 에 따로 쌓이고, 화면이 보는
     * 값은 조회 시점에 둘을 겹쳐 만든다 — 그래야 「원래 얼마였는데 무엇을 고쳤는지」가 남는다.
     *
     * <p><b>산식 항목은 고칠 수 없다.</b> ㉒ 소득금액을 손으로 덮으면 ⑭−⑱ 과 맞지 않는 신고서가
     * 된다. 바꾸고 싶으면 그 아래 항목을 고쳐야 하고, 그러면 산식이 따라 움직인다.
     */
    /**
     * 초안 삭제 (명세 밖 — 구현현황 4장).
     *
     * <p><b>명세 14.3 에 없던 경로다.</b> 화면에는 초안 목록이 있는데 지우는 길이 없어,
     * 기간이나 서식을 잘못 고른 초안이 <b>영영 목록에 남았다</b>. 프론트에서 그 회신이 왔다.
     *
     * <p><b>행을 실제로 지운다.</b> 증빙 삭제(13.5)가 소프트 삭제인 것과 다른데, 저쪽은
     * {@code EVIDENCE_CORRECTION} 이 실사용 정확도의 유일한 원천이라 함께 지우면 측정이
     * 사라지기 때문이다. 초안에는 그런 이유가 없고, 다른 테이블이 참조하지도 않는다.
     *
     * <p>⚠ <b>사장님이 적어 둔 값이 함께 사라진다.</b> 재생성은 같은 서식·귀속연도의 <b>직전
     * 초안</b>에서 {@code overrides}·{@code hometaxReference} 를 물려받는데, 마지막 초안을
     * 지우면 물려받을 것이 없어진다. 화면이 지우기 전에 한 번 묻는 편이 좋다.
     *
     * <p><b>집계 중({@code PROCESSING})인 것은 막는다.</b> 지우면 워커가 이어서
     * {@code aggregator.generate} 를 부르고 「초안이 없다」로 실패한다 — 화면에는 지운 초안
     * 대신 <b>실패한 작업</b>이 남고, 사장님은 무엇이 잘못됐는지 알 수 없다. 집계는 몇 초라
     * 기다렸다 지우면 된다. {@code Job} 행은 그대로 둔다 — 작업 이력은 초안과 별개다.
     */
    @Transactional
    public void delete(Long draftId) {
        TaxFilingDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_DRAFT_NOT_FOUND));
        // 직접 경로다 — Hibernate 필터가 걸리지 않으므로 명시 검증이 필요하다.
        guard.assertOwned(draft);

        if (draft.getStatus() == TaxDraftStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.TAX_DRAFT_NOT_DELETABLE);
        }
        drafts.delete(draft);
        log.info("신고서 초안 {} 삭제 — 사업장 {} · {}년 · 상태 {}",
                draftId, draft.getBusinessId(), draft.getTaxYear(), draft.getStatus());
    }

    @Transactional
    public void patch(Long draftId,
                      Map<String, Object> hometaxReference,
                      Map<String, Object> overrides,
                      List<String> confirmedFieldKeys) {

        TaxFilingDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_DRAFT_NOT_FOUND));
        // 직접 경로다 — Hibernate 필터가 걸리지 않으므로 명시 검증이 필요하다.
        guard.assertOwned(draft);

        TaxFormTemplate template = templates.findById(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TAX_DRAFT_NOT_FOUND));

        Map<String, Map<String, Object>> definitions = new LinkedHashMap<>();
        for (Map<String, Object> section : template.getSections()) {
            Object rawFields = section.get("fields");
            if (rawFields instanceof List<?> list) {
                for (Object raw : list) {
                    if (raw instanceof Map<?, ?> field) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        field.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        definitions.put(String.valueOf(copy.get("fieldKey")), copy);
                    }
                }
            }
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        overrides.forEach((fieldKey, value) -> {
            Map<String, Object> definition = requireKnown(definitions, fieldKey);
            // 저장하지 않기로 한 값이다. 조용히 무시하면 화면은 적힌 줄 알고 서버에는 없어,
            // 내보내기에서 빈 칸으로 나간다.
            if (Boolean.TRUE.equals(definition.get("sensitive"))) {
                throw new BusinessException(ErrorCode.SENSITIVE_FIELD_NOT_STORABLE);
            }
            if ("CALCULATED".equals(definition.get("source"))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "%s 은 산식으로 계산되는 항목이라 직접 고칠 수 없습니다.".formatted(fieldKey));
            }
            merged.put(fieldKey, value);
        });

        for (String fieldKey : confirmedFieldKeys) {
            requireKnown(definitions, fieldKey);
            Map<String, Object> entry = new LinkedHashMap<>();
            if (merged.get(fieldKey) instanceof Map<?, ?> existing) {
                existing.forEach((k, v) -> entry.put(String.valueOf(k), v));
            }
            entry.put("confirmed", true);
            merged.put(fieldKey, entry);
        }

        if (!merged.isEmpty()) {
            draft.applyOverrides(merged);
        }
        if (hometaxReference != null) {
            draft.updateHometaxReference(hometaxReference);
        }
    }

    private Map<String, Object> requireKnown(Map<String, Map<String, Object>> definitions,
                                             String fieldKey) {
        Map<String, Object> definition = definitions.get(fieldKey);
        if (definition == null) {
            // 오타를 받아 두면 화면은 저장됐다고 하고 그 값은 어느 칸에도 안 나타난다.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "서식에 없는 항목입니다: " + fieldKey);
        }
        return definition;
    }

    /**
     * 집계가 터졌을 때 초안을 실패로 남긴다.
     *
     * <p><b>별도 트랜잭션이다.</b> 집계 트랜잭션은 예외와 함께 되감기므로, 같은 트랜잭션에서
     * 상태를 적으면 그 기록도 함께 사라진다 — 초안이 영영 {@code PROCESSING} 으로 남는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long draftId) {
        drafts.findById(draftId).ifPresent(TaxFilingDraft::fail);
    }
}
