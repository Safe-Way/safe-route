package com.saferoute.route.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;
import com.saferoute.common.ErrorResponse;
import com.saferoute.route.RouteService;

/** 가짜 경로 엔진으로, 창구가 요청을 넘기고 답·오류를 알맞게 돌려주는지 확인 (DB 없이 돈다) */
class RouteControllerTests {

    private static final RouteRequest.Place CHOSUN = new RouteRequest.Place(35.1401, 126.9336, "조선대학교");
    private static final RouteRequest.Place HOSPITAL = new RouteRequest.Place(35.1397, 126.9264, "조선대병원");
    private static final RouteResult EMPTY = new RouteResult(null, List.of(), null, List.of(), null);

    private final RouteService routeService = mock(RouteService.class);
    private final RouteController controller = new RouteController(routeService);

    @Test
    void returnsEngineResult() {
        RouteRequest req = new RouteRequest(CHOSUN, HOSPITAL, "auto", "child");
        when(routeService.route(req)).thenReturn(EMPTY);

        ResponseEntity<?> res = controller.route(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isSameAs(EMPTY);
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

            ResponseEntity<?> res = new RouteController(engine).route(new RouteRequest(CHOSUN, HOSPITAL, "auto"));

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
        when(routeService.route(any())).thenReturn(EMPTY);

        mvc.perform(post("/api/route").contentType(MediaType.APPLICATION_JSON).content("""
                { "origin": { "lat": 35.1401, "lon": 126.9336, "name": "조선대학교" },
                  "destination": { "lat": 35.1397, "lon": 126.9264 },
                  "night": "on", "mode": "senior" }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes").isArray());

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
