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
 * <p>무엇을: 가까운 곳(near)·지도 범위(within) SQL이 거리 순서, 지금 열었는지, 휠체어·엘리베이터 조건을 맞게 거르는지.
 * <p>어떻게: 가짜 DB가 아니라 진짜 MySQL에 poi 줄을 넣고 poi_status 뷰를 읽는다. 공간 함수(ST_Distance_Sphere 등)와
 * 뷰의 "지금 이용 가능" 계산은 가짜로 흉내 낼 수 없어서다.
 * <p>왜: 깨지면 지도에 엉뚱한 시설이 뜨거나, 닫힌 화장실을 연 곳으로 안내하거나, 휠체어 이용자에게 계단 육교를 엘리베이터로 보여 준다.
 */
@SpringBootTest
@Transactional
@DisplayName("시설 표 SQL (실제 MySQL)")
class FacilityRepositoryTests {

    /** 시험 기준점. 실제 시설이 하나도 없는 바다 위라 진짜 데이터와 섞이지 않는다 */
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

    /**
     * 기준점에서 북쪽으로 dLat 도 떨어진 곳에 시설 하나를 넣는다. from·to·days 는 SQL 조각 그대로 들어간다
     * (따옴표 포함 문자열이나 NULL). wc 는 휠체어 가능(1)·모름(null)
     */
    private void insert(String kind, String name, double dLat, String from, String to, String days, Integer wc,
            String modes) {
        double lat = LAT + dLat;
        jdbc.update("INSERT INTO poi (kind, name, lon, lat, geom, open_from, open_to, open_days, wheelchair, modes,"
                + " detail, sources, built_at) VALUES (?, ?, ?, ?, ST_SRID(POINT(?, ?), 0), " + from + ", " + to
                + ", " + days + ", ?, ?, ?, '[]', NOW())", kind, name, LON, lat, LON, lat, wc, modes, name);
    }

    @Test
    @DisplayName("1km 안 화장실을 가까운 순으로, 지금 열었는지와 함께 준다 (2.2km·CCTV는 빠짐)")
    void nearToiletsInOrder() {
        // 거리 순서·1km 자르기·종류 거르기·지금 상태를 한 번에 본다. 닫힌 화장실도 목록에는 남고 상태만 "닫힘"이어야
        // 창구(FacilityController.pick)가 "가장 가까운 닫힌 곳"을 고를 수 있다
        boolean wheelchairOnly = false;
        int radius = 1000;

        List<Facility> toilets = repo.near(LAT, LON, "toilet", wheelchairOnly, radius);

        assertThat(toilets).extracting(Facility::name)
                .containsExactly("시험 화장실 24시간", "시험 화장실 닫힘", "시험 장애인 화장실");
        assertThat(toilets).extracting(Facility::nowStatus).containsExactly("이용 가능", "닫힘", "이용 가능");
        assertThat(toilets.get(0).distanceM()).isBetween(100, 120); // 위도 0.001도 ≈ 111m
        // modes 는 "senior,wheelchair" 글자를 목록으로, wheelchair 는 1 을 true 로 바꿔 읽어야 한다
        Facility wheelchairToilet = toilets.get(2);
        assertThat(wheelchairToilet.modes()).containsExactly("senior", "wheelchair");
        assertThat(wheelchairToilet.wheelchair()).isTrue();
    }

    @Test
    @DisplayName("휠체어 가능한 곳만 고른다")
    void nearWheelchairOnly() {
        // wheelchair 칸이 1 인 것만. NULL(모름)인 화장실은 빠져야 휠체어 이용자를 못 쓰는 곳으로 보내지 않는다
        assertThat(repo.near(LAT, LON, "toilet", true, 1000)).extracting(Facility::name)
                .containsExactly("시험 장애인 화장실");
    }

    @Test
    @DisplayName("지도 범위에서 종류와 개수 한도로 거른다")
    void withinFiltersKindsAndLimit() {
        // 기준점 둘레 ±0.01도(약 1km) 네모. 2.2km 떨어진 화장실은 이 네모 밖이다
        double w = LON - 0.01, e = LON + 0.01, s = LAT - 0.01, n = LAT + 0.01;

        List<Facility> cctv = repo.within(w, s, e, n, Set.of("cctv"), 10);
        assertThat(cctv).extracting(Facility::name).containsExactly("시험 CCTV");
        // CCTV는 운영 시간이 없어 상태가 null, 지도 범위 조회는 기준점이 없어 거리도 null
        assertThat(cctv.get(0).nowStatus()).isNull();
        assertThat(cctv.get(0).distanceM()).isNull();
        // 화장실 3곳 + CCTV 1곳이 범위 안에 있어도 한도 2 에서 자른다

        assertThat(repo.within(w, s, e, n, Set.of("toilet", "cctv"), 2)).hasSize(2);
    }

    @Test
    @DisplayName("지하철역은 엘리베이터 출구가 0개여도 남긴다")
    void subwayKeepsZeroElevatorExits() {
        // 지하철역의 cnt 는 엘리베이터 출구 수라 0 이 "엘리베이터 없음"이라는 뜻이다. 다른 시설처럼 최소 1 로 올리면
        // 엘리베이터 없는 역이 있는 것처럼 보인다
        jdbc.update("INSERT INTO poi (kind, name, lon, lat, geom, cnt, wheelchair, modes, detail, sources, built_at)"
                + " VALUES ('subway', '시험역', ?, ?, ST_SRID(POINT(?, ?), 0), 0, 0, 'common', '시험역', '[]', NOW())",
                LON, LAT, LON, LAT);
        double w = LON - 0.01, e = LON + 0.01, s = LAT - 0.01, n = LAT + 0.01;

        Facility station = repo.within(w, s, e, n, Set.of("subway"), 10).get(0);
        assertThat(station.count()).isZero();
        assertThat(station.wheelchair()).isFalse();
        // 비교용: CCTV는 cnt 가 NULL 이어도 한 점에 최소 1 개
        assertThat(repo.within(w, s, e, n, Set.of("cctv"), 10).get(0).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("엘리베이터 = 엘리베이터 육교와 엘리베이터 출구")
    void elevatorMeansElevatorFootbridgesAndElevatorExits() {
        // elevator 는 표에 없는 묶음 이름이다: 육교는 sub 에 "승강기"가 든 것, 출구는 wheelchair = 1 인 것.
        // 계단 육교·엘리베이터 없는 출구가 섞여 들어오면 휠체어 이용자를 계단으로 보낸다
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
        // elevator 와 보통 종류를 같이 달라고 해도 두 조건이 OR 로 합쳐져야 한다
        assertThat(repo.within(w, s, e, n, Set.of(FacilityRepository.ELEVATOR, "cctv"), 10)).extracting(Facility::name)
                .containsExactlyInAnyOrder("시험 엘리베이터 육교", "시험역 1번 출구", "시험 CCTV");
    }

    /** 세부 종류(sub)를 정해 넣는 insert. 운영 시간 칸은 비우고 modes 는 wheelchair 로 둔다 */
    private void place(String kind, String sub, String name, double dLat, Integer wc) {
        double lat = LAT + dLat;
        jdbc.update("INSERT INTO poi (kind, sub, name, lon, lat, geom, wheelchair, modes, detail, sources, built_at)"
                + " VALUES (?, ?, ?, ?, ?, ST_SRID(POINT(?, ?), 0), ?, 'wheelchair', ?, '[]', NOW())",
                kind, sub, name, LON, lat, LON, lat, wc, name);
    }
}
