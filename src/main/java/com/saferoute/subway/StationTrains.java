package com.saferoute.subway;

import java.util.List;

/**
 * 앱에 나가는 역 하나: 위치·편의시설 + 곧 오는 열차.
 *
 * @param elevator      역 안 엘리베이터 전체 대수 (대합실↔승강장 포함). 지도(/api/pois)의 출구 수와 다르다
 * @param trains        방향마다 다음 열차 2대까지, 빨리 오는 순. 운행이 끝났으면 빈 목록
 * @param elevatorExits 지상에서 엘리베이터로 들어갈 수 있는 출구 (지도 /api/pois 와 같은 기준)
 */
public record StationTrains(int id, String name, double lat, double lon, int distanceM,
                            Facility elevator, Facility wheelchairLift, Facility accessibleToilet,
                            String elevatorBrokenGates, List<SubwayTrain> trains, ElevatorExits elevatorExits) {

    /** 출구 정보 없이 만들 때 (예전 모양, 테스트용) */
    public StationTrains(int id, String name, double lat, double lon, int distanceM,
                         Facility elevator, Facility wheelchairLift, Facility accessibleToilet,
                         String elevatorBrokenGates, List<SubwayTrain> trains) {
        this(id, name, lat, lon, distanceM, elevator, wheelchairLift, accessibleToilet, elevatorBrokenGates,
                trains, null);
    }

    /**
     * 엘리베이터 출구.
     *
     * @param count            엘리베이터로 들어갈 수 있는 출구 수 (예: 문화전당 3)
     * @param exitNos          그 출구 번호 (쉼표, 예: "1,2"), 없으면 null
     * @param unknown          확인 안 된 출구 수
     * @param total            역 출구 전체 수
     * @param wheelchairEntry  휠체어로 지상에서 들어갈 수 있나: 1곳 이상이면 true,
     *                         모든 출구를 확인했는데 0곳이면 false, 확인 안 된 출구만 남았으면 null(모름)
     */
    public record ElevatorExits(int count, String exitNos, int unknown, int total, Boolean wheelchairEntry) {

        static ElevatorExits of(int count, String exitNos, int unknown, int total) {
            Boolean entry = count > 0 ? Boolean.TRUE : (unknown == 0 && total > 0 ? Boolean.FALSE : null);
            return new ElevatorExits(count, exitNos, unknown, total, entry);
        }
    }

    /** 편의시설 수: 전체 · 지금 쓸 수 있는 수 */
    public record Facility(int total, int available) {
    }
}
