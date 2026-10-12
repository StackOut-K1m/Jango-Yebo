package com.jangoyebo.common.tenancy;

import com.jangoyebo.common.error.BusinessException;
import com.jangoyebo.common.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * {@link CurrentBusiness} 파라미터에 사업장 ID를 주입하면서 <b>소유권을 검증한다.</b>
 *
 * <p>컨트롤러가 검증을 잊을 수 없게 하는 것이 목적이다. 경로에 {@code businessId}가 없으면
 * 설정 실수이므로 즉시 예외를 던진다.
 */
@Component
public class CurrentBusinessArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String PATH_VARIABLE = "businessId";

    private final BusinessGuard guard;

    public CurrentBusinessArgumentResolver(BusinessGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentBusiness.class)
                && Long.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables = (Map<String, String>) webRequest.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);

        String raw = pathVariables == null ? null : pathVariables.get(PATH_VARIABLE);
        if (raw == null) {
            throw new IllegalStateException(
                    "@CurrentBusiness 를 쓰려면 경로에 {businessId} 가 있어야 합니다: "
                            + parameter.getMethod());
        }

        long businessId;
        try {
            businessId = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BUSINESS_NOT_FOUND);
        }

        guard.assertOwnsBusiness(businessId);
        return businessId;
    }
}
