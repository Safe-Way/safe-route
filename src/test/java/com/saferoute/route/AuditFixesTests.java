package com.saferoute.route;

import static com.saferoute.route.TestRoutes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.InMemoryRoadGraphSource;
import com.saferoute.route.engine.event.RoadEvent;
import com.saferoute.route.engine.explain.ExplanationBuilder;
import com.saferoute.route.engine.facility.Availability;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/** 경로 엔진 감사(2026-11-09)에서 나온 문제 1~5 */
class AuditFixesTests {

    static RoadGraph straight(EdgePenalty p) {
        return new RoadGraph(Map.of(S, S_P, T, T_P), List.of(edge(1, S, T, S_P, T_P, p)));
    }

    static RouteRequest request(String night, String mode) {
        return new RouteRequest(new Place(LAT, S_P[0], "출발"), new Place(LAT, T_P[0], "도착"), night, mode);
    }

    @Test
    void wheelchairWarningAddedLaterStillFixesTheSafestSentence() {
        // 1. 밤 휠체어, 피할 수 없는 비포장 하나뿐: 주의가 붙으면 "가장 안전한 길"이라고 하지 않는다
        RouteResult r = TestRoutes.service(straight(new EdgePenalty(Map.of(Penalty.UNPAVED, 1.0))),
                TestRoutes.pois(List.of()), 21).route(request("on", "wheelchair"));

        assertThat(r.explanation().notes()).anyMatch(n -> n.kind().equals("unpaved") && n.level().equals("warn"));
        assertThat(r.explanation().summary()).isEqualTo("가장 짧은 길로 갑니다");
        assertThat(r.explanation().text()).startsWith("가장 짧은 길로 갑니다 (주의: 비포장길");
    }

    @Test
    void countsUseTheSameFacilitiesAsTheRouteChoice() {
        // 2. 길 옆 화장실 안 비상벨(24시간)과 평일 9~18시 지킴이집
        Poi indoorBell = new Poi("bell", "화장실", 1, M_P[0], LAT + 0.0001, 0, 24 * 60, "매일", null, null,
                null, null, null, null, 1, 1);
        Poi guard = new Poi("guard_house", null, null, M_P[0], LAT - 0.0001, null, null, null, null, null,
                9 * 60, 18 * 60, "평일", true, 2, 1);
        List<Poi> pois = List.of(indoorBell, guard);

        RouteResult noon = TestRoutes.service(straight(EdgePenalty.NONE), TestRoutes.pois(pois), 12)
                .route(request("off", "adult"));
        assertThat(noon.routes().get(0).counts()).containsEntry("bell", 1).containsEntry("help", 1);
        // 밤에는 지킴이집·건물 안 비상벨을 경로 고를 때 안 쓰니 세지도 않는다
        RouteResult night = TestRoutes.service(straight(EdgePenalty.NONE), TestRoutes.pois(pois), 21)
                .route(request("on", "adult"));
        assertThat(night.routes().get(0).counts()).containsEntry("bell", 0).containsEntry("help", 0);
    }

    @Test
    void eventIsNotAvoidedWhenTheSnappedStartIsInside() {
        // 3. 요청한 출발점은 행사 반경 밖(약 58m)이지만 붙인 길 점 S는 안(약 33m). S에 닿는 길이 모두 반경 안
        RoadEvent ev = new RoadEvent(1, "행사", "충장축제", null, null, S_P[0], LAT - 0.0003, 45, null);
        RouteService s = TestRoutes.service(diamond(0.0004), TestRoutes.pois(List.of(), List.of(ev)), 12);
        RouteResult r = s.route(new RouteRequest(new Place(LAT + 0.0002, S_P[0] + 0.0002, "출발"),
                new Place(LAT, T_P[0], "도착"), "auto", "senior"));

        // 피할 길이 없다는 틀린 주의 대신, 피하지 않는 행사로 알리기만 한다
        assertThat(r.explanation().notes()).noneMatch(n -> n.level().equals("warn"));
        assertThat(r.routes().get(r.routes().size() - 1).events()).allMatch(e -> !e.avoid());
    }

    @Test
    void nearNotesReadNaturallyForEveryKind() {
        // 4. 행사가 아닌 종류, 받침, 여러 건
        List<RouteResult.Event> safe = List.of(
                new RouteResult.Event("공사", "무진대로 공사", null, null, null, 0, 50, false, "start"),
                new RouteResult.Event("재난", "금남로 침수", null, null, null, 0, 50, false, "end"),
                new RouteResult.Event("행사", "충장축제", null, null, null, 0, 50, false, "both"),
                new RouteResult.Event("행사", "비엔날레 콘서트", null, null, null, 0, 50, false, "both"));

        assertThat(ExplanationBuilder.eventNotes("요약", List.of(), safe)).extracting(RouteResult.Note::text)
                .containsExactly("출발지 근처에 무진대로 공사가 있어요",
                        "도착지 근처에 금남로 침수 재난이 있어요",
                        "출발지와 도착지 근처에서 충장축제 행사가 열리고 있어요",
                        "출발지와 도착지 근처에서 비엔날레 콘서트 행사가 열리고 있어요");
    }

    @Test
    void halfFilledHoursDoNotCrash() {
        // 5. 여는 시각만 있고 닫는 시각이 비어 있으면 시간을 모르는 것으로 본다 (예전엔 null 오류로 요청 전체 실패)
        Poi toilet = new Poi("toilet", "공중화장실", null, 126.93, 35.14, 9 * 60, null, "매일", null, null,
                9 * 60, null, "매일", true);
        assertThatCode(() -> Availability.usable(toilet, LocalDateTime.of(2026, 11, 4, 12, 0), Set.<LocalDate>of()))
                .doesNotThrowAnyException();
    }
}
