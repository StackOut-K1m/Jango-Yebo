package com.jangoyebo.common.security;

/**
 * 인증된 사용자. JWT에서 복원하며 DB를 조회하지 않는다.
 *
 * <p><b>사업장 ID는 담지 않는다.</b> 토큰에 넣으면 사업장 추가·권한 변경이
 * 토큰 갱신 전까지 반영되지 않는다(아키텍처 §9). 사업장은 요청 경로로 받고
 * 매 요청 소유권을 검증한다.
 */
public record AuthUser(Long userId, String email) {
}
