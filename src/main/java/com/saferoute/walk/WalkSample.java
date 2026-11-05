package com.saferoute.walk;

/**
 * 앱이 보내는 한 번 잰 값 (POST /api/walk-speed 본문).
 * 좌표·길·시각은 받지 않는다. 거리와 움직인 시간만.
 *
 * @param guestId   앱이 처음 실행 때 만든 무작위 번호 (UUID)
 * @param mode      adult, child, senior, wheelchair
 * @param distanceM 길안내 중 걸은 거리 (m, 300 이상)
 * @param movingS   움직인 시간 (초, 신호 대기처럼 멈춘 시간 뺌)
 */
public record WalkSample(String guestId, String mode, Integer distanceM, Integer movingS) {
}
