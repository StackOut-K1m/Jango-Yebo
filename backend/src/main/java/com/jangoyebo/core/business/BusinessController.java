package com.jangoyebo.core.business;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.security.FieldEncryptor;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.common.tenancy.CurrentBusiness;
import com.jangoyebo.core.business.BusinessDtos.BusinessResponse;
import com.jangoyebo.core.business.BusinessDtos.CreateRequest;
import com.jangoyebo.core.business.BusinessDtos.UpdateRequest;
import com.jangoyebo.core.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 사업장 (API 명세 7.1). */
@RestController
@RequestMapping("/api/v1/businesses")
public class BusinessController {

    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final BusinessGuard guard;
    private final FieldEncryptor encryptor;
    private final BusinessSummaryReader summary;

    public BusinessController(BusinessRepository businessRepository,
                              UserRepository userRepository,
                              BusinessGuard guard,
                              FieldEncryptor encryptor,
                              BusinessSummaryReader summary) {
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.guard = guard;
        this.encryptor = encryptor;
        this.summary = summary;
    }

    /**
     * 목록·상세가 같은 모양을 쓰도록 한 자리에 모은다.
     *
     * <p>사업장마다 조회가 두 번 더 나간다. 한 사용자의 사업장은 한 자릿수라(다사업장이라도
     * 매장 몇 개다) 문제가 되지 않지만, 수십 개가 되는 날에는 목록용 집계 쿼리로 바꿔야 한다.
     */
    private BusinessResponse describe(Business business) {
        return BusinessResponse.of(business,
                summary.connectedAccountCount(business.getId()),
                summary.currentRiskLevel(business.getId()));
    }

    /** 내 사업장 목록. 소유자 기준 조회라 별도 권한 검사가 필요 없다. */
    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<Map<String, List<BusinessResponse>>> list() {
        List<BusinessResponse> items = businessRepository
                .findByOwnerUserIdAndDeletedAtIsNullOrderByIdAsc(guard.currentUserId())
                .stream()
                .map(this::describe)
                .toList();
        return ApiResponse.ok(Map.of("items", items));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ApiResponse<BusinessResponse> create(@Valid @RequestBody CreateRequest request) {
        Long userId = guard.currentUserId();

        Business business = Business.create(userId, request.name(), request.taxationType());
        business.updateProfile(null, request.industryType(), request.region(), null,
                request.openedAt(), request.annualRevenue(), request.employeeCount());
        applyRegistrationNumber(business, request.businessRegNo());

        Business saved = businessRepository.save(business);
        assignAsDefaultIfFirst(userId, saved.getId());

        return ApiResponse.ok(describe(saved));
    }

    @GetMapping("/{businessId}")
    @Transactional(readOnly = true)
    public ApiResponse<BusinessResponse> get(@CurrentBusiness Long businessId) {
        return ApiResponse.ok(describe(find(businessId)));
    }

    @PatchMapping("/{businessId}")
    @Transactional
    public ApiResponse<BusinessResponse> update(@CurrentBusiness Long businessId,
                                                @Valid @RequestBody UpdateRequest request) {
        Business business = find(businessId);
        business.updateProfile(request.name(),
                request.industryType(), request.region(), request.taxationType(),
                request.openedAt(), request.annualRevenue(), request.employeeCount());
        applyRegistrationNumber(business, request.businessRegNo());
        return ApiResponse.ok(describe(business));
    }

    private Business find(Long businessId) {
        // @CurrentBusiness 가 이미 소유권을 검증했으므로 여기서는 존재 여부만 본다.
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_NOT_FOUND));
    }

    /** 암호문과 마스킹을 한 번에 넣는다. 둘을 따로 계산하는 호출부를 만들지 않기 위해서다. */
    private void applyRegistrationNumber(Business business, String plainRegNo) {
        business.updateRegistrationNumber(encryptor.encrypt(plainRegNo), maskRegNo(plainRegNo));
    }

    /**
     * 첫 사업장을 기본 사업장으로 잡는다.
     *
     * <p>선택지가 하나뿐인 시점에 선택을 요구할 이유가 없고, 이 값이 null 이면 프론트에
     * "현재 사업장"이 없어 화면이 성립하지 않는다.
     *
     * <p><b>이미 지정돼 있으면 건드리지 않는다</b> — 사용자가 {@code PATCH /users/me} 로 고른 값이
     * 나중에 만든 사업장 때문에 바뀌면 안 된다.
     */
    private void assignAsDefaultIfFirst(Long userId, Long businessId) {
        userRepository.findById(userId)
                .filter(user -> user.getDefaultBusinessId() == null)
                .ifPresent(user -> user.updateProfile(null, null, businessId));
    }

    /**
     * 사업자등록번호 마스킹. 뒤 5자리만 남긴다 — API 7.1 예시({@code ***-**-67890})와 같은 형태다.
     *
     * <p><b>평문을 받는다.</b> 암호문에 걸면 암호문 조각이 응답으로 나간다.
     * 결과는 쓰기 시점에 저장하며, 읽을 때 복호화하지 않는 이유는 V4 마이그레이션 주석에 있다.
     */
    static String maskRegNo(String plainRegNo) {
        if (plainRegNo == null) {
            return null;
        }
        String digits = plainRegNo.replaceAll("[^0-9]", "");
        // 형식 검증(^\d{3}-\d{2}-\d{5}$)을 통과하면 항상 10자리다. 방어적으로만 둔다.
        return digits.length() < 5 ? null : "***-**-" + digits.substring(digits.length() - 5);
    }
}
