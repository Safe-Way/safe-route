package com.saferoute.route.engine.graph;

/**
 * 길 성질 종류. 경로 엔진은 STAIRS·CURB·FOOTBRIDGE·UNPAVED·TRAIL·FORD·SURFACE_UNKNOWN·CROSSWALK만 읽는다 (값 1 = 확실히 해당, 0 = 아님·모름).
 * 밝기·CCTV·도움 시설 같은 선호 기준은 {@link com.saferoute.route.engine.score.Criterion}으로 요청 때 계산한다.
 */
public enum Penalty {
    /** 계단이면 1 */
    STAIRS,
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
