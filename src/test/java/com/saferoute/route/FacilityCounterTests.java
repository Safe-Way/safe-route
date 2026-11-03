package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.props;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.facility.Poi;

/** 경로 주변 시설 세기 (결합 반경 30m·50m). 시설은 poi(중복을 이미 합친 표) 목록이다 */
class FacilityCounterTests {

    /** 동서로 약 200m 뻗은 길 */
    static final List<double[]> LINE = List.of(S_P, T_P);
    /** 위도 0.0001도 ≈ 11.1m */
    static final double M11 = 0.0001;

    static Counts count(List<Poi> pois) {
        return new FacilityCounter(props()).countAlong(List.of(LINE), pois).get(0);
    }

    static Poi at(String kind, long id, double northM, int count) {
        return new Poi(kind, null, null, 126.9311, LAT + northM / 111_195.0, null, null, null, null, null,
                null, null, null, null, id, count);
    }

    @Test
    void radiusPerGroup() {
        Counts c = count(List.of(
                at("light", 1, 28, 1),   // 조명 30m 안
                at("light", 2, 32, 1),   // 조명 30m 밖
                at("bell", 3, 48, 1),    // 50m 안
                at("bell", 4, 52, 1),    // 50m 밖
                at("cctv", 5, -45, 4))); // 남쪽 45m, 카메라 4대

        assertThat(c.get(FacilityGroup.LIGHT)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.BELL)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(4);
    }

    @Test
    void helpGroupCountsPoliceStoreAndGuardHouse() {
        Counts c = count(List.of(
                at("police", 1, 10, 1),
                at("store", 2, 20, 1),
                at("guard_house", 3, 30, 1),
                at("toilet", 4, 5, 1)));     // 화장실은 1차 설명에 세지 않음

        assertThat(c.get(FacilityGroup.HELP)).isEqualTo(3);
        assertThat(c.passed()).hasSize(3);
    }

    @Test
    void differentKindsAtSamePointAreAllCounted() {
        Counts c = count(List.of(at("cctv", 1, 10, 1), at("bell", 2, 10, 1), at("store", 3, 10, 1)));

        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.BELL)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.HELP)).isEqualTo(1);
    }

    @Test
    void eachLineCountedSeparatelyInOrder() {
        List<double[]> north = List.of(new double[] {126.93000, LAT + 10 * M11}, new double[] {126.93220, LAT + 10 * M11});
        List<Poi> pois = List.of(at("light", 1, 105, 1)); // 북쪽 길에서 약 6m

        List<Counts> counts = new FacilityCounter(props()).countAlong(List.of(LINE, north), pois);

        assertThat(counts.get(0).get(FacilityGroup.LIGHT)).isZero();
        assertThat(counts.get(1).get(FacilityGroup.LIGHT)).isEqualTo(1);
    }
}
