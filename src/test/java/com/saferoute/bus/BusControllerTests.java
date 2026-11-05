package com.saferoute.bus;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;

/** 가짜 BusService로 창구의 값 검사·JSON 모양·DB 고장 처리를 확인 */
class BusControllerTests {

    private final BusService bus = mock(BusService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BusController(bus))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test
    void returnsStopsAndArrivals() throws Exception {
        when(bus.nearby(35.1401, 126.9336)).thenReturn(new BusNearbyResponse(List.of(
                new StopArrivals(1230, "조선대", "남광주", 35.1405, 126.933, 60, true,
                        List.of(new BusArrival(15, "15", 3, 1, true, "광주역", "조선대"))))));

        mvc.perform(get("/api/bus/nearby").param("lat", "35.1401").param("lon", "126.9336"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].id").value(1230))
                .andExpect(jsonPath("$.stops[0].name").value("조선대"))
                .andExpect(jsonPath("$.stops[0].distanceM").value(60))
                .andExpect(jsonPath("$.stops[0].available").value(true))
                .andExpect(jsonPath("$.stops[0].arrivals[0].lineId").value(15))
                .andExpect(jsonPath("$.stops[0].arrivals[0].lineName").value("15"))
                .andExpect(jsonPath("$.stops[0].arrivals[0].remainMin").value(3))
                .andExpect(jsonPath("$.stops[0].arrivals[0].lowFloor").value(true));
    }

    @Test
    void missingLonGives400() throws Exception {
        mvc.perform(get("/api/bus/nearby").param("lat", "35.1401"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
        verify(bus, never()).nearby(anyDouble(), anyDouble());
    }

    @Test
    void outOfRangeGives400() throws Exception {
        mvc.perform(get("/api/bus/nearby").param("lat", "135").param("lon", "126.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void notANumberGives400() throws Exception {
        mvc.perform(get("/api/bus/nearby").param("lat", "abc").param("lon", "126.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void dbDownGives503() throws Exception {
        when(bus.nearby(anyDouble(), anyDouble())).thenThrow(new DataAccessResourceFailureException("down"));

        mvc.perform(get("/api/bus/nearby").param("lat", "35.1401").param("lon", "126.9336"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }

    @Test
    void returnsBusLocations() throws Exception {
        when(bus.locations(2)).thenReturn(new BusLocationResponse(2, true, List.of(
                new BusLocation("775293", true, 1205, "조선대", 35.14, 126.93, 30))));

        mvc.perform(get("/api/bus/locations").param("lineId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineId").value(2))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.buses[0].busId").value("775293"))
                .andExpect(jsonPath("$.buses[0].lowFloor").value(true))
                .andExpect(jsonPath("$.buses[0].stopName").value("조선대"))
                .andExpect(jsonPath("$.buses[0].lat").value(35.14));
    }

    @Test
    void missingLineIdGives400() throws Exception {
        mvc.perform(get("/api/bus/locations"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
        verify(bus, never()).locations(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void locationsDbDownGives503() throws Exception {
        when(bus.locations(2)).thenThrow(new DataAccessResourceFailureException("down"));

        mvc.perform(get("/api/bus/locations").param("lineId", "2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }
}
