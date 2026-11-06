package com.saferoute.assistant;

import java.util.List;
import java.util.Map;

import com.saferoute.route.api.RouteResult;

/**
 * recommend_trip 이 모은 후보 하나 (점수 매기기 전).
 *
 * @param type      walk 또는 transit
 * @param minutes   걸리는 시간(분). 걷기는 내 걸음 속도가 있으면 그걸로 다시 셈
 * @param walkM     걷는 거리(m). 날씨가 나쁠 때 밖에 있는 시간을 셀 때 쓴다
 * @param transfers 갈아타는 횟수 (걷기는 0)
 * @param reports   길 30m 안 불편 신고 수 (걷기만, 대중교통은 0)
 * @param summary   한 줄 요약 (예: "걸어서 18분, 1,240m" / "버스 30번 → 지하철 1호선, 25분")
 * @param request   [이 길로 출발] 때 앱이 /api/route 또는 /api/transit 에 보낼 값
 * @param route     걷기일 때 고른 길 종류 (safe, shortest). 대중교통은 null
 * @param diff      걷기 맞춤 길이 최단 길보다 더 지나는 시설 수 (원일님 설명 그대로, 예: cctv → 12). 없으면 빈 값
 * @param events    걷는 길이 지나는 행사·돌발 (원일님 엔진 routes[].events). 대중교통은 빈 목록
 * @param lowFloor  대중교통 첫 버스 중 곧 오는 저상버스가 있으면 true
 * @param firstBus  대중교통 첫 버스 번호와 몇 분 뒤 (예: "30번 5분 뒤"). 모르면 null
 */
public record Trip(String type, int minutes, int walkM, int transfers, int reports, String summary,
                   Map<String, Object> request, String route, Map<String, Integer> diff,
                   List<RouteResult.Event> events, boolean lowFloor, String firstBus) {
}
