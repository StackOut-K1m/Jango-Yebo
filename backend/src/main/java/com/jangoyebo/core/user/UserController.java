package com.jangoyebo.core.user;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import com.jangoyebo.common.response.ApiResponse;
import com.jangoyebo.common.tenancy.BusinessGuard;
import com.jangoyebo.core.business.BusinessRepository;
import com.jangoyebo.core.user.AuthDtos.MeResponse;
import com.jangoyebo.core.user.AuthDtos.UpdateMeRequest;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 내 프로필 (API 명세 6.3). */
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final BusinessGuard guard;

    public UserController(UserRepository userRepository,
                          BusinessRepository businessRepository,
                          BusinessGuard guard) {
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.guard = guard;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ApiResponse<MeResponse> me() {
        User user = currentUser();
        long businessCount = businessRepository.countByOwnerUserIdAndDeletedAtIsNull(user.getId());
        return ApiResponse.ok(new MeResponse(
                user.getId(), user.getEmail(), user.getName(), user.getPhone(),
                user.getDefaultBusinessId(), businessCount, user.getCreatedAt()));
    }

    @PatchMapping
    @Transactional
    public ApiResponse<MeResponse> update(@Valid @RequestBody UpdateMeRequest request) {
        User user = currentUser();

        // 남의 사업장을 기본값으로 지정하지 못하게 한다.
        if (request.defaultBusinessId() != null) {
            guard.assertOwnsBusiness(request.defaultBusinessId());
        }
        user.updateProfile(request.name(), request.phone(), request.defaultBusinessId());

        long businessCount = businessRepository.countByOwnerUserIdAndDeletedAtIsNull(user.getId());
        return ApiResponse.ok(new MeResponse(
                user.getId(), user.getEmail(), user.getName(), user.getPhone(),
                user.getDefaultBusinessId(), businessCount, user.getCreatedAt()));
    }

    private User currentUser() {
        return userRepository.findById(guard.currentUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));
    }
}
