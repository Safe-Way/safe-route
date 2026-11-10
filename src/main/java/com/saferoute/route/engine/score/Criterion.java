package com.saferoute.route.engine.score;

/**
 * 경로를 고를 때 보는 기준. 모두 "길 길이 중 시설 범위 안에 드는 비율"(커버율)로 잰다.
 * route.yml의 day·night weights 이름(light, cctv, ...)과 같다.
 */
public enum Criterion {
    /** 가로등·보안등에서 길을 따라 light-m(30m) 안 */
    LIGHT,
    /** CCTV에서 길을 따라 cctv-m(50m) 안. 단속·관리용은 poi에서 이미 빠져 있다 (결정 39) */
    CCTV,
    /** 지금 이용 가능한 도움 시설에서 반경 help-m 안. 낮은 경찰·편의점·비상벨·지킴이집, 밤은 지킴이집과 건물 안 비상벨을 뺀다 */
    HELP,
    /** 어린이보호구역 대상 시설(학교·유치원 등)에서 반경 school-m(300m) 안 */
    SCHOOL,
    /** 지금 이용 가능한 쉼터·공중화장실에서 반경 rest-m 안 */
    REST,
    /** 지붕 있는 길 (road_edge.covered). 시설이 아니라 길 성질이라 커버율은 0 또는 1. 비 올 때만 비중을 둔다 (2026-11-09) */
    ROOF
}
