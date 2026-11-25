package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.UserPreferences;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.Criterion;

/** 사람마다 정한 선호(user_profile)를 모드 기준 위에 덮기 (2026-11-09) */
@DisplayName("사람별 선호 덮기")
class PersonalPreferencesTests {

    static RouteResult route(RoadGraph g, String mode, String guest, UserPreferences prefs) {
        RouteService s = TestRoutes.service(new InMemoryRoadGraphSource(g), TestRoutes.pois(List.of()), at(12), props(),
                id -> "guest-1".equals(id) ? prefs : UserPreferences.NONE, null);
        return s.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"), "auto", mode,
                guest));
    }

    /** 안내하는 경로가 계단 없이 북쪽 길로 도는지 */
    static boolean avoidsStairs(RouteResult r) {
        return safe(r).stairs().isEmpty() && safe(r).distanceM() > 205;
    }

    @Test
    @DisplayName("본인의 계단 설정이 모드 기준을 바꾸고 본인 요청에만 쓰인다")
    void stairsSettingChangesTheRoute() {
        // 지름길 S–M이 계단, 북쪽 길은 약 1.1배
        RoadGraph stairs = diamond(0.0004, STAIRS);

        // 어른은 원래 계단으로 가지만 "몇 칸은 괜찮음(1)"이면 노인처럼 조건부로 피하고, "못 감(0)"이면 계단 길을 뺀다
        assertThat(avoidsStairs(route(stairs, "adult", "guest-1", UserPreferences.NONE))).isFalse();
        assertThat(avoidsStairs(route(stairs, "adult", "guest-1", new UserPreferences(1, null, null)))).isTrue();
        RouteResult none = route(stairs, "adult", "guest-1", new UserPreferences(0, null, null));
        assertThat(avoidsStairs(none)).isTrue();
        assertThat(none.explanation().summary()).isEqualTo("계단 없는 길 중 가장 짧은 길입니다");

        // 노인은 원래 계단을 피하지만 "괜찮음(2)"이면 짧은 계단 길로
        assertThat(avoidsStairs(route(stairs, "senior", "guest-1", UserPreferences.NONE))).isTrue();
        assertThat(avoidsStairs(route(stairs, "senior", "guest-1", new UserPreferences(2, null, null)))).isFalse();

        // 다른 사람(번호 없음·다른 번호)은 모드 기준
        assertThat(avoidsStairs(route(stairs, "senior", null, new UserPreferences(2, null, null)))).isTrue();
        assertThat(avoidsStairs(route(stairs, "senior", "guest-2", new UserPreferences(2, null, null)))).isTrue();
    }

    @Test
    @DisplayName("계단을 못 가도 도착이 계단 뒤면 길 끝까지 안내하고 확인 안 된 거리를 알린다")
    void stairFreePersonIsToldWhereTheWayIsNotConfirmed() {
        // stairs_ok 0. 알림은 "계단·등산로 없이"(등산로를 넣으면 "계단 없이") (RouteService.attempt)
        RouteService s = TestRoutes.service(new InMemoryRoadGraphSource(SnapTests.pocket(20)), TestRoutes.pois(List.of()),
                at(12), props(), id -> new UserPreferences(0, null, null), null);
        double[] d = north(SnapTests.B, 20);
        for (Boolean allowTrail : new Boolean[] {null, true}) {
            RouteResult r = s.route(new RouteRequest(new Place(SnapTests.A[1], SnapTests.A[0], null), new Place(d[1], d[0], null),
                    "auto", "adult", "guest-1", null, null, allowTrail));
            assertThat(r.explanation().notes()).contains(RouteResult.Note.warn("snap", "도착점 근처 약 20m는 %s 이어진 길이 확인되지 않았습니다"
                    .formatted(allowTrail == null ? "계단·등산로 없이" : "계단 없이")));
        }
    }

    @Test
    @DisplayName("본인이 정한 우회 한도가 모드 한도 대신 쓰인다")
    void detourLimitIsTheirs() {
        // 북쪽 길 약 1.9배: 노인 한도 1.2배면 계단으로, 본인이 2.0배까지 괜찮다면 계단을 피해 돈다
        RoadGraph far = diamond(0.0015, STAIRS);
        assertThat(avoidsStairs(route(far, "senior", "guest-1", UserPreferences.NONE))).isFalse();
        assertThat(avoidsStairs(route(far, "senior", "guest-1", new UserPreferences(null, 2.0, null)))).isTrue();
    }

    static final RouteProperties.Profile SENIOR = props().profile("senior");

    @Test
    @DisplayName("사람이 정한 우회 한도는 1.0~2.0으로 자른다")
    void detourLimitIsClampedBetween1And2() {
        // Profile.personalized, 2026-11-09
        assertThat(SENIOR.personalized(new UserPreferences(null, 3.0, null)).maxDetour()).isEqualTo(2.0);
        assertThat(SENIOR.personalized(new UserPreferences(null, 0.5, null)).maxDetour()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("쉬는 간격의 절반을 쉼 곳 반경으로 쓰고 쉼 곳 기준이 있을 때만 바꾼다")
    void restIntervalHalvesIntoRestRadiusWhereRestIsACriterion() {
        // 쉬는 간격 300m → 쉼 곳 반경 150m (쉼 곳 기준을 쓰는 노인 낮만)
        RouteProperties.Profile rest = SENIOR.personalized(new UserPreferences(null, null, 300));
        assertThat(rest.day().restM()).isEqualTo(150);
        assertThat(rest.night().weights()).doesNotContainKey(Criterion.REST);
        assertThat(rest.night().restM()).isEqualTo(SENIOR.night().restM());
    }

    @Test
    @DisplayName("휠체어 모드는 사람 설정으로 계단 제외를 풀지 않는다")
    void wheelchairKeepsStairsExcludedWhateverTheSetting() {
        // Profile.personalized
        RouteProperties.Profile wheel = props().profile("wheelchair");
        assertThat(wheel.personalized(new UserPreferences(2, null, null)).exclude()).contains(Penalty.STAIRS);
    }

    @Test
    @DisplayName("빈 선호면 모드 기준을 그대로 쓴다")
    void emptyPreferencesKeepTheModeProfile() {
        assertThat(SENIOR.personalized(UserPreferences.NONE)).isSameAs(SENIOR);
    }
}
