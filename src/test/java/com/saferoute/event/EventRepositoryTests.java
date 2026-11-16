package com.saferoute.event;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 지도용 행사·돌발 표 읽기 SQL (가짜 DB) */
class EventRepositoryTests {

    @Test
    void activeEventsInBox() {
        FakeDb db = new FakeDb().rows("FROM road_event", row("id", 5L, "kind", "festival", "detail", null,
                "title", "양림 크리스마스 문화축제", "place", "양림오거리", "lat", 35.1399, "lon", 126.9150, "radius_m", 300,
                "s", "2026-12-01T00:00", "e", "2026-12-31T23:59", "message", "사람이 많아요", "url", null));
        LocalDateTime now = LocalDateTime.of(2026, 12, 5, 18, 0);

        assertThat(new EventRepository(db.jdbc()).active(126.90, 35.13, 126.93, 35.15, now, 20)).containsExactly(
                new RoadEvent(5L, "festival", null, "양림 크리스마스 문화축제", "양림오거리", 35.1399, 126.9150, 300,
                        "2026-12-01T00:00", "2026-12-31T23:59", "사람이 많아요", null));
        var p = db.call("FROM road_event").params();
        assertThat((String) p.get(0)).isEqualTo(
                "POLYGON((126.9000000 35.1300000,126.9300000 35.1300000,126.9300000 35.1500000,126.9000000 35.1500000,126.9000000 35.1300000))");
        assertThat(p.subList(1, 4)).containsExactly(Timestamp.valueOf(now), Timestamp.valueOf(now), 20);
    }
}
