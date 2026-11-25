package com.saferoute.facility;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * poi_status 뷰를 실제 DB로 읽는다 (DB가 있어야 돈다, CI는 빈 MySQL).
 * 바다 위(경도 125.5, 위도 34.0) 시험 시설을 넣고 시험이 끝나면 되돌린다(@Transactional).
 */
@SpringBootTest
@Transactional
@DisplayName("시설 표 SQL (실제 MySQL)")
class FacilityRepositoryTests {

    private static final double LON = 125.5;
    private static final double LAT = 34.0;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FacilityRepository repo;

    @BeforeEach
    void addTestPlaces() {
        // 북쪽으로 약 110m: 24시간 화장실, 약 220m: 늘 닫힌 화장실(여는 시각 = 닫는 시각), 약 330m: 장애인 화장실,
        // 약 2.2km: 1km 범위 밖
        insert("toilet", "시험 화장실 24시간", 0.001, "'00:00:00'", "'24:00:00'", "'매일'", null, "senior");
        insert("toilet", "시험 화장실 닫힘", 0.002, "'05:00:00'", "'05:00:00'", "'매일'", null, "senior");
        insert("toilet", "시험 장애인 화장실", 0.003, "'00:00:00'", "'24:00:00'", "'매일'", 1, "senior,wheelchair");
        insert("toilet", "시험 먼 화장실", 0.02, "'00:00:00'", "'24:00:00'", "'매일'", null, "senior");
        insert("cctv", "시험 CCTV", 0.001, "NULL", "NULL", "NULL", null, "common");
    }

    private void insert(String kind, String name, double dLat, String from, String to, String days, Integer wc,
            String modes) {
        double lat = LAT + dLat;
        jdbc.update("INSERT INTO poi (kind, name, lon, lat, geom, open_from, open_to, open_days, wheelchair, modes,"
                + " detail, sources, built_at) VALUES (?, ?, ?, ?, ST_SRID(POINT(?, ?), 0), " + from + ", " + to
                + ", " + days + ", ?, ?, ?, '[]', NOW())", kind, name, LON, lat, LON, lat, wc, modes, name);
    }

    @Test
    @DisplayName("1km 안 화장실을 가까운 순으로, 지금 열었는지와 함께 준다 (2.2km·CCTV는 빠짐)")
    void nearSortsByDistanceWithStatus() {
        boolean wheelchairOnly = false;
        int radiusM = 1000;

        List<Facility> found = repo.near(LAT, LON, "toilet", wheelchairOnly, radiusM);

        assertThat(found).extracting(Facility::name)
                .containsExactly("시험 화장실 24시간", "시험 화장실 닫힘", "시험 장애인 화장실");
        assertThat(found).extracting(Facility::nowStatus).containsExactly("이용 가능", "닫힘", "이용 가능");
        assertThat(found.get(0).distanceM()).isBetween(100, 120); // 위도 0.001도 ≈ 111m
        Facility accessibleToilet = found.get(2);
        assertThat(accessibleToilet.modes()).containsExactly("senior", "wheelchair");
        assertThat(accessibleToilet.wheelchair()).isTrue();
    }

    @Test
    @DisplayName("휠체어 가능한 곳만 고른다")
    void nearWheelchairOnly() {
        assertThat(repo.near(LAT, LON, "toilet", true, 1000)).extracting(Facility::name)
                .containsExactly("시험 장애인 화장실");
    }

    @Test
    @DisplayName("지도 범위에서 종류와 개수 한도로 거른다")
    void withinFiltersKindsAndLimit() {
        double w = LON - 0.01, e = LON + 0.01, s = LAT - 0.01, n = LAT + 0.01;

        List<Facility> cctv = repo.within(w, s, e, n, Set.of("cctv"), 10);
        assertThat(cctv).extracting(Facility::name).containsExactly("시험 CCTV");
        assertThat(cctv.get(0).nowStatus()).isNull();
        assertThat(cctv.get(0).distanceM()).isNull();

        assertThat(repo.within(w, s, e, n, Set.of("toilet", "cctv"), 2)).hasSize(2);
    }

    @Test
    @DisplayName("지하철역은 엘리베이터 출구가 0개여도 남긴다")
    void subwayKeepsZeroElevatorExits() {
        jdbc.update("INSERT INTO poi (kind, name, lon, lat, geom, cnt, wheelchair, modes, detail, sources, built_at)"
                + " VALUES ('subway', '시험역', ?, ?, ST_SRID(POINT(?, ?), 0), 0, 0, 'common', '시험역', '[]', NOW())",
                LON, LAT, LON, LAT);
        double w = LON - 0.01, e = LON + 0.01, s = LAT - 0.01, n = LAT + 0.01;

        Facility station = repo.within(w, s, e, n, Set.of("subway"), 10).get(0);
        assertThat(station.count()).isZero();
        assertThat(station.wheelchair()).isFalse();
        assertThat(repo.within(w, s, e, n, Set.of("cctv"), 10).get(0).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("엘리베이터 = 엘리베이터 육교와 엘리베이터 출구")
    void elevatorMeansElevatorFootbridgesAndElevatorExits() {
        // 엘리베이터 육교(약 110m), 그냥 육교(약 220m), 엘리베이터 출구(약 330m), 엘리베이터 없는 출구(약 440m)
        place("footbridge", "승강기", "시험 엘리베이터 육교", 0.001, null);
        place("footbridge", "계단", "시험 그냥 육교", 0.002, null);
        place("subway_entrance", null, "시험역 1번 출구", 0.003, 1);
        place("subway_entrance", null, "시험역 2번 출구", 0.004, 0);

        assertThat(repo.near(LAT, LON, FacilityRepository.ELEVATOR, false, 1000)).extracting(Facility::name)
                .containsExactly("시험 엘리베이터 육교", "시험역 1번 출구");
        double w = LON - 0.01, e = LON + 0.01, s = LAT - 0.01, n = LAT + 0.01;
        assertThat(repo.within(w, s, e, n, Set.of(FacilityRepository.ELEVATOR), 10)).extracting(Facility::name)
                .containsExactlyInAnyOrder("시험 엘리베이터 육교", "시험역 1번 출구");
        assertThat(repo.within(w, s, e, n, Set.of(FacilityRepository.ELEVATOR, "cctv"), 10)).extracting(Facility::name)
                .containsExactlyInAnyOrder("시험 엘리베이터 육교", "시험역 1번 출구", "시험 CCTV");
    }

    private void place(String kind, String sub, String name, double dLat, Integer wc) {
        double lat = LAT + dLat;
        jdbc.update("INSERT INTO poi (kind, sub, name, lon, lat, geom, wheelchair, modes, detail, sources, built_at)"
                + " VALUES (?, ?, ?, ?, ?, ST_SRID(POINT(?, ?), 0), ?, 'wheelchair', ?, '[]', NOW())",
                kind, sub, name, LON, lat, LON, lat, wc, name);
    }
}
