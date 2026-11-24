package com.saferoute.walk;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saferoute.common.ApiErrorHandler;
import com.saferoute.guest.GuestAuthFilter;
import com.saferoute.guest.GuestTokenStore;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 게스트 본인 확인 필터(com.saferoute.guest)를 실제 창구(걷기 속도) 앞에 붙여 본다 (공개 배포 준비 1-1) */
@DisplayName("게스트 본인 확인: 걷기 속도 창구에 붙여 보기")
class WalkSpeedGuestAuthTests {

    static final String GUEST = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    static final String OTHER = "9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d";
    static final String TOKEN = "a".repeat(43);
    static final Map<String, Double> BASE = Map.of("adult", 80.0, "child", 70.0, "senior", 60.0, "wheelchair", 50.0);

    private final WalkSpeedRepository repo = mock(WalkSpeedRepository.class);
    private final GuestTokenStore tokens = mock(GuestTokenStore.class);

    private MockMvc mvc(boolean enforce) {
        return MockMvcBuilders.standaloneSetup(new WalkSpeedController(
                        new WalkSpeedService(repo, BASE::get, Clock.system(ZoneId.of("Asia/Seoul")))))
                .setControllerAdvice(new ApiErrorHandler())
                .addFilters(new GuestAuthFilter(tokens, enforce))
                .build();
    }

    @Test
    @DisplayName("헤더에만 있는 게스트 번호도 창구까지 전달된다")
    void headerOnlyGuestReachesRequestParam() throws Exception {
        when(tokens.check(GUEST, TOKEN)).thenReturn(GuestTokenStore.Check.OK);
        mvc(false).perform(get("/api/walk-speed").param("mode", "child")
                        .header(GuestAuthFilter.HEADER_ID, GUEST).header(GuestAuthFilter.HEADER_TOKEN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speedMMin").value(70.0));
        verify(repo).find(GUEST, "child");   // 주소에 guestId 가 없어도 창구가 헤더의 번호를 받음
    }

    @Test
    @DisplayName("헤더와 주소의 게스트 번호가 다르면 400")
    void headerAndParamDifferIs400() throws Exception {
        mvc(false).perform(get("/api/walk-speed").param("guestId", OTHER).param("mode", "child")
                        .header(GuestAuthFilter.HEADER_ID, GUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUEST_MISMATCH"));
        verify(repo, never()).find(anyString(), anyString());
    }

    @Test
    @DisplayName("토큰이 틀리면 강제 전이라도 401")
    void wrongTokenIs401EvenWhenNotEnforced() throws Exception {
        when(tokens.check(GUEST, TOKEN)).thenReturn(GuestTokenStore.Check.WRONG);
        mvc(false).perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "child")
                        .header(GuestAuthFilter.HEADER_TOKEN, TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GUEST_TOKEN_WRONG"));
        verify(repo, never()).find(anyString(), anyString());
    }

    @Test
    @DisplayName("토큰이 없으면 강제 전까지는 통과")
    void missingTokenPassesUntilEnforced() throws Exception {
        when(tokens.check(eq(GUEST), any())).thenReturn(GuestTokenStore.Check.MISSING);
        mvc(false).perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "child"))
                .andExpect(status().isOk());
        mvc(true).perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "child"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GUEST_TOKEN_REQUIRED"));
    }

    @Test
    @DisplayName("토큰 표가 없어도 막지 않는다")
    void tableMissingDoesNotBlock() throws Exception {
        when(tokens.check(GUEST, TOKEN)).thenReturn(GuestTokenStore.Check.UNAVAILABLE);
        mvc(true).perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "child")
                        .header(GuestAuthFilter.HEADER_TOKEN, TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("본문(JSON)의 게스트 번호도 확인하고, 본문은 그대로 읽힌다")
    void jsonBodyGuestIsCheckedAndBodyStillReadable() throws Exception {
        when(tokens.check(GUEST, TOKEN)).thenReturn(GuestTokenStore.Check.OK);
        when(repo.sampleSum(GUEST, "senior")).thenReturn(new WalkSpeedRepository.SampleSum(1, 45.0));
        when(repo.find(GUEST, "senior")).thenReturn(new WalkSpeedRepository.Profile(57.5, 1));
        String body = "{\"guestId\":\"" + GUEST + "\",\"mode\":\"senior\",\"distanceM\":450,\"movingS\":600}";
        mvc(true).perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(GuestAuthFilter.HEADER_TOKEN, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.speedMMin").value(57.5));
        verify(tokens).check(GUEST, TOKEN);
    }

    @Test
    @DisplayName("본문 게스트에 틀린 토큰이면 창구까지 가지 않는다")
    void jsonBodyWithWrongTokenNeverReachesController() throws Exception {
        when(tokens.check(GUEST, TOKEN)).thenReturn(GuestTokenStore.Check.WRONG);
        String body = "{\"guestId\":\"" + GUEST + "\",\"mode\":\"senior\",\"distanceM\":450,\"movingS\":600}";
        mvc(false).perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(GuestAuthFilter.HEADER_TOKEN, TOKEN))
                .andExpect(status().isUnauthorized());
        verify(repo, never()).sampleSum(anyString(), anyString());
    }

    @Test
    @DisplayName("헤더와 본문의 게스트 번호가 다르면 400")
    void headerAndBodyDifferIs400() throws Exception {
        String body = "{\"guestId\":\"" + OTHER + "\",\"mode\":\"senior\",\"distanceM\":450,\"movingS\":600}";
        mvc(false).perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(GuestAuthFilter.HEADER_ID, GUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUEST_MISMATCH"));
    }

    @Test
    @DisplayName("아주 큰 본문은 413이라 본문 게스트로 검사를 피할 수 없다")
    void hugeJsonIs413SoBodyGuestCannotSkipCheck() throws Exception {
        String pad = "x".repeat(GuestAuthFilter.MAX_JSON_BYTES);
        String body = "{\"guestId\":\"" + GUEST + "\",\"pad\":\"" + pad + "\"}";
        mvc(false).perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("TOO_LARGE"));
    }

    @Test
    @DisplayName("게스트 번호가 없는 요청은 건드리지 않는다")
    void requestWithoutGuestPassesUntouched() throws Exception {
        mvc(true).perform(get("/api/walk-speed").param("mode", "child"))
                .andExpect(status().isBadRequest())       // 창구가 낸 원래 오류 그대로
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
        verify(tokens, never()).check(any(), any());
    }
}
