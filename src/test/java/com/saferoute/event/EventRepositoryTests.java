package com.saferoute.event;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/**
 * 지도용 행사·돌발 표 읽기 SQL (가짜 DB)
 * <p>무엇을: {@link EventRepository}가 지도 범위 안에서 지금 진행 중인 행사·돌발(road_event)을 읽는 SQL과 값.
 * <p>어떻게: FakeDb(가짜 JDBC)가 정해 둔 줄을 돌려주고 넣은 값을 남긴다. 진짜 DB는 안 써서, 시작·끝 시각으로 거르는 것은
 * SQL에 맞는 값을 넘기는지만 보고 거르기 결과 자체는 보지 않는다.
 * <p>왜: 범위 사각형의 꼭짓점 순서나 시각 값이 틀리면 축제·공사 알림이 지도에서 사라지거나 끝난 행사가 계속 보인다.
 */
@DisplayName("지도용 행사·돌발 SQL")
class EventRepositoryTests {

    // 줄의 칸을 RoadEvent 로 그대로 옮기는지(detail·url 같은 NULL 칸 포함), 그리고 SQL에 넘기는 값을 본다:
    // 범위는 서남→동남→동북→서북→서남으로 닫힌 POLYGON(소수 7자리), 지금 시각은 시작·끝 비교에 두 번, 마지막이 최대 개수
    @Test
    @DisplayName("지도 범위 안의 지금 유효한 행사를 읽는다")
    void activeEventsInBox() {
        FakeDb db = new FakeDb().rows("FROM road_event", row("id", 5L, "kind", "festival", "detail", null,
                "title", "양림 크리스마스 문화축제", "place", "양림오거리", "lat", 35.1399, "lon", 126.9150, "radius_m", 300,
                "s", "2026-12-01T00:00", "e", "2026-12-31T23:59", "message", "사람이 많아요", "url", null));
        // 행사 기간(12-01 ~ 12-31) 한가운데 시각
        LocalDateTime now = LocalDateTime.of(2026, 12, 5, 18, 0);

        // 인자: 서, 남, 동, 북, 지금, 최대 개수
        assertThat(new EventRepository(db.jdbc()).active(126.90, 35.13, 126.93, 35.15, now, 20)).containsExactly(
                new RoadEvent(5L, "festival", null, "양림 크리스마스 문화축제", "양림오거리", 35.1399, 126.9150, 300,
                        "2026-12-01T00:00", "2026-12-31T23:59", "사람이 많아요", null));
        var p = db.call("FROM road_event").params();
        assertThat((String) p.get(0)).isEqualTo(
                "POLYGON((126.9000000 35.1300000,126.9300000 35.1300000,126.9300000 35.1500000,126.9000000 35.1500000,126.9000000 35.1300000))");
        assertThat(p.subList(1, 4)).containsExactly(Timestamp.valueOf(now), Timestamp.valueOf(now), 20);
    }
}
