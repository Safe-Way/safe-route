package com.saferoute.route.data;

/**
 * 사람마다 정한 경로 선호 (A파트 user_profile 표, migration 027). 비어 있는 값은 모드 기준을 쓴다.
 * 값은 PATCH /api/me(C)나 비서 대화·다닌 기록(C #103)으로 바뀐다.
 *
 * @param stairsOk   계단: 0 못 감, 1 몇 칸은 괜찮음, 2 괜찮음. null이면 모드 기준
 * @param maxDetour  받아들이는 우회 (최단의 몇 배까지, 1.0~2.0). null이면 모드 기준
 * @param restEveryM 쉬어야 하는 간격(m, 50~2000). null이면 모드 기준
 */
public record UserPreferences(Integer stairsOk, Double maxDetour, Integer restEveryM) {

    public static final UserPreferences NONE = new UserPreferences(null, null, null);

    public boolean isEmpty() {
        return stairsOk == null && maxDetour == null && restEveryM == null;
    }
}
