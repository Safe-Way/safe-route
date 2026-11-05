package com.saferoute.facility;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

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
    void nearSortsByDistanceWithStatus() {
        List<Facility> found = repo.near(LAT, LON, "toilet", false, 1000);

        assertThat(found).extracting(Facility::name)
                .containsExactly("시험 화장실 24시간", "시험 화장실 닫힘", "시험 장애인 화장실");
        assertThat(found).extracting(Facility::nowStatus).containsExactly("이용 가능", "닫힘", "이용 가능");
        assertThat(found.get(0).distanceM()).isBetween(100, 120);
        assertThat(found.get(2).modes()).containsExactly("senior", "wheelchair");
        assertThat(found.get(2).wheelchair()).isTrue();
    }

    @Test
    void nearWheelchairOnly() {
        assertThat(repo.near(LAT, LON, "toilet", true, 1000)).extracting(Facility::name)
                .containsExactly("시험 장애인 화장실");
    }

    @Test
    void withinFiltersKindsAndLimit() {
        double w = LON - 0.01, e = LON + 0.01, s = LAT - 0.01, n = LAT + 0.01;

        List<Facility> cctv = repo.within(w, s, e, n, Set.of("cctv"), 10);
        assertThat(cctv).extracting(Facility::name).containsExactly("시험 CCTV");
        assertThat(cctv.get(0).nowStatus()).isNull();
        assertThat(cctv.get(0).distanceM()).isNull();

        assertThat(repo.within(w, s, e, n, Set.of("toilet", "cctv"), 2)).hasSize(2);
    }

    @Test
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
}
