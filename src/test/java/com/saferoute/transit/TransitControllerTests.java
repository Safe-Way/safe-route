package com.saferoute.transit;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.bus.BusService;
import com.saferoute.common.ApiErrorHandler;
import com.saferoute.route.RouteService;
import com.saferoute.subway.SubwayService;

class TransitControllerTests {

    private final OdsayClient odsay = mock(OdsayClient.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TransitController(new TransitService(odsay,
                    mock(RouteService.class), mock(BusService.class), mock(SubwayService.class), Clock.systemUTC())))
            .setControllerAdvice(new ApiErrorHandler()).build();

    private static final String BODY = """
            {"origin":{"lat":35.144,"lon":126.932},"destination":{"lat":35.16,"lon":126.85},"mode":"adult"}""";

    @Test
    void tooCloseGives422() throws Exception {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenThrow(TransitException.tooClose());
        mvc.perform(post("/api/transit").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TOO_CLOSE"));
    }

    @Test
    void dailyLimitGives429() throws Exception {
        when(odsay.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenThrow(TransitException.dailyLimit());
        mvc.perform(post("/api/transit").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DAILY_LIMIT"));
    }

    @Test
    void missingOriginGives400() throws Exception {
        mvc.perform(post("/api/transit").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destination\":{\"lat\":35.16,\"lon\":126.85}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void badModeGives400() throws Exception {
        mvc.perform(post("/api/transit").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("adult", "runner")))
                .andExpect(status().isBadRequest());
    }
}
