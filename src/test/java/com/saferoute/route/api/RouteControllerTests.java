package com.saferoute.route.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;
import com.saferoute.common.ErrorResponse;
import com.saferoute.route.RouteService;
import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 가짜 경로 엔진·가짜 날씨로, 창구가 요청을 넘기고 답·오류를 알맞게 돌려주는지 확인 (DB·인터넷 없이 돈다) */
class RouteControllerTests {

    private static final RouteRequest.Place CHOSUN = new RouteRequest.Place(35.1401, 126.9336, "조선대학교");
    private static final RouteRequest.Place HOSPITAL = new RouteRequest.Place(35.1397, 126.9264, "조선대병원");
    private static final RouteResult EMPTY = new RouteResult(null, List.of(), null, List.of(), null);
    private static final RouteResult NIGHT = new RouteResult(new RouteResult.Conditions(true, "auto", "17:36"),
            List.of(), null, List.of(), null);
    private static final WeatherDust RAIN_DUST = new WeatherDust(true, false, false, 12.0, true, true, true);

    private final RouteService routeService = mock(RouteService.class);
    private final WeatherDustService weatherDust = mock(WeatherDustService.class);
    private final RouteController controller =
            new RouteController(routeService, weatherDust, JsonMapper.builder().build());

    @BeforeEach
    void fakeWeather() {
        CompletableFuture<WeatherDust> f = CompletableFuture.completedFuture(RAIN_DUST);
        when(weatherDust.start(anyDouble(), anyDouble())).thenReturn(f);
        when(weatherDust.await(any())).thenReturn(RAIN_DUST);
    }

    @Test
    void returnsEngineResultWithWeatherAdded() {
        RouteRequest req = new RouteRequest(CHOSUN, HOSPITAL, "auto", "child");
        when(routeService.route(req)).thenReturn(NIGHT);

        ResponseEntity<?> res = controller.route(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = (JsonNode) res.getBody();
        JsonNode c = body.get("conditions");
        // B가 만든 칸은 그대로
        assertThat(c.get("night").asBoolean()).isTrue();
        assertThat(c.get("nightSource").asString()).isEqualTo("auto");
        assertThat(c.get("sunset").asString()).isEqualTo("17:36");
        // C가 더한 칸
        assertThat(c.get("rain").asBoolean()).isTrue();
        assertThat(c.get("heat").asBoolean()).isFalse();
        assertThat(c.get("cold").asBoolean()).isFalse();
        assertThat(c.get("tempC").asDouble()).isEqualTo(12.0);
        assertThat(c.get("weatherAvailable").asBoolean()).isTrue();
        assertThat(c.get("dustBad").asBoolean()).isTrue();
        assertThat(c.get("dustAvailable").asBoolean()).isTrue();
        assertThat(body.get("routes").isArray()).isTrue();
    }

    @Test
    void weatherUnknownStillReturnsRoute() {
        when(weatherDust.await(any())).thenReturn(WeatherDust.unknown());
        when(routeService.route(any())).thenReturn(NIGHT);

        JsonNode c = ((JsonNode) controller.route(new RouteRequest(CHOSUN, HOSPITAL, "auto")).getBody())
                .get("conditions");

        assertThat(c.get("night").asBoolean()).isTrue();
        assertThat(c.get("weatherAvailable").asBoolean()).isFalse();
        assertThat(c.get("dustAvailable").asBoolean()).isFalse();
        assertThat(c.get("tempC").isNull()).isTrue();
    }

    @Test
    void noConditionsFromEngineStillGetsWeather() {
        when(routeService.route(any())).thenReturn(EMPTY);

        JsonNode c = ((JsonNode) controller.route(new RouteRequest(CHOSUN, HOSPITAL, "auto")).getBody())
                .get("conditions");

        assertThat(c.get("rain").asBoolean()).isTrue();
    }

    @Test
    void missingOriginDoesNotAskWeather() {
        when(routeService.route(any())).thenThrow(new IllegalArgumentException("origin과 destination의 lat·lon이 필요합니다"));

        ResponseEntity<?> res = controller.route(new RouteRequest(null, HOSPITAL, "auto"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(weatherDust, never()).start(anyDouble(), anyDouble());
    }

    @Test
    void routeErrorKeepsStatusAndCode() {
        when(routeService.route(any())).thenThrow(new RouteException(RouteError.OUT_OF_AREA));

        ResponseEntity<?> res = controller.route(new RouteRequest(CHOSUN, HOSPITAL, "auto"));

        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody()).isEqualTo(new ErrorResponse("OUT_OF_AREA", "아직 광주만 길찾기가 됩니다"));
    }

    @Test
    void everyRouteErrorUsesItsOwnStatus() {
        for (RouteError error : RouteError.values()) {
            RouteService engine = mock(RouteService.class);
            when(engine.route(any())).thenThrow(new RouteException(error));

            ResponseEntity<?> res = new RouteController(engine, weatherDust, JsonMapper.builder().build())
                    .route(new RouteRequest(CHOSUN, HOSPITAL, "auto"));

            assertThat(res.getStatusCode().value()).isEqualTo(error.httpStatus());
            assertThat(((ErrorResponse) res.getBody()).code()).isEqualTo(error.name());
        }
    }

    @Test
    void badNightOrModeGives400() {
        when(routeService.route(any())).thenThrow(new IllegalArgumentException("night 는 auto, on, off 중 하나여야 합니다"));

        ResponseEntity<?> res = controller.route(new RouteRequest(CHOSUN, HOSPITAL, "noon"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(((ErrorResponse) res.getBody()).code()).isEqualTo("BAD_PARAM");
    }

    @Test
    void readsJsonBodyIntoRequest() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrorHandler()).build();
        when(routeService.route(any())).thenReturn(NIGHT);

        mvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content("""
                { "origin": { "lat": 35.1401, "lon": 126.9336, "name": "조선대학교" },
                  "destination": { "lat": 35.1397, "lon": 126.9264 },
                  "night": "on", "mode": "senior" }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes").isArray())
                .andExpect(jsonPath("$.conditions.night").value(true))
                .andExpect(jsonPath("$.conditions.rain").value(true))
                .andExpect(jsonPath("$.conditions.dustBad").value(true));

        ArgumentCaptor<RouteRequest> sent = ArgumentCaptor.forClass(RouteRequest.class);
        verify(routeService).route(sent.capture());
        assertThat(sent.getValue().origin().lat()).isEqualTo(35.1401);
        assertThat(sent.getValue().destination().lon()).isEqualTo(126.9264);
        assertThat(sent.getValue().night()).isEqualTo("on");
        assertThat(sent.getValue().mode()).isEqualTo("senior");
    }

    @Test
    void brokenJsonGives400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrorHandler()).build();

        mvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content("{ 깨진"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }
}
