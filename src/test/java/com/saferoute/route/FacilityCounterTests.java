package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.justAbove;
import static com.saferoute.route.TestRoutes.justBelow;
import static com.saferoute.route.TestRoutes.props;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.RouteProperties.CountRadius;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.facility.Poi;

/** 경로 주변 시설 세기 (결합 반경 30m·50m). 시설은 poi(중복을 이미 합친 표) 목록이다 */
@DisplayName("경로 주변 시설 세기")
class FacilityCounterTests {

    /** 동서로 약 200m 뻗은 길 */
    static final List<double[]> LINE = List.of(S_P, T_P);
    /** 위도 0.0001도 ≈ 11.1m */
    static final double M11 = 0.0001;

    static Counts count(List<Poi> pois) {
        return new FacilityCounter(props()).countAlong(List.of(LINE), pois).get(0);
    }

    static Poi at(String kind, long id, double northM, int count) {
        double[] p = TestRoutes.north(new double[] {126.9311, LAT}, northM);
        return TestRoutes.plain(kind, id, p[0], p[1], count);
    }

    @Test
    @DisplayName("CCTV는 곳 수가 아니라 카메라 대수로 센다")
    void cctvIsCountedByCameras() {
        Counts c = count(List.of(at("cctv", 5, -45, 4))); // 남쪽 45m, 카메라 4대

        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(4);
    }

    @Test
    @DisplayName("경로에서 종류별 반경 이하인 시설만 센다")
    void countRadiusLimit() {
        // route.yml count-radius-m, FacilityCounter.count
        // 좌표 거리라 경계값 자체는 못 만들어 경계 ±0.01m로 본다
        CountRadius cr = props().countRadiusM();
        Map<String, Double> radius = Map.of("light", cr.light(), "cctv", cr.cctv(), "bell", cr.bell(), "store", cr.help(),
                "toilet", cr.rest(), "school_zone", cr.school());
        radius.forEach((kind, r) -> {
            FacilityGroup g = FacilityGroup.of(kind);
            assertThat(count(List.of(at(kind, 1, justBelow(r), 1))).get(g)).as(kind + " 안").isEqualTo(1);
            assertThat(count(List.of(at(kind, 2, -justAbove(r), 1))).get(g)).as(kind + " 밖").isZero();
        });
    }

    @Test
    @DisplayName("도움 시설은 경찰·편의점·아동안전지킴이집이고 화장실은 쉼 곳으로 센다")
    void helpGroupCountsPoliceStoreAndGuardHouse() {
        Counts c = count(List.of(
                at("police", 1, 10, 1),
                at("store", 2, 20, 1),
                at("guard_house", 3, 30, 1),
                at("toilet", 4, 5, 1)));     // 화장실은 도움 지점이 아니라 쉼 곳 (2026-11-06)

        assertThat(c.get(FacilityGroup.HELP)).isEqualTo(3);
        assertThat(c.get(FacilityGroup.REST)).isEqualTo(1);
        assertThat(c.passed()).hasSize(4);
    }

    @Test
    @DisplayName("같은 자리의 다른 종류 시설은 각각 센다")
    void differentKindsAtSamePointAreAllCounted() {
        // 중복 합치기는 poi 표에서 이미 함
        Counts c = count(List.of(at("cctv", 1, 10, 1), at("bell", 2, 10, 1), at("store", 3, 10, 1)));

        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.BELL)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.HELP)).isEqualTo(1);
    }

    @Test
    @DisplayName("경로 선마다 따로, 넘긴 순서대로 센다")
    void eachLineCountedSeparatelyInOrder() {
        List<double[]> north = List.of(new double[] {126.93000, LAT + 10 * M11}, new double[] {126.93220, LAT + 10 * M11});
        List<Poi> pois = List.of(at("light", 1, 105, 1)); // 북쪽 길에서 약 6m

        List<Counts> counts = new FacilityCounter(props()).countAlong(List.of(LINE, north), pois);

        assertThat(counts.get(0).get(FacilityGroup.LIGHT)).isZero();
        assertThat(counts.get(1).get(FacilityGroup.LIGHT)).isEqualTo(1);
    }
}
