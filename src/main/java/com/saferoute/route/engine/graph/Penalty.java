package com.saferoute.route.engine.graph;

/**
 * 길 성질 종류. 경로 엔진은 STAIRS·CURB·FOOTBRIDGE·UNPAVED·TRAIL·FORD·SURFACE_UNKNOWN·CROSSWALK만 읽는다 (값 1 = 확실히 해당, 0 = 아님·모름).
 * 밝기·CCTV·도움 시설 같은 선호 기준은 {@link com.saferoute.route.engine.score.Criterion}으로 요청 때 계산한다.
 * 나머지 값은 예전 감점 공식(정책 확정 전 실험)의 이름이며 지금은 쓰지 않는다.
 */
public enum Penalty {
    /** 밝기 부족: 1 − min(1, 조명 수 ÷ (길이 ÷ 25m)) */
    LIGHT,
    /** CCTV 부족: 1 − min(1, 카메라 대수 ÷ (길이 ÷ 100m)). 요구사항 6.1은 50m, 실측 후 100m로 완화 */
    CCTV,
    /** 도움 지점 멀음: min(1, 가장 가까운 경찰·편의점·지킴이집·비상벨 ÷ 300m) */
    HELP,
    /** 덮개 없음: 덮인 길이면 0 */
    COVER,
    /** 계단이면 1 */
    STAIRS,
    /** 무더위쉼터 멀음 */
    HEAT,
    /** 한파쉼터 멀음 */
    COLD,
    /** 큰 차도: 간선 1, 보조간선 0.5 */
    TRAFFIC,
    /** 지킴이집 멂: min(1, 가장 가까운 아동안전지킴이집 ÷ 500m) (6.4, 어린이). 요구사항 6.4는 300m, 실측 후 500m로 완화 */
    GUARD,
    /** 횡단 많음: min(1, 길 100m당 횡단보도 수 ÷ 2) (6.4, 어린이·노인) */
    CROSSING,
    /** 쉼 곳 멂: min(1, 가장 가까운 쉼터·공중화장실 ÷ 300m) (6.4, 노인) */
    REST,
    /** 보도턱: 지나는 횡단보도의 보도턱이 안 낮춰졌으면 1, 낮춰졌으면 0, 모르면 0.5 (crosswalk.curb_lowered, 휠체어) */
    CURB,
    /** 육교: 휠체어로 못 지나는 육교면 1, 경사로·엘리베이터가 있으면 0, 모르면 0.5 (footbridge.wheelchair_ok, 휠체어) */
    FOOTBRIDGE,
    /** 비포장: 바닥 재질이 비포장이거나, 재질을 모르는 농로가 tracktype grade2~5·없음이면 1 (휠체어 조건부 제외, 2026-11-04) */
    UNPAVED,
    /** 산행 길: 등산 난이도(sac_scale)가 붙은 산길이면 1 (휠체어 제외, 2026-11-04) */
    TRAIL,
    /** 징검다리·물을 건너는 길: OSM ford(stepping_stones 등)가 있으면 1 (휠체어 제외, 2026-11-04) */
    FORD,
    /** 재질 확인 안 됨: 재질·난이도 태그가 없는 산길이면 1. 포장으로 보고 지나가되 알린다 (휠체어, 2026-11-04) */
    SURFACE_UNKNOWN,
    /** 횡단보도 길: OSM footway=crossing이면 1. 경로 비용에는 쓰지 않고 꺾는 곳 안내("횡단보도를 건너 왼쪽", FR-34)에만 쓴다 */
    CROSSWALK
}
