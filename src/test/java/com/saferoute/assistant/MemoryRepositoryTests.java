package com.saferoute.assistant;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 비서 기억 읽고 쓰기 SQL (가짜 DB) */
@DisplayName("비서 기억 읽고 쓰기 SQL")
class MemoryRepositoryTests {

    private final FakeDb db = new FakeDb();
    private final MemoryRepository repo = new MemoryRepository(db.jdbc());

    @Test
    @DisplayName("비서가 쓸 내 정보를 읽는다")
    void profile() {
        assertThat(repo.profile("none")).isNull();

        db.rows("FROM user_profile_now p", row("mode_now", "senior", "aid_now", "cane", "temp_active", 0,
                "temp_reason", null, "temp_until", null, "mode", "senior", "stairs_ok", 1, "prefer_transit", 2,
                "setup_done", 1, "consent_trips", 0));
        Profile p = repo.profile("g1");
        assertThat(p.modeNow()).isEqualTo("senior");
        assertThat(p.tempUntil()).isNull();
        assertThat(p.tempActive()).isFalse();
        assertThat(db.calls("FROM user_profile_now p").get(1).params()).containsExactly("g1");
    }

    @Test
    @DisplayName("내 정보는 허용한 칸만 저장한다")
    void saveProfileOnlyAllowedColumns() {
        repo.saveProfile("g1", Map.of("max_detour", 2)); // 비서는 이 칸을 못 고침
        assertThat(db.calls).isEmpty();

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("temp_mode", "wheelchair");
        f.put("temp_until", LocalDate.of(2026, 11, 30));
        repo.saveProfile("g1", f);
        FakeDb.Call c = db.call("INSERT INTO user_profile");
        assertThat(c.sql()).endsWith("temp_mode = VALUES(temp_mode), temp_until = VALUES(temp_until)");
        assertThat(c.params()).containsExactly("g1", "wheelchair", Date.valueOf("2026-11-30"));
    }

    @Test
    @DisplayName("습관·날씨 선택·가 본 곳을 읽는다")
    void habitsWeatherAndVisited() {
        db.rows("FROM trip_habit", row("to_name", "문화전당", "to_lat", 35.147, "to_lon", 126.92,
                        "start_slot", LocalTime.of(8, 30), "times", 3, "by_transit", 1, "by_walk", 2))
                .rows("FROM trip_weather_choice", row("trips", 6, "by_transit", 6, "by_walk", 0, "bad_feedback", 1))
                .rows("FROM trip_log", row("to_name", "남광주시장", "lat", 35.14, "lon", 126.93, "n", 4));

        assertThat(repo.habits("g1", 1, LocalTime.of(8, 0), LocalTime.of(9, 0))).containsExactly(
                new MemoryRepository.Habit("문화전당", 35.147, 126.92, LocalTime.of(8, 30), 3, 1, 2));
        assertThat(db.call("FROM trip_habit").params())
                .containsExactly("g1", 1, Time.valueOf("08:00:00"), Time.valueOf("09:00:00"));
        assertThat(repo.weatherChoice("g1", "rain")).isEqualTo(new MemoryRepository.WeatherChoice(6, 6, 0, 1));
        assertThat(repo.visited("g1", "시장")).containsExactly(
                new Conversation.Place("남광주시장", null, 35.14, 126.93));
    }

    @Test
    @DisplayName("날씨 선택이 없으면 빈 값")
    void weatherChoiceMissing() {
        assertThat(repo.weatherChoice("g1", "heat")).isNull();
    }

    @Test
    @DisplayName("지도 시설: 같은 뜻의 말을 나눠 읽고 저장한다")
    void layersSplitAliasesAndSave() {
        db.rows("FROM map_layer ORDER BY", row("layer", "toilet", "name", "화장실", "aliases", "화장실, 공중화장실,,"),
                row("layer", "cctv", "name", "CCTV", "aliases", null),
                row("layer", "bell", "name", "비상벨", "aliases", " "));

        assertThat(repo.layers()).containsExactly(
                new MemoryRepository.Layer("toilet", "화장실", java.util.List.of("화장실", "공중화장실")),
                new MemoryRepository.Layer("cctv", "CCTV", java.util.List.of()),
                new MemoryRepository.Layer("bell", "비상벨", java.util.List.of()));

        repo.saveLayer("g1", "cctv", true, LocalDate.of(2026, 11, 20), "밤");
        repo.saveLayer("g1", "bell", false, null, null);
        assertThat(db.calls("INSERT INTO user_map_layer").get(0).params())
                .containsExactly("g1", "cctv", 1, Date.valueOf("2026-11-20"), "밤");
        assertThat(db.calls("INSERT INTO user_map_layer").get(1).params()).containsExactly("g1", "bell", 0, null, null);
    }
}
