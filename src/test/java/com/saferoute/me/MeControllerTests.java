package com.saferoute.me;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        // 가짜 프로필의 기본값(300)과 다른 500을 보내야, 보낸 값이 저장되고 다시 읽은 값이 응답에 나오는지 가려진다
        MeRepository.Row before = MeServiceTests.row(true);
        MeRepository.Row after = new MeRepository.Row(before.modeNow(), before.aidNow(), before.tempActive(),
                before.setupDone(), before.askToday(), before.mode(), before.mobilityAid(), before.stairsOk(),
                before.maxDetour(), 500, before.preferTransit(), before.tempMode(), before.tempAid(),
                before.tempReason(), before.tempUntil(), before.consentTrips());
        when(repo.find(GUEST)).thenReturn(Optional.of(before));
        doAnswer(inv -> {
            when(repo.find(GUEST)).thenReturn(Optional.of(after));
            return null;
        }).when(repo).save(eq(GUEST), anyMap());

        mvc.perform(patch("/api/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"" + GUEST + "\",\"restEveryM\":500,\"consentTrips\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restEveryM").value(500));
        verify(repo).save(eq(GUEST), argThat(f -> Integer.valueOf(500).equals(f.get("rest_every_m"))
                && Integer.valueOf(1).equals(f.get("consent_trips"))));
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
    void endTripTellsWhatWasLearned() throws Exception {
        when(repo.endTrip(GUEST, 3L, "arrived", 14, "bad", "계단 많음")).thenReturn(1);
        when(repo.find(GUEST)).thenReturn(Optional.of(MeServiceTests.row(true)));
        when(repo.recentDone(GUEST, 10)).thenReturn(List.of(
                new PreferenceLearner.Done("walk", "arrived", "bad", "계단 많음"),
                new PreferenceLearner.Done("walk", "arrived", "bad", "계단")));
        mvc.perform(patch("/api/me/trips/3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guestId\":\"" + GUEST + "\",\"result\":\"arrived\",\"actualMin\":14,"
                                + "\"feedback\":\"bad\",\"feedbackReason\":\"계단 많음\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.learned[0].what").value("stairsOk"))
                .andExpect(jsonPath("$.learned[0].say").exists());
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
