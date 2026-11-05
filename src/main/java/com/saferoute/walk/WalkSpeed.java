package com.saferoute.walk;

/**
 * 지금 쓰는 걸음 속도 (앱에 돌려주는 값).
 *
 * @param mode          모드
 * @param speedMMin     지금 쓰는 속도 (분당 m). 잰 적이 없으면 모드 기본 속도
 * @param baseSpeedMMin 모드 기본 속도 (route.yml: 일반 80, 어린이 70, 노인 60, 휠체어 50)
 * @param samples       지금까지 받은 측정 수
 * @param personal      true 면 내 기록이 섞인 속도, false 면 모드 기본 속도 그대로
 */
public record WalkSpeed(String mode, double speedMMin, double baseSpeedMMin, int samples, boolean personal) {
}
