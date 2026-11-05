package com.saferoute.walk;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;

class WalkSpeedControllerTests {

    private static final String GUEST = WalkSpeedServiceTests.GUEST_LOWER;

    private final WalkSpeedRepository repo = mock(WalkSpeedRepository.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WalkSpeedController(
                    new WalkSpeedService(repo, WalkSpeedServiceTests.BASE::get, Clock.system(ZoneId.of("Asia/Seoul")))))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test
    void postGives200WithNewSpeed() throws Exception {
        when(repo.sampleSum(GUEST, "senior")).thenReturn(new WalkSpeedRepository.SampleSum(1, 45.0));
        when(repo.find(GUEST, "senior")).thenReturn(new WalkSpeedRepository.Profile(57.5, 1));

        mvc.perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"" + GUEST + "\",\"mode\":\"senior\",\"distanceM\":450,\"movingS\":600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("senior"))
                .andExpect(jsonPath("$.speedMMin").value(57.5))
                .andExpect(jsonPath("$.baseSpeedMMin").value(60.0))
                .andExpect(jsonPath("$.samples").value(1))
                .andExpect(jsonPath("$.personal").value(true));
    }

    @Test
    void postTooFastGives400() throws Exception {
        mvc.perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"" + GUEST + "\",\"mode\":\"adult\",\"distanceM\":3000,\"movingS\":600}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void emptyBodyGives400() throws Exception {
        mvc.perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void getWithoutRecordGivesBase() throws Exception {
        mvc.perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "child"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speedMMin").value(70.0))
                .andExpect(jsonPath("$.personal").value(false));
    }

    @Test
    void getWithoutGuestGives400() throws Exception {
        mvc.perform(get("/api/walk-speed").param("mode", "child"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void deleteGivesCount() throws Exception {
        when(repo.deleteGuest(GUEST)).thenReturn(2);
        mvc.perform(delete("/api/walk-speed").param("guestId", GUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedModes").value(2));
    }

    @Test
    void dbDownGives503() throws Exception {
        when(repo.find(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        mvc.perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "adult"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }
}
