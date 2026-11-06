package com.saferoute.me;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.saferoute.common.ApiErrorHandler;

class MeControllerTests {

    static final String GUEST = MeServiceTests.GUEST;

    final MeRepository repo = mock(MeRepository.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MeController(new MeService(repo, MeServiceTests.CLOCK)))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test
    void viewGivesProfileWithoutGuestId() throws Exception {
        when(repo.find(GUEST)).thenReturn(Optional.of(MeServiceTests.row(true)));
        when(repo.layers(GUEST)).thenReturn(List.of());
        mvc.perform(get("/api/me").param("guestId", GUEST))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.known").value(true))
                .andExpect(jsonPath("$.modeNow").value("senior"))
                .andExpect(jsonPath("$.guestId").doesNotExist());
    }

    @Test
    void missingGuestIdGives400() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
    }

    @Test
    void patchReturnsUpdatedView() throws Exception {
        when(repo.find(GUEST)).thenReturn(Optional.of(MeServiceTests.row(true)));
        mvc.perform(patch("/api/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"" + GUEST + "\",\"restEveryM\":300,\"consentTrips\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restEveryM").value(300));
    }

    @Test
    void tripWithoutConsentGives403() throws Exception {
        when(repo.find(GUEST)).thenReturn(Optional.of(MeServiceTests.row(false)));
        mvc.perform(post("/api/me/trips").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"" + GUEST + "\",\"toName\":\"집\",\"toLat\":35.14,\"toLon\":126.92,"
                                + "\"mode\":\"senior\",\"travel\":\"walk\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_CONSENT"));
    }

    @Test
    void endTripGives204() throws Exception {
        when(repo.endTrip(GUEST, 3L, "arrived", 14, "good", null)).thenReturn(1);
        mvc.perform(patch("/api/me/trips/3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"" + GUEST + "\",\"result\":\"arrived\",\"actualMin\":14,\"feedback\":\"good\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAllTellsWhatWasDeleted() throws Exception {
        when(repo.deleteAll(GUEST)).thenReturn(new MeRequests.Deleted(true, 12, 2, 1, 1));
        mvc.perform(delete("/api/me").param("guestId", GUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value(true))
                .andExpect(jsonPath("$.trips").value(12))
                .andExpect(jsonPath("$.guardianShares").value(1));
    }

    @Test
    void dbDownGives503() throws Exception {
        when(repo.find(anyString())).thenThrow(new DataAccessResourceFailureException("down"));
        mvc.perform(get("/api/me").param("guestId", GUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"));
    }
}
