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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;

import com.saferoute.common.ErrorResponse;

/**
 * DB 대신 가짜(mock) 저장소로 가까운 곳·지도 시설 규칙을 확인.
 * <p>무엇을: "가까운 화장실·경찰서 찾기"(GET /api/places/nearby)와 지도 범위 안 시설(GET /api/pois) 창구.
 * <p>어떻게: FacilityRepository를 Mockito mock으로 바꿔 DB 없이 돌린다. 고르는 규칙(pick)은 목록을 직접 넣어 보고,
 * 창구 메서드는 잘못된 값이면 저장소를 아예 부르지 않는지, 맞으면 어떤 값으로 부르는지 verify로 본다.
 * <p>왜: 이게 깨지면 닫힌 화장실로 안내하거나, 휠체어 이용자에게 못 쓰는 화장실을 보여 주거나,
 * 너무 넓은 지도 범위로 DB를 무겁게 만든다.
 */
@DisplayName("가까운 곳·지도 시설 창구")
class FacilityControllerTests {

    private final FacilityRepository repo = mock(FacilityRepository.class);
    private final FacilityController controller = new FacilityController(repo);

    /** 시험용 화장실 하나. 거리와 지금 상태(이용 가능·닫힘·확인 안 됨, null = 시간과 상관없음)만 바꿔 쓴다 */
    private static Facility toilet(long id, int distanceM, String status) {
        return new Facility(id, "toilet", "공중화장실", "화장실 " + id, null, 35.14, 126.92, distanceM, status, null,
                null, 1, List.of("senior"), "공중화장실");
    }

    @Test
    @DisplayName("지금 연 곳을 먼저, 최대 5곳까지 고른다")
    void picksOpenPlacesFirstUpToFive() {
        // 가장 가까운 1번은 닫혔고 3번은 시간 모름이다. 가깝다는 이유로 닫힌 곳을 고르면 안 된다.
        // 연 곳이 6곳(2,4,5,6,7,8)이라 5곳에서 잘리는지도 같이 본다
        List<Facility> byDistance = List.of(toilet(1, 50, "닫힘"), toilet(2, 80, "이용 가능"),
                toilet(3, 90, "확인 안 됨"), toilet(4, 100, "이용 가능"), toilet(5, 120, "이용 가능"),
                toilet(6, 130, "이용 가능"), toilet(7, 140, "이용 가능"), toilet(8, 150, "이용 가능"));

        NearbyResponse r = FacilityController.pick(byDistance);

        assertThat(r.places()).extracting(Facility::id).containsExactly(2L, 4L, 5L, 6L, 7L);
        // 연 곳이 있으면 닫힌 곳·안내 문구는 주지 않는다
        assertThat(r.closestClosed()).isNull();
        assertThat(r.message()).isNull();
    }

    @Test
    @DisplayName("운영 시간이 없는 곳은 연 곳으로 친다")
    void timeFreePlacesCountAsOpen() {
        // 어린이보호구역처럼 운영 시간 개념이 없는 곳은 상태가 null로 온다. null을 "닫힘"으로 보면 늘 빠져 버린다
        Facility zone = new Facility(9, "school_zone", "초등학교", "동산초", null, 35.14, 126.92, 200, null, null,
                null, 1, List.of("child"), "초등학교 앞 어린이보호구역");

        assertThat(FacilityController.pick(List.of(zone)).places()).containsExactly(zone);
    }

    @Test
    @DisplayName("연 곳이 모자라면 시간 모름·가장 가까운 닫힌 곳으로 채운다")
    void fallsBackToUnknownWithClosestClosed() {
        // 연 곳이 하나도 없을 때: "확인 안 됨"(2번)을 보여 주되, 가기 전에 확인하라는 문구와 가장 가까운 닫힌 곳(1번)을 같이 준다.
        // "운영 기간 아님"(3번)도 닫힌 것으로 치지만, 더 가까운 1번이 있어서 1번이 뽑힌다
        NearbyResponse r = FacilityController.pick(List.of(toilet(1, 50, "닫힘"), toilet(2, 80, "확인 안 됨"),
                toilet(3, 90, "운영 기간 아님")));

        assertThat(r.places()).extracting(Facility::id).containsExactly(2L);
        assertThat(r.closestClosed().id()).isEqualTo(1L);
        assertThat(r.message()).contains("확인이 안 된");
    }

    @Test
    @DisplayName("연 곳이 없으면 가장 가까운 닫힌 곳과 안내 문구를 준다")
    void noOpenPlaceGivesClosestClosedAndMessage() {
        // 다 닫혔으면 빈 목록 대신 "가장 가까운 닫힌 곳"이라도 알려 줘야 앱이 아무것도 없다고 오해하지 않는다
        NearbyResponse r = FacilityController.pick(List.of(toilet(1, 300, "닫힘"), toilet(2, 400, "닫힘")));

        assertThat(r.places()).isEmpty();
        assertThat(r.closestClosed().id()).isEqualTo(1L);
        assertThat(r.message()).isEqualTo("가까운 곳에 열린 곳이 없어요");
    }

    @Test
    @DisplayName("반경 안에 아무것도 없으면 빈 목록")
    void nothingWithinRadius() {
        // 1km 안에 아예 없을 때와 다 닫혔을 때는 문구가 달라야 한다 (바로 위 시험과 짝)
        NearbyResponse r = FacilityController.pick(List.of());

        assertThat(r.places()).isEmpty();
        assertThat(r.closestClosed()).isNull();
        assertThat(r.message()).isEqualTo("1km 안에 이 시설이 없어요");
    }

    @Test
    @DisplayName("휠체어 모드는 장애인 화장실만 찾는다")
    void wheelchairModeAsksOnlyAccessibleToilets() {
        // 휠체어 모드라도 "휠체어 가능만" 거르는 종류는 화장실·지하철역뿐이다. 경찰서까지 거르면 대부분 빠져 버린다.
        // 좌표는 시연 지역(아시아문화전당) 근처, 반경은 늘 1km
        when(repo.near(anyDouble(), anyDouble(), anyString(), anyBoolean(), anyInt())).thenReturn(List.of());

        controller.nearby(35.1473, 126.9214, "toilet", "wheelchair");
        controller.nearby(35.1473, 126.9214, "police", "wheelchair");

        verify(repo).near(35.1473, 126.9214, "toilet", true, 1000);
        verify(repo).near(35.1473, 126.9214, "police", false, 1000);
    }

    @Test
    @DisplayName("가까운 곳: 잘못된 값은 400")
    void nearbyRejectsBadParams() {
        // 차례로: 위도 없음, 가까운 곳 목록에 없는 종류(light = 가로등), 없는 모드(disabled)
        assertThat(code(controller.nearby(null, 126.92, "toilet", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.nearby(35.14, 126.92, "light", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.nearby(35.14, 126.92, "toilet", "disabled"))).isEqualTo("BAD_PARAM");
        // 값이 틀리면 DB까지 가지 않아야 한다
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("가까운 곳: DB 오류면 503")
    void nearbyDbFailureGives503() {
        // DB가 끊겼을 때 500(서버 버그)이 아니라 503(잠시 뒤 다시)으로 답해야 앱이 "다시 시도" 안내를 띄운다
        when(repo.near(anyDouble(), anyDouble(), anyString(), anyBoolean(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("접속 끊김"));

        ResponseEntity<?> res = controller.nearby(35.14, 126.92, "toilet", "senior");

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(code(res)).isEqualTo("DB_UNAVAILABLE");
    }

    @Test
    @DisplayName("지도 시설: 지도 범위와 종류로 읽는다")
    void poisReadsBboxAndKinds() {
        // kinds 는 앞뒤 빈칸·끝의 쉼표가 있어도 cctv, shelter 두 개로 읽어야 한다.
        // limit 을 안 주면 500개인데 저장소에는 501개를 달라고 한다 (하나 더 와야 "잘렸다"를 알 수 있음)
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(Set.of("cctv", "shelter")), anyInt()))
                .thenReturn(List.of(toilet(1, 0, null)));

        ResponseEntity<?> res = controller.pois("126.91,35.14,126.93,35.15", " cctv , shelter ,", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(repo).within(126.91, 35.14, 126.93, 35.15, Set.of("cctv", "shelter"), 501);
        assertThat(((PoisResponse) res.getBody()).truncated()).isFalse();
    }

    @Test
    @DisplayName("엘리베이터를 가까운 곳·지도 종류로 쓸 수 있다")
    void elevatorIsAKindForNearbyAndMap() {
        // elevator 는 표에 있는 종류가 아니라 "엘리베이터 육교 + 엘리베이터 출구"를 묶은 이름이라 따로 허용해야 한다.
        // 휠체어 모드여도 elevator 는 거르는 종류가 아니라 wheelchairOnly = false 로 간다
        when(repo.near(anyDouble(), anyDouble(), anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(Set.of("elevator")), anyInt()))
                .thenReturn(List.of());

        assertThat(controller.nearby(35.14, 126.92, "elevator", "wheelchair").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.pois("126.91,35.14,126.93,35.15", "elevator", null).getStatusCode().value())
                .isEqualTo(200);
        verify(repo).near(35.14, 126.92, "elevator", false, 1000);
    }

    @Test
    @DisplayName("지도 시설: 지하철 출입구도 받는다")
    void poisAcceptsSubwayEntrances() {
        // 지하철 출입구(subway_entrance)는 지도 종류에만 있고 가까운 곳 종류에는 없다. 지도에서 빠지지 않는지 본다
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(Set.of("subway", "subway_entrance")),
                anyInt())).thenReturn(List.of());

        ResponseEntity<?> res = controller.pois("126.91,35.14,126.93,35.15", "subway,subway_entrance", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("지도 시설: 개수 한도에서 잘리면 잘렸다고 표시한다")
    void poisMarksTruncated() {
        // limit 2 인데 저장소가 3개를 주면(= 2 + 1개 더) 2개만 내보내고 잘렸다고 표시해야 앱이 "더 확대하세요"를 띄운다
        when(repo.within(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(Set.of("cctv")), anyInt()))
                .thenReturn(List.of(toilet(1, 0, null), toilet(2, 0, null), toilet(3, 0, null)));

        PoisResponse r = (PoisResponse) controller.pois("126.91,35.14,126.93,35.15", "cctv", 2).getBody();

        assertThat(r.pois()).hasSize(2);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    @DisplayName("지도 시설: 잘못된 값은 400")
    void poisRejectsBadParams() {
        // 차례로: bbox 없음, 서쪽이 동쪽보다 큼, 숫자 3개뿐, 한 변 0.5도(약 50km)라 너무 넓음(한도 0.2도),
        // 지도에 못 띄우는 종류(light, 8만 개가 넘음), 쉼표·빈칸만 있어 종류가 0개
        assertThat(code(controller.pois(null, "cctv", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.pois("126.93,35.14,126.91,35.15", "cctv", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.pois("126.91,35.14,126.93", "cctv", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.pois("126.0,35.0,126.5,35.1", "cctv", null))).isEqualTo("BBOX_TOO_LARGE");
        assertThat(code(controller.pois("126.91,35.14,126.93,35.15", "light", null))).isEqualTo("BAD_PARAM");
        assertThat(code(controller.pois("126.91,35.14,126.93,35.15", " , ", null))).isEqualTo("BAD_PARAM");
        verifyNoInteractions(repo);
    }

    /** 오류 답의 code (BAD_PARAM, BBOX_TOO_LARGE, DB_UNAVAILABLE) */
    private static String code(ResponseEntity<?> res) {
        return ((ErrorResponse) res.getBody()).code();
    }
}
