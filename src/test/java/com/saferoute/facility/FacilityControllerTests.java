package com.saferoute.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;

import com.saferoute.common.ErrorResponse;

/** DB 대신 가짜(mock) 저장소로 가까운 곳·지도 시설 규칙을 확인 */
class FacilityControllerTests {

    private final FacilityRepository repo = mock(FacilityRepository.class);
    private final FacilityController controller = new FacilityController(repo);

    private static Facility toilet(long id, int distanceM, String status) {
        return new Facility(id, "toilet", "공중화장실", "화장실 " + id, null, 35.14, 126.92, distanceM, status, null,
                null, 1, List.of("senior"), "공중화장실");
    }

    @Test
    void picksOpenPlacesFirstUpToFive() {
        List<Facility> byDistance = List.of(toilet(1, 50, "닫힘"), toilet(2, 80, "이용 가능"),
                toilet(3, 90, "확인 안 됨"), toilet(4, 100, "이용 가능"), toilet(5, 120, "이용 가능"),
                toilet(6, 130, "이용 가능"), toilet(7, 140, "이용 가능"), toilet(8, 150, "이용 가능"));

        NearbyResponse r = FacilityController.pick(byDistance);

        assertThat(r.places()).extracting(Facility::id).containsExactly(2L, 4L, 5L, 6L, 7L);
        assertThat(r.closestClosed()).isNull();
        assertThat(r.message()).isNull();
    }

    @Test
    void timeFreePlacesCountAsOpen() {
        Facility zone = new Facility(9, "school_zone", "초등학교", "동산초", null, 35.14, 126.92, 200, null, null,
                null, 1, List.of("child"), "초등학교 앞 어린이보호구역");

        assertThat(FacilityController.pick(List.of(zone)).places()).containsExactly(zone);
    }

    @Test
    void fallsBackToUnknownWithClosestClosed() {
        NearbyResponse r = FacilityController.pick(List.of(toilet(1, 50, "닫힘"), toilet(2, 80, "확인 안 됨"),
                toilet(3, 90, "운영 기간 아님")));

        assertThat(r.places()).extracting(Facility::id).containsExactly(2L);
        assertThat(r.closestClosed().id()).isEqualTo(1L);
        assertThat(r.message()).contains("확인이 안 된");
    }

    @Test
    void noOpenPlaceGivesClosestClosedAndMessage() {
        NearbyResponse r = FacilityController.pick(List.of(toilet(1, 300, "닫힘"), toilet(2, 400, "닫힘")));

        assertThat(r.places()).isEmpty();
        assertThat(r.closestClosed().id()).isEqualTo(1L);
        assertThat(r.message()).isEqualTo("가까운 곳에 열린 곳이 없어요");
    }

    @Test
    void nothingWithinRadius() {
        NearbyResponse r = FacilityController.pick(List.of());

        assertThat(r.places()).isEmpty();
        assertThat(r.closestClosed()).isNull();
        assertThat(r.message()).isEqualTo("1km 안에 이 시설이 없어요");
    }

    @Test
    void wheelchairModeAsksOnlyAccessibleToilets() {
        when(repo.near(anyDouble(), anyDouble(), anyString(), anyBoolean(), anyInt())).thenReturn(List.of());

        controller.nearby(35.1473, 126.9214, "toilet", "wheelchair");
        controller.nearby(35.1473, 126.9214, "police", "wheelchair");

        verify(repo).near(35.1473, 126.9214, "toilet", true, 1000);
        verify(repo).near(35.1473, 126.9214, "police", false, 1000);
    }

    @Test
    void nearbyRejectsBadParams() {
        assertThat(code(controller.nearby(null, 126.92, "toilet", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.nearby(35.14, 126.92, "light", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.nearby(35.14, 126.92, "toilet", "disabled"))).isEqualTo("BAD_PARAM");
        verifyNoInteractions(repo);
    }

    @Test
    void nearbyDbFailureGives503() {
        when(repo.near(anyDouble(), anyDouble(), anyString(), anyBoolean(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("접속 끊김"));

        ResponseEntity<?> res = controller.nearby(35.14, 126.92, "toilet", "senior");

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(code(res)).isEqualTo("DB_UNAVAILABLE");
    }

    @Test
    void poisReadsBboxAndKinds() {
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(Set.of("cctv", "shelter")), anyInt()))
                .thenReturn(List.of(toilet(1, 0, null)));

        ResponseEntity<?> res = controller.pois("126.91,35.14,126.93,35.15", " cctv , shelter ,", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(repo).within(126.91, 35.14, 126.93, 35.15, Set.of("cctv", "shelter"), 501);
        assertThat(((PoisResponse) res.getBody()).truncated()).isFalse();
    }

    @Test
    void elevatorIsAKindForNearbyAndMap() {
        when(repo.near(anyDouble(), anyDouble(), anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(Set.of("elevator")), anyInt()))
                .thenReturn(List.of());

        assertThat(controller.nearby(35.14, 126.92, "elevator", "wheelchair").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.pois("126.91,35.14,126.93,35.15", "elevator", null).getStatusCode().value())
                .isEqualTo(200);
        verify(repo).near(35.14, 126.92, "elevator", false, 1000);
    }

    @Test
    void poisAcceptsSubwayEntrances() {
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(Set.of("subway", "subway_entrance")),
                anyInt())).thenReturn(List.of());

        ResponseEntity<?> res = controller.pois("126.91,35.14,126.93,35.15", "subway,subway_entrance", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void poisMarksTruncated() {
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(Set.of("cctv")), anyInt()))
                .thenReturn(List.of(toilet(1, 0, null), toilet(2, 0, null), toilet(3, 0, null)));

        PoisResponse r = (PoisResponse) controller.pois("126.91,35.14,126.93,35.15", "cctv", 2).getBody();

        assertThat(r.pois()).hasSize(2);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void poisRejectsBadParams() {
        assertThat(code(controller.pois(null, "cctv", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.pois("126.93,35.14,126.91,35.15", "cctv", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.pois("126.91,35.14,126.93", "cctv", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.pois("126.0,35.0,126.5,35.1", "cctv", null))).isEqualTo("BBOX_TOO_LARGE");
        assertThat(code(controller.pois("126.91,35.14,126.93,35.15", "light", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.pois("126.91,35.14,126.93,35.15", " , ", null))).isEqualTo("BAD_PARAM");
        verifyNoInteractions(repo);
    }

    private static String code(ResponseEntity<?> res) {
        return ((ErrorResponse) res.getBody()).code();
    }
}
