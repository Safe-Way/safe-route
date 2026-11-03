package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.ingest.FacilityTable;

import com.saferoute.route.engine.facility.Facility;
import com.saferoute.route.engine.facility.FacilityCounter.Counts;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.facility.FacilityGroup;

/** 경로 주변 시설 세기 (결합 반경 30m·50m, 10m 안 중복 하나로) */
class FacilityCounterTests {

    /** 동서로 약 200m 뻗은 길 */
    static final List<double[]> LINE = List.of(S_P, T_P);
    /** 위도 0.0001도 ≈ 11.1m */
    static final double M11 = 0.0001;

    static Counts count(List<Facility> facilities) {
        return counter(facilities).countAlong(List.of(LINE)).get(0);
    }

    static FacilityCounter counter(List<Facility> facilities) {
        return new FacilityCounter(RouteServiceTests.facilities(facilities), props());
    }

    static Facility at(FacilityTable t, long id, double northM, int count) {
        return new Facility(t, id, 126.9311, LAT + northM / 111_195.0, count);
    }

    @Test
    void radiusPerGroup() {
        Counts c = count(List.of(
                at(FacilityTable.SECURITY_LIGHT, 1, 28, 1),  // 조명 30m 안
                at(FacilityTable.SECURITY_LIGHT, 2, 32, 1),  // 조명 30m 밖
                at(FacilityTable.EMERGENCY_BELL, 3, 48, 1),  // 50m 안
                at(FacilityTable.EMERGENCY_BELL, 4, 52, 1),  // 50m 밖
                at(FacilityTable.CCTV, 5, -45, 4)));         // 남쪽 45m, 카메라 4대

        assertThat(c.get(FacilityGroup.LIGHT)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.BELL)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(4);
    }

    @Test
    void helpGroupCountsPoliceStoreAndChildSafetyHouse() {
        Counts c = count(List.of(
                at(FacilityTable.POLICE, 1, 10, 1),
                at(FacilityTable.CONVENIENCE_STORE, 2, 20, 1),
                at(FacilityTable.CHILD_SAFETY_HOUSE, 3, 30, 1),
                at(FacilityTable.HEAT_SHELTER, 4, 5, 1)));   // 쉼터는 1차 설명에 세지 않음

        assertThat(c.get(FacilityGroup.HELP)).isEqualTo(3);
        assertThat(c.passed()).hasSize(3);
    }

    @Test
    void duplicatesWithinTenMetresKeepLargerCount() {
        Counts c = count(List.of(
                at(FacilityTable.CCTV, 1, 20, 2),
                at(FacilityTable.CCTV, 2, 25, 5),            // 5m 옆, 카메라 더 많음 → 이걸 남김
                at(FacilityTable.CCTV, 3, 40, 1)));          // 15m 떨어짐 → 다른 CCTV

        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(6);
        assertThat(c.passed()).extracting(Facility::id).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void differentKindsAtSamePointAreNotMerged() {
        Counts c = count(List.of(
                at(FacilityTable.CCTV, 1, 10, 1),
                at(FacilityTable.EMERGENCY_BELL, 2, 10, 1),
                at(FacilityTable.CONVENIENCE_STORE, 3, 10, 1)));

        assertThat(c.get(FacilityGroup.CCTV)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.BELL)).isEqualTo(1);
        assertThat(c.get(FacilityGroup.HELP)).isEqualTo(1);
    }

    @Test
    void eachLineCountedSeparatelyInOrder() {
        List<double[]> north = List.of(new double[] {126.93000, LAT + 10 * M11}, new double[] {126.93220, LAT + 10 * M11});
        List<Facility> facilities = List.of(at(FacilityTable.SECURITY_LIGHT, 1, 105, 1)); // 북쪽 길에서 약 6m

        List<Counts> both = counter(facilities).countAlong(List.of(LINE, north));

        assertThat(both.get(0).get(FacilityGroup.LIGHT)).isZero();
        assertThat(both.get(1).get(FacilityGroup.LIGHT)).isEqualTo(1);
    }

    @Test
    void noFacilitiesGivesZeros() {
        Counts c = count(List.of());

        assertThat(FacilityGroup.keyed(c.byGroup())).isEqualTo(Map.of("light", 0, "cctv", 0, "bell", 0, "help", 0));
    }

    @Test
    void minusIsSafeMinusShortestPerGroup() {
        Counts a = new Counts(Map.of(FacilityGroup.CCTV, 5, FacilityGroup.LIGHT, 2), List.of());
        Counts b = new Counts(Map.of(FacilityGroup.CCTV, 3, FacilityGroup.BELL, 1), List.of());

        assertThat(a.minus(b)).containsEntry(FacilityGroup.CCTV, 2).containsEntry(FacilityGroup.LIGHT, 2)
                .containsEntry(FacilityGroup.BELL, -1).containsEntry(FacilityGroup.HELP, 0);
    }

    @Test
    void groupsCoverTheRightTables() {
        assertThat(FacilityGroup.of(FacilityTable.STREETLIGHT)).isEqualTo(FacilityGroup.LIGHT);
        assertThat(FacilityGroup.of(FacilityTable.POLICE)).isEqualTo(FacilityGroup.HELP);
        assertThat(FacilityGroup.of(FacilityTable.PUBLIC_TOILET)).isNull();
        assertThat(FacilityGroup.allTables()).hasSize(7);
    }
}
