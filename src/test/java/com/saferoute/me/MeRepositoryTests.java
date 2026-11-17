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

import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 내 정보·다닌 기록 SQL (가짜 DB) */
class MeRepositoryTests {

    private final FakeDb db = new FakeDb();
    private final MeRepository repo = new MeRepository(db.jdbc());

    @Test
    void findReadsProfileAndView() {
        db.rows("FROM user_profile u JOIN user_profile_now", row("mode_now", "wheelchair", "aid_now", "wheelchair",
                "temp_active", 1, "setup_done", 1, "ask_today", 0, "mode", "adult", "mobility_aid", null,
                "stairs_ok", 0, "max_detour", 1.3, "rest_every_m", 300, "prefer_transit", null,
                "temp_mode", "wheelchair", "temp_aid", "wheelchair", "temp_reason", "다리 다침",
                "temp_until", LocalDate.of(2026, 11, 30), "consent_trips", 1));

        MeRepository.Row r = repo.find("g1").orElseThrow();

        assertThat(r.modeNow()).isEqualTo("wheelchair");
        assertThat(r.tempActive()).isTrue();
        assertThat(r.askToday()).isFalse();
        assertThat(r.stairsOk()).isZero();
        assertThat(r.maxDetour()).isEqualTo(1.3);
        assertThat(r.preferTransit()).isNull();
        assertThat(r.tempUntil()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(r.consentTrips()).isTrue();
        assertThat(db.calls.get(0).params()).containsExactly("g1");
    }

    @Test
    void findEmptyAndNullValues() {
        assertThat(repo.find("none")).isEmpty();

        db.rows("FROM user_profile u", row("a", null, "b", null, "c", 0, "d", 0, "e", 0, "f", null, "g", null,
                "h", null, "i", null, "j", null, "k", null, "l", null, "m", null, "n", null, "o", null, "p", 0));
        MeRepository.Row r = repo.find("g2").orElseThrow();
        assertThat(r.maxDetour()).isNull();
        assertThat(r.tempUntil()).isNull();
    }

    @Test
    void saveOnlyAllowedColumnsAndUpserts() {
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
        assertThat(c.params()).containsExactly("g1", "senior", Date.valueOf(LocalDate.of(2026, 12, 1)), null);
    }

    @Test
    void saveWithNothingAllowedOnlyEnsuresRow() {
        repo.save("g1", Map.of("nope", 1));

        assertThat(db.call("INSERT IGNORE INTO user_profile").params()).containsExactly("g1");
        assertThat(db.calls).hasSize(1);
    }

    @Test
    void layers() {
        db.rows("FROM user_map_layer_now", row("layer", "cctv", "name", "CCTV", "shown", 1,
                        "until", LocalDate.of(2026, 11, 20), "reason", "밤길"),
                row("layer", "toilet", "name", "화장실", "shown", 0, "until", null, "reason", null));
        db.rows("FROM map_layer WHERE", row("n", 1));

        assertThat(repo.layers("g1")).containsExactly(
                new MeRequests.Layer("cctv", "CCTV", true, "2026-11-20", "밤길"),
                new MeRequests.Layer("toilet", "화장실", false, null, null));
        assertThat(repo.layerExists("cctv")).isTrue();

        repo.saveLayer("g1", "cctv", false, null, "끔");
        repo.saveLayer("g1", "toilet", true, LocalDate.of(2026, 11, 20), null);
        assertThat(db.calls("INSERT INTO user_map_layer").get(0).params()).containsExactly("g1", "cctv", 0, null, "끔");
        assertThat(db.calls("INSERT INTO user_map_layer").get(1).params())
                .containsExactly("g1", "toilet", 1, Date.valueOf(LocalDate.of(2026, 11, 20)), null);
        assertThat(repo.deleteLayer("g1", "cctv")).isEqualTo(1);
    }

    @Test
    void layerMissing() {
        db.rows("FROM map_layer WHERE", row("n", 0));
        assertThat(repo.layerExists("nope")).isFalse();
    }

    @Test
    void tripsInsertTrimEndAndRead() {
        db.generatedKey(42L).rows("COUNT(*) FROM trip_log", row("n", 7))
                .rows("FROM trip_log WHERE guest_id = ? AND result IN", row("travel", "transit", "result", "arrived",
                        "feedback", "bad", "feedback_reason", "계단"));

        long id = repo.insertTrip("g1", LocalDate.of(2026, 11, 16), 1, LocalTime.of(8, 30),
                new MeRepository.Trip("문화전당", 35.147, 126.92, "wheelchair", "walk", "safe", true, "rain", false,
                        null, 12));

        assertThat(id).isEqualTo(42L);
        List<Object> p = db.call("INSERT INTO trip_log").params();
        assertThat(p).containsExactly("g1", Date.valueOf("2026-11-16"), 1, Time.valueOf("08:30:00"), "문화전당", 35.147,
                126.92, "wheelchair", "walk", "safe", 1, "rain", 0, null, 12);
        assertThat(repo.tripCount("g1")).isEqualTo(7);
        assertThat(repo.trimTrips("g1")).isEqualTo(1);
        assertThat(db.call("DELETE FROM trip_log WHERE guest_id = ? AND id NOT IN").sql()).contains("LIMIT 200");
        assertThat(repo.endTrip("g1", 42, "arrived", 14, "good", null)).isEqualTo(1);
        assertThat(db.call("UPDATE trip_log").params()).containsExactly("arrived", 14, "good", null, 42L, "g1");
        assertThat(repo.recentDone("g1", 10)).containsExactly(
                new PreferenceLearner.Done("transit", "arrived", "bad", "계단"));
    }

    @Test
    void insertTripWithoutKeyIsZero() {
        assertThat(repo.insertTrip("g1", LocalDate.of(2026, 11, 16), 1, LocalTime.NOON,
                new MeRepository.Trip("집", 35.1, 126.9, "adult", "walk", "safe", false, null, true, "피곤", null)))
                .isZero();
    }

    @Test
    void deleteAllCountsEachTable() {
        db.updates("DELETE FROM trip_log", 5).updates("DELETE FROM user_map_layer", 2)
                .updates("DELETE FROM user_profile", 1).updates("DELETE FROM walk_profile", 3)
                .updates("DELETE FROM guardian_share", 0);

        assertThat(repo.deleteAll("g1")).isEqualTo(new MeRequests.Deleted(true, 5, 2, 3, 0));
        assertThat(db.calls).allSatisfy(c -> assertThat(c.params()).containsExactly("g1"));
    }

    @Test
    void tripCountWhenNoRow() {
        db.rows("COUNT(*) FROM trip_log", row("n", null));
        assertThat(repo.tripCount("g1")).isZero();
    }
}
