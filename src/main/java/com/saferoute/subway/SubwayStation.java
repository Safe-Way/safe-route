package com.saferoute.subway;

/**
 * DB에서 찾은 지하철역 하나 (subway_station 표).
 *
 * @param id                  광주교통공사 역 번호 (1 평동 ~ 20 녹동)
 * @param name                역 이름 (예: 문화전당)
 * @param distanceM           내 위치에서 거리(m)
 * @param elevatorTotal       엘리베이터 전체 수
 * @param elevatorAvailable   지금 쓸 수 있는 엘리베이터 수 (자료 받은 때 기준)
 * @param elevatorBrokenGates 엘리베이터가 고장 난 출입구 번호 (쉼표), 없으면 null
 * @param liftTotal           휠체어 리프트 전체 수
 * @param liftAvailable       쓸 수 있는 휠체어 리프트 수
 * @param toiletTotal         장애인 화장실 전체 수
 * @param toiletAvailable     쓸 수 있는 장애인 화장실 수
 */
public record SubwayStation(int id, String name, double lat, double lon, int distanceM,
                            int elevatorTotal, int elevatorAvailable, String elevatorBrokenGates,
                            int liftTotal, int liftAvailable, int toiletTotal, int toiletAvailable) {

    /** 시간표 표의 역 코드 (녹동 100 ~ 평동 119). 역 번호(평동 1 ~ 녹동 20)와 순서가 거꾸로다 */
    public int timetableCode() {
        return 120 - id;
    }
}
