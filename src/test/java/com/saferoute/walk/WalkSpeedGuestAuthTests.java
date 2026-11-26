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

/**
 * 게스트 본인 확인 필터(com.saferoute.guest)를 실제 창구(걷기 속도) 앞에 붙여 본다 (공개 배포 준비 1-1).
 * 무엇을: 헤더·주소·JSON 본문 어디로 온 게스트 번호든 필터가 확인하고, 창구는 평소처럼 번호와 본문을 받는지.
 * 어떻게: 진짜 WalkSpeedController·WalkSpeedService 에 MockMvc(스프링을 띄우지 않는 standalone)로 요청을 보낸다.
 *        저장소(WalkSpeedRepository)와 토큰 저장소(GuestTokenStore)는 Mockito 가짜, 모드 기본 속도는 BASE 표.
 * 왜: 필터가 본문을 읽고 돌려주지 않거나 헤더 번호를 창구에 안 넘기면 걷기 속도 저장이 통째로 고장 나고,
 *     번호가 서로 다른 요청이나 틀린 토큰을 통과시키면 남의 기록을 읽거나 바꿀 수 있다.
 */
@DisplayName("게스트 본인 확인: 걷기 속도 창구에 붙여 보기")
class WalkSpeedGuestAuthTests {

    static final String GUEST = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    static final String OTHER = "9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d";
    static final String TOKEN = "a".repeat(43);   // 앱이 32바이트 무작위를 base64로 보낸 길이(43자)와 같게
    // 모드별 기본 속도(m/분). 진짜 route.yml 대신 쓴다
    static final Map<String, Double> BASE = Map.of("adult", 80.0, "child", 70.0, "senior", 60.0, "wheelchair", 50.0);

    private final WalkSpeedRepository repo = mock(WalkSpeedRepository.class);
    private final GuestTokenStore tokens = mock(GuestTokenStore.class);

    /** 걷기 속도 창구 앞에 본인 확인 필터를 붙인 MockMvc. enforce = 토큰 없는 요청도 막을지(guest-auth.enforce) */
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
        // 앱이 번호를 주소에서 빼고 X-Guest-Id 헤더로만 보내도(접속 기록에 안 남게) @RequestParam guestId 창구가 그대로 동작해야 한다.
        // repo.find 는 정해 주지 않아 null → 잰 적 없음 → child 기본 속도 70.0 이 나온다
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
        // 헤더는 내 번호, 주소는 남의 번호로 보내 헤더 쪽만 확인받고 남의 기록을 읽는 수법을 막는다.
        // 토큰 확인 전에 막히므로 tokens 는 정해 주지 않았다
        mvc(false).perform(get("/api/walk-speed").param("guestId", OTHER).param("mode", "child")
                        .header(GuestAuthFilter.HEADER_ID, GUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUEST_MISMATCH"));
        verify(repo, never()).find(anyString(), anyString());
    }

    @Test
    @DisplayName("토큰이 틀리면 강제 전이라도 401")
    void wrongTokenIs401EvenWhenNotEnforced() throws Exception {
        // enforce=false 는 "토큰이 없는" 예전 앱만 봐주는 것이다. 틀린 토큰은 남이 쓰는 것이라 언제나 막는다
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
        // 앱이 토큰을 보내기 전에는 막지 않아야 지금 쓰는 사람들이 안 끊긴다. 강제(true)로 바꾸면 업데이트 안내와 함께 401
        when(tokens.check(eq(GUEST), any())).thenReturn(GuestTokenStore.Check.MISSING);   // 토큰 헤더가 없어 null 로 불리므로 any()
        mvc(false).perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "child"))
                .andExpect(status().isOk());
        mvc(true).perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "child"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GUEST_TOKEN_REQUIRED"));
    }

    @Test
    @DisplayName("토큰 표가 없어도 막지 않는다")
    void tableMissingDoesNotBlock() throws Exception {
        // guest_token 표가 없거나(V37 전) DB 오류면 강제 중이라도 통과시킨다. 아니면 DB 문제 하나로 모든 창구가 막힌다
        when(tokens.check(GUEST, TOKEN)).thenReturn(GuestTokenStore.Check.UNAVAILABLE);
        mvc(true).perform(get("/api/walk-speed").param("guestId", GUEST).param("mode", "child")
                        .header(GuestAuthFilter.HEADER_TOKEN, TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("본문(JSON)의 게스트 번호도 확인하고, 본문은 그대로 읽힌다")
    void jsonBodyGuestIsCheckedAndBodyStillReadable() throws Exception {
        // 필터가 guestId 를 찾으려고 본문을 먼저 읽는다. 다시 읽을 수 있게 돌려주지 않으면 @RequestBody 가 빈 본문을 받아 고장 난다
        // 450m ÷ 600초 = 분당 45m. 측정 1개(합 45)와 senior 기본 60을 섞으면 (60×5 + 45) ÷ 6 = 57.5
        when(tokens.check(GUEST, TOKEN)).thenReturn(GuestTokenStore.Check.OK);
        when(repo.sampleSum(GUEST, "senior")).thenReturn(new WalkSpeedRepository.SampleSum(1, 45.0));
        when(repo.find(GUEST, "senior")).thenReturn(new WalkSpeedRepository.Profile(57.5, 1));   // 응답의 측정 수(samples)용
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
        // 본문으로 온 번호도 헤더·주소와 똑같이 확인한다. 막혔으면 서비스가 DB를 건드리면 안 된다
        when(tokens.check(GUEST, TOKEN)).thenReturn(GuestTokenStore.Check.WRONG);
        String body = "{\"guestId\":\"" + GUEST + "\",\"mode\":\"senior\",\"distanceM\":450,\"movingS\":600}";
        mvc(false).perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(GuestAuthFilter.HEADER_TOKEN, TOKEN))
                .andExpect(status().isUnauthorized());
        verify(repo, never()).sampleSum(anyString(), anyString());   // 저장 과정의 어느 단계도 안 감
    }

    @Test
    @DisplayName("헤더와 본문의 게스트 번호가 다르면 400")
    void headerAndBodyDifferIs400() throws Exception {
        // 주소 대신 본문에 남의 번호를 넣어 피하는 경우도 막는다
        String body = "{\"guestId\":\"" + OTHER + "\",\"mode\":\"senior\",\"distanceM\":450,\"movingS\":600}";
        mvc(false).perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(GuestAuthFilter.HEADER_ID, GUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GUEST_MISMATCH"));
    }

    @Test
    @DisplayName("아주 큰 본문은 413이라 본문 게스트로 검사를 피할 수 없다")
    void hugeJsonIs413SoBodyGuestCannotSkipCheck() throws Exception {
        // 본문이 커서 필터가 읽지 않고 넘기면 본문 속 guestId 를 확인 없이 쓰게 된다. 그래서 상한을 넘으면 아예 413
        String pad = "x".repeat(GuestAuthFilter.MAX_JSON_BYTES);   // 이것만으로 상한(256KB)이라 앞뒤 글자를 더하면 넘는다
        String body = "{\"guestId\":\"" + GUEST + "\",\"pad\":\"" + pad + "\"}";
        mvc(false).perform(post("/api/walk-speed").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("TOO_LARGE"));
    }

    @Test
    @DisplayName("게스트 번호가 없는 요청은 건드리지 않는다")
    void requestWithoutGuestPassesUntouched() throws Exception {
        // 게스트 번호가 없는 요청에 필터가 자기 오류를 내면 안 된다. 창구가 원래 내던 오류(guestId 형식)가 그대로 나와야 한다
        mvc(true).perform(get("/api/walk-speed").param("mode", "child"))
                .andExpect(status().isBadRequest())       // 창구가 낸 원래 오류 그대로
                .andExpect(jsonPath("$.code").value("BAD_PARAM"));
        verify(tokens, never()).check(any(), any());   // 확인할 번호가 없으니 토큰 저장소도 안 부름
    }
}
