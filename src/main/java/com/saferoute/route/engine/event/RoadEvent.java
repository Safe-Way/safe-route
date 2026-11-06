package com.saferoute.route.engine.event;

import java.time.LocalDateTime;

/**
 * 길 위의 행사·돌발상황 하나 (A파트 road_event 표, migration 026).
 *
 * @param kind    행사, 공사, 사고, 통제, 재난, 기타
 * @param title   화면에 보일 이름 (축제 이름, 돌발은 도로 이름 + 종류)
 * @param place   장소 설명 ("금남로+충장로 일원"). 없으면 null
 * @param message 안내 문구 (돌발 "(2차로) 시설물 보수작업중", 축제 내용 앞부분). 없으면 null
 * @param radiusM 영향 반경 (행사 300m, 돌발 100m)
 * @param endsAt  끝나는 때. null이면 끝을 모름 (돌발은 다음 갱신까지 있다고 본다)
 */
public record RoadEvent(long id, String kind, String title, String place, String message, double lon, double lat,
        double radiusM, LocalDateTime endsAt) {

    public double[] point() {
        return new double[] {lon, lat};
    }
}
