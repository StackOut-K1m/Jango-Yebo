package com.jangoyebo.forecast.pattern;

/**
 * 반복 패턴 상태 (API 9.1).
 *
 * <p>{@code CANDIDATE} → {@code CONFIRMED}(승인) / {@code DISMISSED}(무시) / {@code PAUSED}(일시중지).
 *
 * <p><b>{@code DISMISSED} 는 재탐지에서 되살아나지 않는다.</b> 사용자가 "이건 반복이 아니다"
 * 라고 판단한 것을 배치가 매번 다시 올리면 같은 결정을 계속 요구하게 된다(API 9.3).
 */
public enum PatternStatus {
    CANDIDATE,
    CONFIRMED,
    DISMISSED,
    PAUSED
}
