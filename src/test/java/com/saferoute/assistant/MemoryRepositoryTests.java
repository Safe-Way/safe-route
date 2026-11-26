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

/**
 * 비서 기억 읽고 쓰기 SQL (가짜 DB)
 * <p>무엇을: {@link MemoryRepository}가 내 정보·습관·날씨 선택·가 본 곳·지도 시설 설정을 읽고 쓰는 SQL과 값.
 * <p>어떻게: FakeDb(가짜 JDBC)가 SQL에 든 글자로 정해 둔 줄을 돌려주고, 실행한 SQL과 넣은 값을 남긴다. 진짜 DB는 안 쓴다.
 * <p>왜: 비서가 칸 이름을 SQL에 직접 넣기 때문에 허용 목록이 뚫리면 아무 칸이나 고칠 수 있다.
 * 날짜·시각을 java.sql 형으로 안 바꾸면 MySQL에 엉뚱한 값이 들어가고, 줄이 없을 때 null 처리를 잘못하면 비서가 처음 쓰는 사람에게 터진다.
 */
@DisplayName("비서 기억 읽고 쓰기 SQL")
class MemoryRepositoryTests {

    private final FakeDb db = new FakeDb();
    private final MemoryRepository repo = new MemoryRepository(db.jdbc());

    // 처음 쓰는 사람(줄 없음)은 null, 있는 사람은 칸을 순서대로 읽는다.
    // temp_until 이 NULL 이어도 날짜 변환에서 터지지 않고, 0/1 숫자 칸이 boolean 으로 바뀌는지 본다
    @Test
    @DisplayName("비서가 쓸 내 정보를 읽는다")
    void profile() {
        // 아직 rows 를 정하지 않아 빈 결과 → null
        assertThat(repo.profile("none")).isNull();

        // 칸 순서가 SELECT 순서와 같아야 한다 (저장소가 칸 번호로 읽음)
        db.rows("FROM user_profile_now p", row("mode_now", "senior", "aid_now", "cane", "temp_active", 0,
                "temp_reason", null, "temp_until", null, "mode", "senior", "stairs_ok", 1, "prefer_transit", 2,
                "setup_done", 1, "consent_trips", 0));
        Profile p = repo.profile("g1");
        assertThat(p.modeNow()).isEqualTo("senior");
        assertThat(p.tempUntil()).isNull();
        assertThat(p.tempActive()).isFalse();
        // 0번은 위의 "none" 호출, 1번이 "g1" 호출
        assertThat(db.calls("FROM user_profile_now p").get(1).params()).containsExactly("g1");
    }

    // 칸 이름이 SQL 글자로 들어가므로 PROFILE_COLUMNS 밖의 칸은 버려야 한다 (SQL 주입·권한 밖 수정 막기).
    // 허용 칸만 있으면 INSERT … ON DUPLICATE KEY UPDATE 로 넣고, LocalDate 는 java.sql.Date 로 바꾼다
    @Test
    @DisplayName("내 정보는 허용한 칸만 저장한다")
    void saveProfileOnlyAllowedColumns() {
        repo.saveProfile("g1", Map.of("max_detour", 2)); // 비서는 이 칸을 못 고침
        assertThat(db.calls).isEmpty();

        // 넣은 순서대로 SQL 칸이 이어지는지 보려고 순서가 있는 LinkedHashMap 을 쓴다
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("temp_mode", "wheelchair");
        f.put("temp_until", LocalDate.of(2026, 11, 30));
        repo.saveProfile("g1", f);
        FakeDb.Call c = db.call("INSERT INTO user_profile");
        assertThat(c.sql()).endsWith("temp_mode = VALUES(temp_mode), temp_until = VALUES(temp_until)");
        assertThat(c.params()).containsExactly("g1", "wheelchair", Date.valueOf("2026-11-30"));
    }

    // 세 뷰를 한 번에 정해 두고 각각 읽는다. 습관은 시간대 범위를 java.sql.Time 으로 넘기는지,
    // 가 본 곳은 좌표만 있고 주소(두 번째 값)는 null 로 채우는지 본다
    @Test
    @DisplayName("습관·날씨 선택·가 본 곳을 읽는다")
    void habitsWeatherAndVisited() {
        db.rows("FROM trip_habit", row("to_name", "문화전당", "to_lat", 35.147, "to_lon", 126.92,
                        "start_slot", LocalTime.of(8, 30), "times", 3, "by_transit", 1, "by_walk", 2))
                .rows("FROM trip_weather_choice", row("trips", 6, "by_transit", 6, "by_walk", 0, "bad_feedback", 1))
                .rows("FROM trip_log", row("to_name", "남광주시장", "lat", 35.14, "lon", 126.93, "n", 4));

        // 인자: 게스트, 요일(1), 시간대 시작·끝
        assertThat(repo.habits("g1", 1, LocalTime.of(8, 0), LocalTime.of(9, 0))).containsExactly(
                new MemoryRepository.Habit("문화전당", 35.147, 126.92, LocalTime.of(8, 30), 3, 1, 2));
        assertThat(db.call("FROM trip_habit").params())
                .containsExactly("g1", 1, Time.valueOf("08:00:00"), Time.valueOf("09:00:00"));
        assertThat(repo.weatherChoice("g1", "rain")).isEqualTo(new MemoryRepository.WeatherChoice(6, 6, 0, 1));
        // 진짜 DB는 LIKE '%시장%'로 거르지만 가짜 DB는 정해 둔 줄을 그대로 준다. 여기서는 줄을 Place 로 바꾸는 것만 본다
        assertThat(repo.visited("g1", "시장")).containsExactly(
                new Conversation.Place("남광주시장", null, 35.14, 126.93));
    }

    // 그 날씨에 다녀온 기록이 없는 사람도 있다. 빈 객체가 아니라 null 이어야 비서가 "기록 없음"으로 안다
    @Test
    @DisplayName("날씨 선택이 없으면 빈 값")
    void weatherChoiceMissing() {
        assertThat(repo.weatherChoice("g1", "heat")).isNull();
    }

    // aliases 칸은 사람이 손으로 채운 쉼표 목록이라 빈 조각(",,")·공백·NULL 이 섞인다. 모두 빈 말 없이 읽어야 한다.
    // 저장은 shown 을 1/0 숫자로, until 이 없으면 NULL 로 넣는다
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

        // 인자: 게스트, 시설, 보이기, 언제까지, 까닭
        repo.saveLayer("g1", "cctv", true, LocalDate.of(2026, 11, 20), "밤");
        repo.saveLayer("g1", "bell", false, null, null);
        assertThat(db.calls("INSERT INTO user_map_layer").get(0).params())
                .containsExactly("g1", "cctv", 1, Date.valueOf("2026-11-20"), "밤");
        assertThat(db.calls("INSERT INTO user_map_layer").get(1).params()).containsExactly("g1", "bell", 0, null, null);
    }
}
