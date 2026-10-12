package com.jangoyebo.common.tenancy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code /businesses/{businessId}/**} 경로의 사업장 ID를 받는다.
 *
 * <p>주입 시점에 <b>소유권 검증이 이미 끝나 있다</b>({@link CurrentBusinessArgumentResolver}).
 * 컨트롤러가 별도로 확인할 필요가 없고, 반대로 이 애너테이션 없이
 * {@code @PathVariable Long businessId}로 받으면 검증이 빠진다.
 *
 * <pre>{@code
 * @GetMapping("/businesses/{businessId}/transactions")
 * ApiResponse<...> list(@CurrentBusiness Long businessId) { ... }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentBusiness {
}
