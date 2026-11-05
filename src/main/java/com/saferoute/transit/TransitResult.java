package com.saferoute.transit;

import java.util.List;

import com.saferoute.bus.BusArrival;
import com.saferoute.route.api.RouteResult;
import com.saferoute.subway.SubwayTrain;

/**
 * POST /api/transit 답 (FR-37 대중교통 탭).
 *
 * @param paths              추천 순 경로 (최대 3개)
 * @param hiddenForWheelchair 휠체어 모드에서 엘리베이터로 못 들어가는 역을 지나서 뺀 경로 수
 * @param note               앱에 그대로 보여 줄 한 줄 (없으면 null)
 */
public record TransitResult(List<TransitPath> paths, int hiddenForWheelchair, String note) {

    /**
     * 경로 하나.
     *
     * @param totalMin  전체 시간 (분). 걷는 구간은 우리 경로 시간으로 바꿔 다시 셈
     * @param fare      요금 (원, ODsay). 모르면 null
     * @param transfers 갈아타는 횟수
     * @param walkM     걷는 거리 합 (m)
     * @param legs      구간 차례대로
     */
    public record TransitPath(int totalMin, Integer fare, int transfers, int walkM, List<Leg> legs) {
    }

    /**
     * 구간 하나.
     *
     * @param type          walk, bus, subway
     * @param lines         버스 번호들 또는 지하철 노선 이름 (걷기는 빈 목록)
     * @param way           지하철 방향 (예: 녹동). 그 밖에는 null
     * @param stationCount  지나는 정류장·역 수 (걷기는 null)
     * @param safeWalk      걷기 구간을 우리 맞춤 경로로 바꿨으면 true, ODsay 값 그대로면 false (걷기만)
     * @param geometry      걷기 구간 선 [[lon, lat], ...] (/api/route 와 같은 순서, 맞춤 경로로 바꿨을 때만)
     * @param sidewalks     걷기 구간의 인도 방향 (/api/route 와 같은 모양, 맞춤 경로로 바꿨을 때만)
     * @param liveAvailable 버스 도착·지하철 시간표를 붙였으면 true, 못 받았으면 false (걷기는 null)
     * @param buses         탈 버스 중 곧 오는 것 (/api/bus/nearby 와 같은 모양)
     * @param trains        그 방향으로 곧 오는 열차 (시간표 기준, /api/subway/nearby 와 같은 모양)
     */
    public record Leg(String type, int distanceM, int minutes, Stop from, Stop to, List<String> lines, String way,
                      Integer stationCount, Boolean safeWalk, List<double[]> geometry,
                      List<RouteResult.Sidewalk> sidewalks, Boolean liveAvailable,
                      List<BusArrival> buses, List<SubwayTrain> trains) {
    }

    public record Stop(String name, double lat, double lon) {
    }
}
