package com.saferoute.assistant;

import java.time.LocalDate;

/**
 * 비서가 기억하는 내 정보 (user_profile_now 뷰, 027·028).
 *
 * @param modeNow      지금 쓸 모드 (잠깐 바뀐 모드가 기간 안이면 그 값). 모르면 null
 * @param aidNow       지금 이동 보조 (manual_wheelchair, power_wheelchair, walker, cane, stroller). 모르면 null
 * @param tempActive   잠깐 바뀐 모드가 지금 적용 중이면 true
 * @param tempReason   잠깐 바뀐 이유 ("다리 다침"). 없으면 null
 * @param tempUntil    잠깐 바뀐 모드가 끝나는 날. 없으면 null
 * @param mode         평소 모드
 * @param stairsOk     계단 0 못 감, 1 몇 칸은 괜찮음, 2 괜찮음. 모르면 null
 * @param preferTransit 0 걷기 좋아함, 1 보통, 2 대중교통 좋아함. 모르면 null
 * @param setupDone    처음 설정을 마쳤으면 true
 * @param consentTrips 이동 기록 저장에 동의했으면 true
 */
public record Profile(String modeNow, String aidNow, boolean tempActive, String tempReason, LocalDate tempUntil,
                      String mode, Integer stairsOk, Integer preferTransit, boolean setupDone, boolean consentTrips) {
}
