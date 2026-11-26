package com.saferoute.me;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/**
 * 내 정보·다닌 기록 SQL (가짜 DB).
 * 무엇을: MeRepository 가 user_profile·user_map_layer·trip_log 등에 맞는 SQL을 만들고, 읽은 칸을 맞게 바꾸는지.
 * 어떻게: FakeDb 로 실행한 SQL·값을 모으고 돌려줄 줄을 정한다. 줄은 칸 이름이 아니라 순서(1번 칸부터)로 읽히므로
 *        row(...) 에 적는 칸 순서가 SELECT 순서와 같아야 한다.
 * 왜: 깨지면 휠체어 사용자에게 계단 길을 주는 등 설정이 틀리게 읽히거나, 칸 이름으로 SQL을 끼워 넣을 수 있거나,
 *     "내 기록 지우기"가 일부 표를 빠뜨려 개인 기록이 남는다.
 */
@DisplayName("내 정보·다닌 기록 SQL")
class MeRepositoryTests {

    private final FakeDb db = new FakeDb();
    private final MeRepository repo = new MeRepository(db.jdbc());

    @Test
    @DisplayName("내 정보를 표와 뷰에서 읽는다")
    void findReadsProfileAndView() {
        // 지금 적용되는 값(_now 뷰: 임시 휠체어)과 원래 설정(adult)을 한 줄로 읽는다. 0·1 은 참·거짓으로,
        // stairs_ok 처럼 0·1·null 이 다 뜻이 있는 칸은 숫자 그대로, null 은 null 로 남아야 한다
        db.rows("FROM user_profile u JOIN user_profile_now", row("mode_now", "wheelchair", "aid_now", "wheelchair",
                "temp_active", 1, "setup_done", 1, "ask_today", 0, "mode", "adult", "mobility_aid", null,
                "stairs_ok", 0, "max_detour", 1.3, "rest_every_m", 300, "prefer_transit", null,
                "temp_mode", "wheelchair", "temp_aid", "wheelchair", "temp_reason", "다리 다침",
                "temp_until", LocalDate.of(2026, 11, 30), "consent_trips", 1));

        MeRepository.Row r = repo.find("g1").orElseThrow();

        assertThat(r.modeNow()).isEqualTo("wheelchair");
        assertThat(r.tempActive()).isTrue();
        assertThat(r.askToday()).isFalse();
        assertThat(r.stairsOk()).isZero();   // false 가 아니라 숫자 0 (null 이면 "정하지 않음")
        assertThat(r.maxDetour()).isEqualTo(1.3);
        assertThat(r.preferTransit()).isNull();
        assertThat(r.tempUntil()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(r.consentTrips()).isTrue();
        assertThat(db.calls.get(0).params()).containsExactly("g1");
    }

    @Test
    @DisplayName("없는 사람·빈 값은 빈 값으로 돌려준다")
    void findEmptyAndNullValues() {
        // 숫자 칸이 비었을 때 getDouble 처럼 0으로 읽으면 "우회 0배" 같은 엉뚱한 설정이 된다. null 은 null 로 남아야 한다
        assertThat(repo.find("none")).isEmpty();   // 줄이 없으면 Optional.empty

        // 칸 이름(a~p)은 아무거나: 순서로만 읽는다. 16칸, 참·거짓 칸(3·4·5·16번)만 0
        db.rows("FROM user_profile u", row("a", null, "b", null, "c", 0, "d", 0, "e", 0, "f", null, "g", null,
                "h", null, "i", null, "j", null, "k", null, "l", null, "m", null, "n", null, "o", null, "p", 0));
        MeRepository.Row r = repo.find("g2").orElseThrow();
        assertThat(r.maxDetour()).isNull();
        assertThat(r.tempUntil()).isNull();
    }

    @Test
    @DisplayName("허용한 칸만 저장하고, 있으면 고치고 없으면 넣는다")
    void saveOnlyAllowedColumnsAndUpserts() {
        // 칸 이름을 SQL 글자에 그대로 넣으므로, 허용 목록(COLUMNS)에 없는 이름을 버리지 않으면 SQL을 끼워 넣을 수 있다.
        // LinkedHashMap 이라 넣은 순서대로 SQL이 만들어져 아래 문장과 정확히 비교할 수 있다
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("mode", "senior");
        f.put("temp_until", LocalDate.of(2026, 12, 1));
        f.put("guest_id; DROP TABLE x", "x"); // 목록에 없는 칸은 버림
        f.put("temp_reason", null); // null 은 비우기

        repo.save("g1", f);

        FakeDb.Call c = db.call("INSERT INTO user_profile");
        assertThat(c.sql()).isEqualTo("INSERT INTO user_profile (guest_id, mode, temp_until, temp_reason)"
                + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE mode = VALUES(mode),"
                + " temp_until = VALUES(temp_until), temp_reason = VALUES(temp_reason)");
        // LocalDate 는 JDBC 가 아는 java.sql.Date 로 바꿔 넣는다
        assertThat(c.params()).containsExactly("g1", "senior", Date.valueOf(LocalDate.of(2026, 12, 1)), null);
    }

    @Test
    @DisplayName("저장할 칸이 없으면 줄만 만들어 둔다")
    void saveWithNothingAllowedOnlyEnsuresRow() {
        // 고칠 칸이 하나도 없으면 "INSERT INTO user_profile (guest_id) ... UPDATE " 처럼 깨진 SQL이 될 수 있다.
        // 대신 빈 줄만 만들어 둔다 (지도 설정은 user_profile 줄이 있어야 저장된다)
        repo.save("g1", Map.of("nope", 1));

        assertThat(db.call("INSERT IGNORE INTO user_profile").params()).containsExactly("g1");
        assertThat(db.calls).hasSize(1);   // 다른 SQL은 안 부름
    }

    @Test
    @DisplayName("내 지도 시설 설정을 읽고 저장한다")
    void layers() {
        // 지도 시설(CCTV·화장실 등) 켜고 끄기: 읽을 때 날짜는 앱에 보낼 글자("2026-11-20")로, 저장할 때 참·거짓은 1·0으로
        db.rows("FROM user_map_layer_now", row("layer", "cctv", "name", "CCTV", "shown", 1,
                        "until", LocalDate.of(2026, 11, 20), "reason", "밤길"),
                row("layer", "toilet", "name", "화장실", "shown", 0, "until", null, "reason", null));
        db.rows("FROM map_layer WHERE", row("n", 1));   // 시설 사전에 있는 이름 (COUNT = 1)

        assertThat(repo.layers("g1")).containsExactly(
                new MeRequests.Layer("cctv", "CCTV", true, "2026-11-20", "밤길"),
                new MeRequests.Layer("toilet", "화장실", false, null, null));
        assertThat(repo.layerExists("cctv")).isTrue();

        repo.saveLayer("g1", "cctv", false, null, "끔");
        repo.saveLayer("g1", "toilet", true, LocalDate.of(2026, 11, 20), null);
        assertThat(db.calls("INSERT INTO user_map_layer").get(0).params()).containsExactly("g1", "cctv", 0, null, "끔");
        assertThat(db.calls("INSERT INTO user_map_layer").get(1).params())
                .containsExactly("g1", "toilet", 1, Date.valueOf(LocalDate.of(2026, 11, 20)), null);
        assertThat(repo.deleteLayer("g1", "cctv")).isEqualTo(1);   // FakeDb 기본: DELETE 는 1줄
    }

    @Test
    @DisplayName("없는 지도 시설 이름은 없다고 답한다")
    void layerMissing() {
        // COUNT 가 0이면 false. 사전(map_layer)에 없는 이름을 미리 거르는 데 쓰려고 만든 것 (지금 운영 코드에서 부르는 곳은 없음)
        db.rows("FROM map_layer WHERE", row("n", 0));
        assertThat(repo.layerExists("nope")).isFalse();
    }

    @Test
    @DisplayName("다닌 기록: 넣기·오래된 것 정리·끝내기·읽기")
    void tripsInsertTrimEndAndRead() {
        // 기록 한 건의 처음부터 끝까지: 넣고 새 번호 받기 → 개수 세기 → 200건 넘는 것 정리 → 결과 적기 → 최근 끝난 기록 읽기
        // 42 는 INSERT 뒤 DB가 준다고 치는 새 번호
        db.generatedKey(42L).rows("COUNT(*) FROM trip_log", row("n", 7))
                .rows("FROM trip_log WHERE guest_id = ? AND result IN", row("travel", "transit", "result", "arrived",
                        "feedback", "bad", "feedback_reason", "계단"));

        long id = repo.insertTrip("g1", LocalDate.of(2026, 11, 16), 1, LocalTime.of(8, 30),
                new MeRepository.Trip("문화전당", 35.147, 126.92, "wheelchair", "walk", "safe", true, "rain", false,
                        null, 12));

        assertThat(id).isEqualTo(42L);
        List<Object> p = db.call("INSERT INTO trip_log").params();
        // 15칸 순서 그대로. by_assistant true → 1, night false → 0
        assertThat(p).containsExactly("g1", Date.valueOf("2026-11-16"), 1, Time.valueOf("08:30:00"), "문화전당", 35.147,
                126.92, "wheelchair", "walk", "safe", 1, "rain", 0, null, 12);
        assertThat(repo.tripCount("g1")).isEqualTo(7);
        assertThat(repo.trimTrips("g1")).isEqualTo(1);
        assertThat(db.call("DELETE FROM trip_log WHERE guest_id = ? AND id NOT IN").sql()).contains("LIMIT 200");   // KEEP_TRIPS
        assertThat(repo.endTrip("g1", 42, "arrived", 14, "good", null)).isEqualTo(1);
        // WHERE 에 guest_id 도 있어야 남의 기록 번호로 결과를 고치지 못한다
        assertThat(db.call("UPDATE trip_log").params()).containsExactly("arrived", 14, "good", null, 42L, "g1");
        assertThat(repo.recentDone("g1", 10)).containsExactly(
                new PreferenceLearner.Done("transit", "arrived", "bad", "계단"));
    }

    @Test
    @DisplayName("다닌 기록을 넣고 번호를 못 받으면 0")
    void insertTripWithoutKeyIsZero() {
        // generatedKey 를 정하지 않아 DB가 새 번호를 안 준 경우. 오류(NullPointerException) 대신 0을 돌려줘야 한다
        assertThat(repo.insertTrip("g1", LocalDate.of(2026, 11, 16), 1, LocalTime.NOON,
                new MeRepository.Trip("집", 35.1, 126.9, "adult", "walk", "safe", false, null, true, "피곤", null)))
                .isZero();
    }

    @Test
    @DisplayName("내 기록 지우기는 표마다 지운 줄 수를 센다")
    void deleteAllCountsEachTable() {
        // "내 기록 지우기"는 표마다 몇 줄 지웠는지 앱에 알려 준다. 표마다 다른 수를 정해 둬서 칸이 서로 바뀌면 바로 걸린다
        // (1년 안 쓴 게스트 정리 RecordCleanup 도 이 메서드를 쓴다)
        db.updates("DELETE FROM trip_log", 5).updates("DELETE FROM user_map_layer", 2)
                .updates("DELETE FROM user_profile", 1).updates("DELETE FROM walk_profile", 3)
                .updates("DELETE FROM guardian_share", 0);

        assertThat(repo.deleteAll("g1")).isEqualTo(new MeRequests.Deleted(true, 5, 2, 3, 0));
        assertThat(db.calls).allSatisfy(c -> assertThat(c.params()).containsExactly("g1"));   // 모든 DELETE 가 이 게스트만
    }

    @Test
    @DisplayName("다닌 기록이 없으면 0건")
    void tripCountWhenNoRow() {
        // queryForObject 가 null 을 돌려줘도 int 로 바꾸다 NullPointerException 이 나지 않게 0으로 본다
        db.rows("COUNT(*) FROM trip_log", row("n", null));
        assertThat(repo.tripCount("g1")).isZero();
    }
}
