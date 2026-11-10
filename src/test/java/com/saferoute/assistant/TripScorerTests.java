package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.saferoute.bus.BusArrival;
import com.saferoute.route.RouteProperties;
import com.saferoute.route.api.RouteResult;

import com.saferoute.transit.TransitResult;

/** 추천 규칙. 비서 점검 1·30번 (비 오는 아침 휠체어에게 22분 걷기를 고르던 문제) */
class TripScorerTests {

    final TripScorer scorer = new TripScorer();

    static Trip walk(int min, int m) {
        return new Trip("walk", min, m, 0, 0, "걸어서 " + min + "분", Map.of(), "safe", Map.of(), List.of(), false, null);
    }

    static Trip bus(int min, int walkM, boolean lowFloor) {
        return new Trip("transit", min, walkM, 0, 0, "버스 30번, " + min + "분", Map.of(), null, Map.of(), List.of(),
                lowFloor, "30번 5분 뒤");
    }

    static TripScorer.Context rain(String mode, String habit) {
        return new TripScorer.Context(mode, true, false, false, false, false, false, null, habit, null);
    }

    @Test
    void rainyWheelchairPicksLowFloorBusOverLongerRoll() {
        TripScorer.Scored s = scorer.score(List.of(walk(22, 1300), bus(36, 300, true)), rain("wheelchair", "transit"));
        assertThat(s.best().type()).isEqualTo("transit");
        assertThat(s.because()).contains("지난번 비 올 때도 대중교통을 타셨어요", "곧 오는 저상버스가 있어요");
    }

    @Test
    void sameRainAdultStillWalksWhenWalkIsMuchShorter() {
        // 어른은 1,300m를 16분에 걷는다 (분당 80m). 비 속 16분을 한 번 더 세도 32점 < 버스 36분 + 걷기 4분
        TripScorer.Scored s = scorer.score(List.of(walk(16, 1300), bus(36, 300, true)), rain("adult", null));
        assertThat(s.best().type()).isEqualTo("walk");
    }

    @Test
    void rainCountsTheRealWalkingTimeForEachMode() {
        // 같은 1,300m라도 휠체어는 26분(분당 50m) 밖에 있다. 예전엔 모두 분당 80m(16분)로 셌다
        assertThat(TripScorer.outsideMinutes(walk(26, 1300), false)).isEqualTo(26);
        // 대중교통은 걷는 구간 시간을 모르면 걷는 거리를 분당 80m로
        assertThat(TripScorer.outsideMinutes(bus(36, 400, false), false)).isEqualTo(5);
    }

    @Test
    void rainCountsOnlyTheWalkOutsideTheRoof() {
        // 엔진이 지하상가 등 지붕 아래로 1,000m 중 600m를 안내하면 젖는 시간은 20분 × 0.4 = 8분
        RouteResult.Route roofed = new RouteResult.Route("safe", 1000, 20, List.of(), Map.of(), Map.of("roof", 600),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        RouteResult.Route open = new RouteResult.Route("safe", 1000, 20, List.of(), Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(TripScorer.outsideMinutes(walkOn(roofed), true)).isEqualTo(8);
        // 비가 아니면(폭염·미세먼지 등은 지붕이 못 막음) 걷는 시간 전부, 지붕 값이 없어도 전부
        assertThat(TripScorer.outsideMinutes(walkOn(roofed), false)).isEqualTo(20);
        assertThat(TripScorer.outsideMinutes(walkOn(open), true)).isEqualTo(20);
    }

    static Trip walkOn(RouteResult.Route r) {
        return new Trip("walk", 20, 1000, 0, 0, "걸어서 20분", Map.of(), "safe", Map.of(), List.of(), false, null, null,
                List.of(), r);
    }

    @Test
    void nightTransitSaysTheWalkToTheStopIsBrightOnlyWhenItIs() {
        TripScorer.Context night = new TripScorer.Context("senior", false, false, false, false, true, false, null,
                null, null);
        TransitResult.Leg safeLeg = new TransitResult.Leg("walk", 600, 10, null, null, List.of(), null, null, true,
                List.of(), List.of(), null, List.of(), List.of());
        Trip bright = new Trip("transit", 19, 600, 0, 0, "지하철 1호선, 19분", Map.of(), null, Map.of(), List.of(),
                false, null, null, List.of(safeLeg));
        assertThat(scorer.score(List.of(bright), night).because()).contains("정류장까지도 가로등 많은 밝은 길");
        // 걷는 구간을 우리 엔진으로 못 바꿨으면(ODsay 그대로) 말하지 않는다
        assertThat(scorer.score(List.of(bus(19, 600, false)), night).because())
                .doesNotContain("정류장까지도 가로등 많은 밝은 길");
    }

    @Test
    void seniorIgnoresLowFloorAndReportsWeighMore() {
        TripScorer.Context senior = ctx("senior", false);
        TripScorer.Context adult = ctx("adult", false);
        // 어르신은 저상버스를 따지지 않는다 (일반 버스도 탐, 11-09 결정)
        assertThat(scorer.points(bus(20, 300, true), senior)).isEqualTo(scorer.points(bus(20, 300, false), senior));
        // 불편 신고 한 건: 어른 5분, 어르신 10분
        Trip reported = new Trip("walk", 20, 1200, 0, 1, "걸어서 20분", Map.of(), "safe", Map.of(), List.of(), false,
                null);
        assertThat(scorer.points(reported, adult)).isEqualTo(25);
        assertThat(scorer.points(reported, senior)).isEqualTo(30);
    }

    static TripScorer.Context ctx(String mode, boolean night) {
        return new TripScorer.Context(mode, false, false, false, false, night, false, null, null, null);
    }

    static BusArrival arrival(int remainMin, int remainStop, boolean lowFloor) {
        return new BusArrival(1, "36", remainMin, remainStop, lowFloor, null, null);
    }

    static TransitResult.Leg walkLeg(int min) {
        return new TransitResult.Leg("walk", min * 60, min, null, null, List.of(), null, null, true, List.of(),
                List.of(), null, null, null);
    }

    static TransitResult.Leg busLeg(int odsayMin, int stations, BusArrival... buses) {
        return new TransitResult.Leg("bus", 3000, odsayMin, null, null, List.of("36"), null, stations, null, List.of(),
                List.of(), true, List.of(buses), null);
    }

    @Test
    void busTimeUsesLiveArrivalsAndWaiting() {
        // 정류장까지 4분 걷고, 36번이 6분 뒤(3정거장 전 → 정거장당 2분), 10정거장 타고, 3분 걷기
        // = 걷기 4 + 기다림 2 + 타기 20 + 걷기 3 = 29분 (ODsay 구간 시간 15분 대신 실시간)
        var tl = TripScorer.timeline(List.of(walkLeg(4), busLeg(15, 10, arrival(6, 3, false)), walkLeg(3)), "adult");
        assertThat(tl.minutes()).isEqualTo(29);
        assertThat(tl.feasible()).isTrue();
        // 정류장에 닿기 전에 지나가는 버스(2분 뒤)는 못 탐 → 기다림 모름, ODsay 시간
        var missed = TripScorer.timeline(List.of(walkLeg(4), busLeg(15, 10, arrival(2, 1, false))), "adult");
        assertThat(missed.minutes()).isEqualTo(19);
    }

    @Test
    void wheelchairTakesOnlyLowFloorBusesIncludingTransfers() {
        // 첫 버스가 일반이면 못 탐
        assertThat(TripScorer.timeline(List.of(walkLeg(4), busLeg(15, 10, arrival(6, 3, false))), "wheelchair")
                .feasible()).isFalse();
        // 첫 버스 저상(6분 뒤, 정거장당 2분 × 5 = 10분) → 갈아타는 곳에 16분+걷기 2분 = 18분 도착
        // 두 번째 노선 저상이 25분 뒤면 탐 (내 도착 2분 넘게 뒤), 19분 뒤면 놓친다고 봄
        var ok = TripScorer.timeline(List.of(walkLeg(4), busLeg(12, 5, arrival(6, 3, true)), walkLeg(2),
                busLeg(10, 4, arrival(25, 5, true))), "wheelchair");
        assertThat(ok.feasible()).isTrue();
        assertThat(ok.minutes()).isEqualTo(25 + 4 * 5);
        var tight = TripScorer.timeline(List.of(walkLeg(4), busLeg(12, 5, arrival(6, 3, true)), walkLeg(2),
                busLeg(10, 4, arrival(19, 4, true))), "wheelchair");
        assertThat(tight.feasible()).isFalse();
        // 실시간 목록이 비었거나 BIS가 막혀 저상버스인지 모르면 빼지 않음 (이유에 "확인할 수 없어요")
        assertThat(TripScorer.timeline(List.of(busLeg(10, 4)), "wheelchair").feasible()).isTrue();
        var blocked = new TransitResult.Leg("bus", 3000, 10, null, null, List.of("36"), null, 4, null, List.of(),
                List.of(), false, List.of(), null);
        assertThat(TripScorer.timeline(List.of(blocked), "wheelchair").feasible()).isTrue();
        assertThat(TripScorer.timeline(List.of(busLeg(10, 4)), "senior").feasible()).isTrue();
    }

    @Test
    void reasonsTellWhatTheBusAndDarkStretchesReallyAre() {
        // 걷는 길이 없고 일반버스뿐이면 그래도 보여 주되 저상버스가 없다고 알림
        Trip regular = transit(busLeg(10, 4, arrival(3, 2, false)));
        assertThat(scorer.score(List.of(regular), ctx("wheelchair", false)).because()).contains("곧 오는 저상버스가 없어요");
        // BIS가 막혀 모르면 확인할 수 없다고
        Trip blocked = transit(new TransitResult.Leg("bus", 3000, 10, null, null, List.of("36"), null, 4, null,
                List.of(), List.of(), false, List.of(), null));
        assertThat(scorer.score(List.of(blocked), ctx("wheelchair", false)).because())
                .contains("저상버스인지 지금 확인할 수 없어요");
        // 밤: 엔진 길로 바꾼 걷기 구간(safeWalk)이 있어도 다른 후보보다 어두운 길이 길면 밝다고 안 함
        Trip bus = transit(walkLeg(3), busLeg(10, 4));
        Trip walk = walk(20, 1500);
        var night = ctx("adult", true);
        var darker = Map.of(bus, new TripScorer.Look(13, 0, 202, true), walk, new TripScorer.Look(20, 0, 151, true));
        assertThat(scorer.reasons(bus, List.of(bus, walk), night, darker)).doesNotContain("정류장까지도 가로등 많은 밝은 길");
        var brighter = Map.of(bus, new TripScorer.Look(13, 0, 100, true), walk, new TripScorer.Look(20, 0, 151, true));
        assertThat(scorer.reasons(bus, List.of(bus, walk), night, brighter)).contains("정류장까지도 가로등 많은 밝은 길");
    }

    @Test
    void lowFloorBusTooEarlyIsNotSaidToBeComing() {
        // 첫 버스 중 저상버스가 있다고(lowFloor) 왔지만 정류장까지 4분 걷는 동안 2분 뒤 버스는 지나가 탈 수 없다
        Trip missed = new Trip("transit", 15, 300, 0, 0, "버스 36번, 15분", Map.of(), null, Map.of(), List.of(), true,
                "36번 2분 뒤", null, List.of(walkLeg(4), busLeg(10, 4, arrival(2, 1, true))));
        List<String> because = scorer.score(List.of(missed), ctx("wheelchair", false)).because();
        assertThat(because).contains("곧 오는 저상버스가 없어요").doesNotContain("곧 오는 저상버스가 있어요");
    }

    static Trip transit(TransitResult.Leg... legs) {
        return new Trip("transit", 15, 300, 0, 0, "버스 36번, 15분", Map.of(), null, Map.of(), List.of(), false, null,
                null, List.of(legs));
    }

    @Test
    void wheelchairNeverGetsARegularBusRecommended() {
        Trip regular = new Trip("transit", 15, 300, 0, 0, "버스 36번, 15분", Map.of(), null, Map.of(), List.of(), false,
                "36번 3분 뒤", null, List.of(walkLeg(2), busLeg(10, 4, arrival(3, 2, false))));
        TripScorer.Scored s = scorer.score(List.of(walk(40, 2000), regular), ctx("wheelchair", false));
        assertThat(s.best().type()).isEqualTo("walk");
        assertThat(s.others()).isEmpty();
    }

    @Test
    void engineBurdenCountsWhatTheRouteEngineCaresAbout() {
        RouteProperties props = com.saferoute.route.RouteTestProps.load();
        // 밤: 어두운 구간 400m ÷ 80 = 5분, 어르신은 두 배 10분
        RouteResult.Route night = route(2000, Map.of(), List.of(new RouteResult.Stretch(100, 500)), List.of(), 0);
        assertThat(TripScorer.burden(night, true, "adult", props.profile("adult"))).isEqualTo(5);
        assertThat(TripScorer.burden(night, true, "senior", props.profile("senior"))).isEqualTo(10);
        // 노인 낮: 쉼 곳 범위 밖 800m ÷ 80 × 2 = 20분, 계단 2곳 6분
        RouteResult.Route seniorDay = route(2000, Map.of("rest", 1200), List.of(),
                List.of(new RouteResult.Stair(10, 20, null), new RouteResult.Stair(300, 310, null)), 0);
        assertThat(TripScorer.burden(seniorDay, false, "senior", props.profile("senior"))).isEqualTo(26);
        // 어린이 낮: 보호구역 3 : 도움 1 → (3 × 1000 + 1 × 200) ÷ 4 = 800m ÷ 80 × 2 = 20분
        RouteResult.Route childDay = route(2000, Map.of("school", 1000, "help", 1800), List.of(), List.of(), 0);
        assertThat(TripScorer.burden(childDay, false, "child", props.profile("child"))).isEqualTo(20);
        // 어른 낮은 기준이 없어 0, 휠체어 비포장 120m → 6분
        assertThat(TripScorer.burden(childDay, false, "adult", props.profile("adult"))).isZero();
        RouteResult.Route rough = route(2000, Map.of(), List.of(), List.of(), 120);
        assertThat(TripScorer.burden(rough, false, "wheelchair", props.profile("wheelchair"))).isEqualTo(6);
    }

    @Test
    void scorerUsesTheEngineRoutesTheTripsCarry() {
        // 추천기·대중교통 창구가 실어 준 엔진 경로로 부담을 센다 (엔진을 다시 부르지 않음). 밤 어두운 길 400m → 5분
        RouteResult.Route darkWalk = route(1000, Map.of(), List.of(new RouteResult.Stretch(0, 400)), List.of(), 0);
        Trip walk = new Trip("walk", 13, 1000, 0, 0, "걸어서 13분", Map.of(), "safe", Map.of(), List.of(), false, null,
                null, List.of(), darkWalk);
        assertThat(scorer.look(walk, ctx("adult", true)).burden()).isEqualTo(5);
        // 대중교통은 걷는 구간마다: 160m 어두운 구간 둘 → 4분
        RouteResult.Route toStop = route(300, Map.of(), List.of(new RouteResult.Stretch(0, 160)), List.of(), 0);
        TransitResult.Leg leg = new TransitResult.Leg("walk", 300, 4, null, null, List.of(), null, null, true,
                List.of(), List.of(), null, null, null, List.of(), toStop);
        TripScorer.Look bus = scorer.look(transit(leg, busLeg(10, 4), leg), ctx("adult", true));
        assertThat(bus.burden()).isEqualTo(4);
        assertThat(bus.darkM()).isEqualTo(320);
        // 낮이면 어두운 길은 안 셈, 경로가 없으면(엔진이 못 찾음) 부담 0
        assertThat(scorer.look(walk, ctx("adult", false)).burden()).isZero();
        assertThat(scorer.look(walk(13, 1000), ctx("adult", true)).burden()).isZero();
    }

    @Test
    void engineRoutesDoNotLeakIntoTheAppResponse() throws Exception {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        RouteResult.Route r = route(300, Map.of(), List.of(), List.of(), 0);
        String leg = json.writeValueAsString(new TransitResult.Leg("walk", 300, 4, null, null, List.of(), null, null,
                true, List.of(), List.of(), null, null, null, List.of(), r));
        String trip = json.writeValueAsString(new Trip("walk", 4, 300, 0, 0, "", Map.of(), "safe", Map.of(), List.of(),
                false, null, null, List.of(), r));
        assertThat(leg).doesNotContain("walkRoute");
        assertThat(trip).doesNotContain("walkRoute");
    }

    static RouteResult.Route route(int m, Map<String, Integer> covered, List<RouteResult.Stretch> dark,
            List<RouteResult.Stair> stairs, int unpavedM) {
        return new RouteResult.Route("safe", m, m / 80, List.of(), Map.of(), covered, List.of(), dark, List.of(),
                List.of(), stairs, List.of(), unpavedM, 0);
    }

    @Test
    void clearDayWheelchairWalksWhenFaster() {
        TripScorer.Context clear = new TripScorer.Context("wheelchair", false, false, false, false, false, false, null,
                null, null);
        TripScorer.Scored s = scorer.score(List.of(walk(22, 1300), bus(36, 300, true)), clear);
        assertThat(s.best().type()).isEqualTo("walk");
        assertThat(s.because()).contains("22분으로 14분 더 빨라요");
    }
}
