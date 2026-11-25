package com.saferoute.route;

import static com.saferoute.route.TestRoutes.LAT;
import static com.saferoute.route.TestRoutes.S;
import static com.saferoute.route.TestRoutes.S_P;
import static com.saferoute.route.TestRoutes.T_P;
import static com.saferoute.route.TestRoutes.assertError;
import static com.saferoute.route.TestRoutes.east;
import static com.saferoute.route.TestRoutes.edge;
import static com.saferoute.route.TestRoutes.errorOf;
import static com.saferoute.route.TestRoutes.justAbove;
import static com.saferoute.route.TestRoutes.justBelow;
import static com.saferoute.route.TestRoutes.north;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.saferoute.route.api.RouteError;
import com.saferoute.route.api.RouteException;
import com.saferoute.route.api.RouteRequest;
import com.saferoute.route.api.RouteRequest.Place;
import com.saferoute.route.api.RouteResult;
import com.saferoute.route.data.PoiSource;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.graph.RoadEdge.EdgePenalty;
import com.saferoute.route.engine.graph.RoadGraph;

/**
 * 요청 검사 (요구사항 8.3): 입력 오류, SAME_POINT·TOO_FAR·OUT_OF_AREA와 검사 순서, 기본값, 오류 상태.
 * 경곗값은 경계 ± 0.01m로 잰다 (「경로 엔진 테스트 문서」 경곗값 검사).
 */
@DisplayName("요청 검사")
class RequestValidationTests {

    /** 직선 길 S–M–T와 북쪽 6km의 길 없는 노드 (TestRoutes.lineWithFarNode) */
    static RouteService service() {
        return TestRoutes.service(TestRoutes.lineWithFarNode(), TestRoutes.pois(List.of()), 12);
    }

    static RouteRequest request(double[] from, double[] to) {
        return new RouteRequest(new Place(from[1], from[0], null), new Place(to[1], to[0], null), "auto", "adult");
    }

    @Test
    @DisplayName("출발·도착이 50m 미만이면 SAME_POINT다")
    void samePointBelow50m() {
        // same-point-m (route.yml same-point-m, 요구사항 8.3)
        // 좌표 거리라 경계값 자체는 못 만들어 경계 ±0.01m로 본다. S와 그 동쪽 경계 바로 위 K를 잇는 길을 따로 만든다
        double same = TestRoutes.props().samePointM();
        double[] k = east(S_P, justAbove(same));
        RoadGraph g = new RoadGraph(Map.of(S, S_P, 5L, k, 99L, north(S_P, 6000)),
                List.of(edge(1, S, 5L, S_P, k, EdgePenalty.NONE)));
        RouteService s = TestRoutes.service(g, TestRoutes.pois(List.of()), 12);

        assertThat(errorOf(() -> s.route(request(S_P, east(S_P, justBelow(same)))))).isEqualTo(RouteError.SAME_POINT);
        assertThatCode(() -> s.route(request(S_P, k))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("직선거리가 5000m를 넘으면 TOO_FAR다")
    void tooFarAbove5000m() {
        // too-far-m (route.yml too-far-m, 요구사항 8.3)
        RouteService s = service();
        double far = TestRoutes.props().tooFarM();
        // 한도 안이면 거리 검사는 통과하고, 도착점에서 가장 가까운 길이 너무 멀어 NO_ROUTE가 된다
        assertThat(errorOf(() -> s.route(request(S_P, north(S_P, justBelow(far)))))).isEqualTo(RouteError.NO_ROUTE);
        assertThat(errorOf(() -> s.route(request(S_P, north(S_P, justAbove(far)))))).isEqualTo(RouteError.TOO_FAR);
    }

    @Test
    @DisplayName("서비스 지역 경계선 위는 안이고 조금이라도 밖이면 OUT_OF_AREA다")
    void serviceAreaEdgeIsInside() {
        // 서비스 지역은 노드를 감싼 사각형 (요구사항 8.3)
        RouteService s = service();
        double[] onEdge = {T_P[0], S_P[1]};       // 남쪽 경계선(노드 중 가장 작은 위도)과 동쪽 경계선(T) 위
        double[] outside = {T_P[0] + 1e-7, T_P[1]};
        assertThat(errorOf(() -> s.route(request(S_P, onEdge)))).isNotEqualTo(RouteError.OUT_OF_AREA);
        assertThat(errorOf(() -> s.route(request(S_P, outside)))).isEqualTo(RouteError.OUT_OF_AREA);
    }

    @Test
    @DisplayName("검사는 입력 오류, SAME_POINT, TOO_FAR, OUT_OF_AREA 순서로 한다")
    void checksRunInOrder() {
        // RouteService.validate
        RouteService s = service();
        // 지역 밖 두 점이 30m 떨어져 있으면 OUT_OF_AREA가 아니라 SAME_POINT
        double[] a = {S_P[0] - 0.01, S_P[1]};
        assertThat(errorOf(() -> s.route(request(a, north(a, 30))))).isEqualTo(RouteError.SAME_POINT);
        // 입력 오류(모르는 모드)는 거리 검사보다 먼저
        assertThatThrownBy(() -> s.route(new RouteRequest(new Place(S_P[1], S_P[0], null),
                new Place(S_P[1], S_P[0], null), "auto", "runner"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adult").hasMessageContaining("wheelchair");
    }

    @Test
    @DisplayName("mode·night가 비면 adult·auto로 본다")
    void blankModeAndNightUseDefaults() {
        // route.yml profiles
        RouteService s = service();
        Place from = new Place(S_P[1], S_P[0], null);
        Place to = new Place(T_P[1], T_P[0], null);

        RouteResult blank = s.route(new RouteRequest(from, to, null, null));

        assertThat(blank.conditions().nightSource()).isEqualTo("auto");
        assertThat(blank.explanation()).isEqualTo(s.route(new RouteRequest(from, to, "auto", "adult")).explanation());
        assertThat(RouteProperties.modeName(null)).isEqualTo("adult");
    }

    @Test
    @DisplayName("mode 이름은 대소문자와 앞뒤 공백을 무시한다")
    void modeNameIgnoresCaseAndSpaces() {
        assertThat(RouteProperties.modeName(" Senior ")).isEqualTo("senior");
    }

    @Test
    @DisplayName("출발·도착이 없거나 좌표가 비거나 night 값이 틀리면 입력 오류다")
    void missingOrBrokenInputIsRejectedAsBadRequest() {
        RouteService s = service();

        assertThatThrownBy(() -> s.route(new RouteRequest(null, new Place(LAT, T_P[0], null), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.route(new RouteRequest(new Place(null, S_P[0], null), new Place(LAT, T_P[0], null), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.route(new RouteRequest(new Place(LAT, S_P[0], null), new Place(LAT, T_P[0], null), "dark")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시설 표를 읽다 DB가 끊기면 DB_UNAVAILABLE이다")
    void dbFailureIsDbUnavailable() {
        // 요구사항 8.3
        PoiSource broken = new PoiSource() {
            @Override
            public List<Poi> within(BBox box, Set<String> kinds) {
                throw new DataAccessResourceFailureException("DB 꺼짐");
            }

            @Override
            public Set<LocalDate> holidays() {
                return Set.of();
            }

            @Override
            public LocalDate dataDate() {
                return null;
            }
        };
        RouteService s = TestRoutes.service(TestRoutes.diamond(0.0006), broken, 21);

        assertError(() -> s.route(new RouteRequest(new Place(LAT, S_P[0], null), new Place(LAT, T_P[0], null), "auto")),
                RouteError.DB_UNAVAILABLE);
    }

    @Test
    @DisplayName("오류마다 HTTP 상태와 메시지가 요구사항 오류 표와 같다")
    void errorStatusesMatchRequirements() {
        // 요구사항 8.3 오류 표
        assertThat(RouteError.SAME_POINT.httpStatus()).isEqualTo(400);
        assertThat(RouteError.TOO_FAR.httpStatus()).isEqualTo(400);
        assertThat(RouteError.OUT_OF_AREA.httpStatus()).isEqualTo(422);
        assertThat(RouteError.NO_ROUTE.httpStatus()).isEqualTo(404);
        assertThat(RouteError.DB_UNAVAILABLE.httpStatus()).isEqualTo(503);
        assertThat(new RouteException(RouteError.TOO_FAR).getMessage()).isEqualTo("걸어가기엔 멀어요");
    }
}
