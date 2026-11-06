package com.saferoute.event;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /api/events 답 모양과 오류 (DB 없이) */
class EventControllerTests {

    private final EventService service = mock(EventService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EventController(service)).build();

    @Test
    void listsEventsInBox() throws Exception {
        when(service.within(126.90, 35.13, 126.94, 35.16)).thenReturn(List.of(new RoadEvent(7, "행사", null,
                "금남로 차 없는 거리 걷자잉", "금남로 일원", 35.149, 126.917, 300, "2026-04-04T00:00", "2026-11-07T23:59",
                null, null)));
        mvc.perform(get("/api/events").param("bbox", "126.90,35.13,126.94,35.16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].kind").value("행사"))
                .andExpect(jsonPath("$.events[0].radiusM").value(300))
                .andExpect(jsonPath("$.events[0].lat").value(35.149));
    }

    @Test
    void badBoxGives400() throws Exception {
        mvc.perform(get("/api/events").param("bbox", "126.9,35.1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
        when(service.within(126.94, 35.13, 126.90, 35.16)).thenThrow(new IllegalArgumentException("순서"));
        mvc.perform(get("/api/events").param("bbox", "126.94,35.13,126.90,35.16"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void databaseDownGives503() throws Exception {
        when(service.within(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        mvc.perform(get("/api/events").param("bbox", "126.90,35.13,126.94,35.16"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }
}
