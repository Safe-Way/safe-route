package com.saferoute.guardian;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/**
 * 보호자 위치 공유 표 SQL (가짜 DB).
 * 무엇을: GuardianShareRepository 가 guardian_share 표에 공유를 넣고, 위치를 덮어쓰고, 끝내고, 지우는 SQL을 맞게 만드는지.
 * 어떻게: FakeDb 로 실행한 SQL·값을 모으고 돌려줄 줄과 바뀐 줄 수를 정한다. 시각 T 는 고정값이다.
 * 왜: ? 순서가 어긋나면 보호자 지도에 엉뚱한 위치가 뜨고, 빈 칸(null)을 0으로 읽으면 위치 (0, 0)·남은 거리 0 처럼
 *     거짓 정보가 보이며, 끝내기·만료가 위치를 안 비우면 공유가 끝난 뒤에도 마지막 위치가 남는다.
 */
@DisplayName("보호자 위치 공유 SQL")
class GuardianShareRepositoryTests {

    // 공유 시작 시각으로 쓰는 아무 고정 시각 (시험이 날마다 같은 값을 쓰게)
    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 16, 18, 0);

    private final FakeDb db = new FakeDb();
    private final GuardianShareRepository repo = new GuardianShareRepository(db.jdbc());

    @Test
    @DisplayName("공유를 만들고 찾는다")
    void insertAndFind() {
        // 시작할 때는 위치·남은 시간이 아직 없다(null). 넣을 때 null 그대로, 읽을 때도 0이 아니라 null 이어야 한다
        GuardianShare s = new GuardianShare("tok", "g1", "재휘", "문화전당", 35.147, 126.92, "wheelchair", T,
                T.plusHours(3), null, null, null, 800, null, false, null, null);   // 만료 = 시작 3시간 뒤
        repo.insert(s);
        assertThat(db.call("INSERT INTO guardian_share").params()).containsExactly("tok", "g1", "재휘", "문화전당",
                35.147, 126.92, "wheelchair", Timestamp.valueOf(T), Timestamp.valueOf(T.plusHours(3)), null, null, null,
                800);

        // 읽기는 칸 이름으로 한다. off_route 는 표에서 0·1 → 참·거짓
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
        // 없는(끝나고 지워진) 링크는 DB 오류가 아니라 빈 값 → 서비스가 404 "링크가 끝났거나 없어요"로 바꾼다
        assertThat(repo.find("none")).isEmpty();
    }

    @Test
    @DisplayName("위치 옮기기·끝내기·만료·지우기")
    void moveEndExpireDelete() {
        // 공유 한 건의 뒷부분: 위치 덮어쓰기 → 끝내기 → 같은 게스트의 옛 공유 닫기 → 만료 → 끝난 줄 지우기 → 게스트로 지우기
        // 만료는 2줄, 끝난 줄 지우기는 4줄로 정하고 나머지는 FakeDb 기본(1줄)
        db.updates("ended_at IS NULL AND (expires_at", 2).updates("DELETE FROM guardian_share WHERE ended_at", 4);

        assertThat(repo.move("tok", 35.1, 126.9, T, 500, 6, true)).isEqualTo(1);
        assertThat(db.call("SET last_lat = ?, last_lon = ?, last_at").params())
                .containsExactly(35.1, 126.9, Timestamp.valueOf(T), 500, 6, 1, "tok");   // offRoute true → 1
        assertThat(repo.end("tok", "arrived", T)).isEqualTo(1);
        assertThat(db.call("end_reason = ?, last_lat = NULL").params())
                .containsExactly(Timestamp.valueOf(T), "arrived", "tok");
        assertThat(repo.endOthers("g1", "tok", T)).isEqualTo(1);
        assertThat(db.call("token <> ?").params()).containsExactly(Timestamp.valueOf(T), "g1", "tok");
        // 만료 조건 둘: 만료 시각(3시간)이 지났거나, 위치가 30분 동안 안 옴 (silentSince = T - 30분)
        assertThat(repo.expire(T, T.minusMinutes(30))).isEqualTo(2);
        assertThat(db.call("expires_at <= ?").params())
                .containsExactly(Timestamp.valueOf(T), Timestamp.valueOf(T), Timestamp.valueOf(T.minusMinutes(30)));
        assertThat(repo.deleteEndedBefore(T.minusMinutes(10))).isEqualTo(4);
        assertThat(repo.deleteByGuest("g1")).isEqualTo(1);
        // 시각·남은 거리·남은 시간이 없는 위치도 오류 없이 넣는다 (ts(null) → null)
        assertThat(repo.move("tok", 35.1, 126.9, null, null, null, false)).isEqualTo(1);
    }
}
