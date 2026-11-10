package com.saferoute.route.engine.graph;

/**
 * 길 성질 종류. 경로 엔진은 STAIRS·CURB·FOOTBRIDGE·UNPAVED·TRAIL·FORD·SURFACE_UNKNOWN·CROSSWALK만 읽는다 (값 1 = 확실히 해당, 0 = 아님·모름).
 * 밝기·CCTV·도움 시설 같은 선호 기준은 {@link com.saferoute.route.engine.score.Criterion}으로 요청 때 계산한다.
 * 지붕(COVERED)·지하(UNDERGROUND)는 비 오는 날 기준에 쓴다.
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
    CROSSWALK,
    /** 지붕 있는 길: road_edge.covered = 1 (OSM covered·indoor·tunnel, A파트가 그린 지하 길). 비 오는 날 기준(ROOF)에서 범위 안 (2026-11-09) */
    COVERED,
    /**
     * 지하 길: road_edge_excluded(reason = underground)에 A파트가 직접 그린 역 출구 계단·엘리베이터, 대합실, 지하상가 통로.
     * 비 올 때 운행 시간 안에만 도로망에 넣고, 가로등·CCTV·시설 자료가 없어 기준 감점을 하지 않는다 (2026-11-09)
     */
    UNDERGROUND,
    /**
     * 보행자 광장 가로지르기 길: road_edge_excluded(reason = plaza)에 A파트가 광장 입구끼리 그은 곧은 길 (2026-11-10, w13).
     * 다른 모드는 보통 평지 보도로 보고, 휠체어는 쓰지 않는다(route.yml wheelchair exclude). 높낮이를 모르는 선이라
     * 높낮이가 다른 광장에서는 계단 양 끝을 곧게 잇는 선이 있다 (광주 44줄, 문화전당 광장 포함)
     */
    PLAZA
}
