package com.saferoute.guardian;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 보호자 위치 공유 표 SQL (가짜 DB) */
@DisplayName("보호자 위치 공유 SQL")
class GuardianShareRepositoryTests {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 16, 18, 0);

    private final FakeDb db = new FakeDb();
    private final GuardianShareRepository repo = new GuardianShareRepository(db.jdbc());

    @Test
    @DisplayName("공유를 만들고 찾는다")
    void insertAndFind() {
        GuardianShare s = new GuardianShare("tok", "g1", "재휘", "문화전당", 35.147, 126.92, "wheelchair", T,
                T.plusHours(3), null, null, null, 800, null, false, null, null);
        repo.insert(s);
        assertThat(db.call("INSERT INTO guardian_share").params()).containsExactly("tok", "g1", "재휘", "문화전당",
                35.147, 126.92, "wheelchair", Timestamp.valueOf(T), Timestamp.valueOf(T.plusHours(3)), null, null, null,
                800);

        db.rows("FROM guardian_share WHERE token", row("token", "tok", "guest_id", "g1", "walker_name", "재휘",
                "dest_name", "문화전당", "dest_lat", 35.147, "dest_lon", 126.92, "mode", "wheelchair",
                "started_at", T, "expires_at", T.plusHours(3), "last_lat", null, "last_lon", null, "last_at", null,
                "remain_m", 800, "eta_min", null, "off_route", 1, "ended_at", null, "end_reason", null));
        GuardianShare got = repo.find("tok").orElseThrow();
        assertThat(got.lastLat()).isNull();
        assertThat(got.etaMin()).isNull();
        assertThat(got.remainM()).isEqualTo(800);
        assertThat(got.offRoute()).isTrue();
        assertThat(got.startedAt()).isEqualTo(T);
    }

    @Test
    @DisplayName("없는 공유는 빈 값")
    void findMissing() {
        assertThat(repo.find("none")).isEmpty();
    }

    @Test
    @DisplayName("위치 옮기기·끝내기·만료·지우기")
    void moveEndExpireDelete() {
        db.updates("ended_at IS NULL AND (expires_at", 2).updates("DELETE FROM guardian_share WHERE ended_at", 4);

        assertThat(repo.move("tok", 35.1, 126.9, T, 500, 6, true)).isEqualTo(1);
        assertThat(db.call("SET last_lat = ?, last_lon = ?, last_at").params())
                .containsExactly(35.1, 126.9, Timestamp.valueOf(T), 500, 6, 1, "tok");
        assertThat(repo.end("tok", "arrived", T)).isEqualTo(1);
        assertThat(db.call("end_reason = ?, last_lat = NULL").params())
                .containsExactly(Timestamp.valueOf(T), "arrived", "tok");
        assertThat(repo.endOthers("g1", "tok", T)).isEqualTo(1);
        assertThat(db.call("token <> ?").params()).containsExactly(Timestamp.valueOf(T), "g1", "tok");
        assertThat(repo.expire(T, T.minusMinutes(30))).isEqualTo(2);
        assertThat(db.call("expires_at <= ?").params())
                .containsExactly(Timestamp.valueOf(T), Timestamp.valueOf(T), Timestamp.valueOf(T.minusMinutes(30)));
        assertThat(repo.deleteEndedBefore(T.minusMinutes(10))).isEqualTo(4);
        assertThat(repo.deleteByGuest("g1")).isEqualTo(1);
        assertThat(repo.move("tok", 35.1, 126.9, null, null, null, false)).isEqualTo(1);
    }
}
