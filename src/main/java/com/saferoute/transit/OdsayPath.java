package com.saferoute.transit;

import java.util.List;

/**
 * ODsay 가 준 경로 하나 (필요한 칸만 옮긴 것).
 *
 * @param totalMin 전체 걸리는 시간 (분, ODsay 기준)
 * @param fare     요금 (원). 모르면 null
 * @param segs     구간 (걷기·버스·지하철) 차례대로
 */
record OdsayPath(int totalMin, Integer fare, List<Seg> segs) {

    static final int SUBWAY = 1;
    static final int BUS = 2;
    static final int WALK = 3;

    /**
     * 구간 하나. 걷기 구간은 ODsay 가 좌표를 주지 않아 start·end 가 null 이다 (앞뒤 구간으로 채움).
     *
     * @param type         1 지하철, 2 버스, 3 걷기
     * @param lines        버스 번호들 (같은 정류장에서 타는 여러 버스) 또는 지하철 노선 이름
     * @param way          지하철 가는 방향 (예: 녹동). 버스·걷기는 null
     * @param stationCount 지나는 정류장·역 수
     * @param stops        지나는 정류장·역 (passStopList, 타는 곳부터 내리는 곳까지). 걷기는 빈 목록
     */
    record Seg(int type, int distanceM, int minutes, Place start, Place end, List<String> lines, String way,
               int stationCount, List<Place> stops) {

        Seg {
            stops = stops == null ? List.of() : List.copyOf(stops);
        }

        Seg(int type, int distanceM, int minutes, Place start, Place end, List<String> lines, String way,
            int stationCount) {
            this(type, distanceM, minutes, start, end, lines, way, stationCount, List.of());
        }
    }

    /** 이름과 좌표 */
    record Place(String name, double lat, double lon) {
    }
}
