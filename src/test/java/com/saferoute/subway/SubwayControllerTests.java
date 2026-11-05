package com.saferoute.subway;

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

/** 가짜 SubwayService로 창구의 값 검사·JSON 모양·DB 고장 처리를 확인 */
class SubwayControllerTests {

    private final SubwayService subway = mock(SubwayService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SubwayController(subway))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test
    void returnsStationsAndTrains() throws Exception {
        when(subway.nearby(35.1464, 126.9205)).thenReturn(new SubwayNearbyResponse("timetable", "평일", List.of(
                new StationTrains(16, "문화전당", 35.1464, 126.9205, 120,
                        new StationTrains.Facility(3, 2), new StationTrains.Facility(1, 1),
                        new StationTrains.Facility(2, 2), "5",
                        List.of(new SubwayTrain("평동행", "평동", "10:08", 3, "학동증심사입구", 2)),
                        new StationTrains.ElevatorExits(3, "1,2,6", 1, 6, true)))));

        mvc.perform(get("/api/subway/nearby").param("lat", "35.1464").param("lon", "126.9205"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("timetable"))
                .andExpect(jsonPath("$.dayType").value("평일"))
                .andExpect(jsonPath("$.stations[0].name").value("문화전당"))
                .andExpect(jsonPath("$.stations[0].elevator.available").value(2))
                .andExpect(jsonPath("$.stations[0].elevatorExits.count").value(3))
                .andExpect(jsonPath("$.stations[0].elevatorExits.exitNos").value("1,2,6"))
                .andExpect(jsonPath("$.stations[0].elevatorExits.wheelchairEntry").value(true))
                .andExpect(jsonPath("$.stations[0].trains[0].direction").value("평동행"))
                .andExpect(jsonPath("$.stations[0].trains[0].inMin").value(3))
                .andExpect(jsonPath("$.stations[0].trains[0].nowNear").value("학동증심사입구"))
                .andExpect(jsonPath("$.stations[0].trains[0].stopsAway").value(2));
    }

    @Test
    void missingLatGives400() throws Exception {
        mvc.perform(get("/api/subway/nearby").param("lon", "126.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
        verify(subway, never()).nearby(anyDouble(), anyDouble());
    }

    @Test
    void dbDownGives503() throws Exception {
        when(subway.nearby(anyDouble(), anyDouble())).thenThrow(new DataAccessResourceFailureException("down"));

        mvc.perform(get("/api/subway/nearby").param("lat", "35.1464").param("lon", "126.9205"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }
}
