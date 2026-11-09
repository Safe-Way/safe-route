package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.data.UserPreferences;
import com.saferoute.route.engine.explain.RouteResultAssembler;
import com.saferoute.route.engine.facility.FacilityCounter;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadGraph;
import com.saferoute.route.engine.score.Criterion;
import com.saferoute.route.engine.score.RouteScorer;
import com.saferoute.situation.NightJudge;

/** 사람마다 정한 선호(user_profile)를 모드 기준 위에 덮기 (2026-11-09) */
class PersonalPreferencesTests {

    static RouteResult route(RoadGraph g, String mode, String guest, UserPreferences prefs) {
        RouteProperties p = props();
        var now = at(12);
        RouteService s = new RouteService(new InMemoryRoadGraphSource(g), TestRoutes.pois(List.of()), new NightJudge(),
                new RouteScorer(p), new RouteResultAssembler(new FacilityCounter(p), p), p,
                Clock.fixed(now.toInstant(), now.getZone()), id -> "guest-1".equals(id) ? prefs : UserPreferences.NONE);
        return s.route(new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"), "auto", mode,
                guest));
    }

    static boolean north(RouteResult r) {
        return r.routes().get(r.routes().size() - 1).stairs().isEmpty()
                && r.routes().get(r.routes().size() - 1).distanceM() > 205;
    }

    @Test
    void stairsSettingChangesTheRoute() {
        // 지름길 S–M이 계단, 북쪽 길은 약 1.1배
        RoadGraph stairs = RouteFinderTests.diamond(0.0004, RouteFinderTests.STAIRS);

        // 어른은 원래 계단으로 가지만 "몇 칸은 괜찮음(1)"이면 노인처럼 조건부로 피하고, "못 감(0)"이면 계단 길을 뺀다
        assertThat(north(route(stairs, "adult", "guest-1", UserPreferences.NONE))).isFalse();
        assertThat(north(route(stairs, "adult", "guest-1", new UserPreferences(1, null, null)))).isTrue();
        RouteResult none = route(stairs, "adult", "guest-1", new UserPreferences(0, null, null));
        assertThat(north(none)).isTrue();
        assertThat(none.explanation().summary()).isEqualTo("계단 없는 길 중 가장 짧은 길입니다");

        // 노인은 원래 계단을 피하지만 "괜찮음(2)"이면 짧은 계단 길로
        assertThat(north(route(stairs, "senior", "guest-1", UserPreferences.NONE))).isTrue();
        assertThat(north(route(stairs, "senior", "guest-1", new UserPreferences(2, null, null)))).isFalse();

        // 다른 사람(번호 없음·다른 번호)은 모드 기준
        assertThat(north(route(stairs, "senior", null, new UserPreferences(2, null, null)))).isTrue();
        assertThat(north(route(stairs, "senior", "guest-2", new UserPreferences(2, null, null)))).isTrue();
    }

    @Test
    void detourLimitIsTheirs() {
        // 북쪽 길 약 1.9배: 노인 한도 1.2배면 계단으로, 본인이 2.0배까지 괜찮다면 계단을 피해 돈다
        RoadGraph far = RouteFinderTests.diamond(0.0015, RouteFinderTests.STAIRS);
        assertThat(north(route(far, "senior", "guest-1", UserPreferences.NONE))).isFalse();
        assertThat(north(route(far, "senior", "guest-1", new UserPreferences(null, 2.0, null)))).isTrue();
    }

    @Test
    void profileMergeRules() {
        RouteProperties p = props();
        RouteProperties.Profile senior = p.profile("senior");
        RouteProperties.Profile wheel = p.profile("wheelchair");

        // 우회 한도는 1.0~2.0으로 자른다
        assertThat(senior.personalized(new UserPreferences(null, 3.0, null)).maxDetour()).isEqualTo(2.0);
        assertThat(senior.personalized(new UserPreferences(null, 0.5, null)).maxDetour()).isEqualTo(1.0);
        // 쉬는 간격 300m → 쉼 곳 반경 150m (쉼 곳 기준을 쓰는 노인 낮만)
        RouteProperties.Profile rest = senior.personalized(new UserPreferences(null, null, 300));
        assertThat(rest.day().restM()).isEqualTo(150);
        assertThat(rest.night().weights()).doesNotContainKey(Criterion.REST);
        assertThat(rest.night().restM()).isEqualTo(senior.night().restM());
        // 휠체어는 "계단 괜찮음"이어도 계단 제외를 풀지 않는다
        assertThat(wheel.personalized(new UserPreferences(2, null, null)).exclude()).contains(Penalty.STAIRS);
        // 비어 있으면 그대로
        assertThat(senior.personalized(UserPreferences.NONE)).isSameAs(senior);
    }
}
