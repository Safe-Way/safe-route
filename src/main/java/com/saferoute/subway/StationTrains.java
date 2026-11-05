package com.saferoute.subway;

import java.util.List;

/**
 * 앱에 나가는 역 하나: 위치·편의시설 + 곧 오는 열차.
 *
 * @param trains 방향마다 다음 열차 2대까지, 빨리 오는 순. 운행이 끝났으면 빈 목록
 */
public record StationTrains(int id, String name, double lat, double lon, int distanceM,
                            Facility elevator, Facility wheelchairLift, Facility accessibleToilet,
                            String elevatorBrokenGates, List<SubwayTrain> trains) {

    /** 편의시설 수: 전체 · 지금 쓸 수 있는 수 */
    public record Facility(int total, int available) {
    }
}
