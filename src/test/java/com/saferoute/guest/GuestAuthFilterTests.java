package com.saferoute.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 게스트 본인 확인 필터(GuestAuthFilter): 요청 속 게스트 번호를 찾아 토큰으로 본인인지 확인한다.
 * 토큰 저장소(GuestTokenStore)는 Mockito 가짜로 바꾸고, 요청·응답·다음 필터는 스프링의 Mock 서블릿 객체로 만든다.
 * 이게 깨지면 남의 게스트 번호만 알아도 그 사람의 내 정보·기록을 보거나 지울 수 있고,
 * 반대로 같은 사람을 다른 사람으로 보고 막아 버릴 수 있다.
 * (실제 창구에 붙여 보는 시험은 walk/WalkSpeedGuestAuthTests)
 */
@DisplayName("게스트 본인 확인 필터")
class GuestAuthFilterTests {

    // 앱이 만드는 UUID 모양의 게스트 번호 (소문자)
    static final String GUEST = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    @Test
    @DisplayName("본문 맨 위의 글자 guestId만 읽는다")
    void readsOnlyTopLevelStringGuestId() {
        // 본문 깊숙이 든 guestId 나 숫자 guestId 를 게스트 번호로 잘못 읽으면, 엉뚱한 번호로 확인하거나 확인을 건너뛴다.
        // 깨진 JSON 은 여기서 오류를 내지 않고 null 로 넘겨 컨트롤러가 원래대로 400을 내게 한다
        assertThat(GuestAuthFilter.guestIdFromJson(
                ("{\"guestId\":\" " + GUEST + " \"}").getBytes())).isEqualTo(GUEST);   // 앞뒤 빈칸은 떼어 낸다
        assertThat(GuestAuthFilter.guestIdFromJson(
                "{\"x\":{\"guestId\":\"a\"}}".getBytes())).isNull();          // 안쪽 객체의 guestId 는 무시
        assertThat(GuestAuthFilter.guestIdFromJson("{not json".getBytes())).isNull();                // 깨진 JSON
        assertThat(GuestAuthFilter.guestIdFromJson("{\"guestId\":3}".getBytes())).isNull();           // 글자가 아닌 값
    }

    @Test
    @DisplayName("게스트 번호를 소문자로 맞춰 헤더와 주소가 같게 본다")
    void guestIdIsLowerCasedSoHeaderAndParamAgree() throws Exception {
        // DB 게스트 칸은 대소문자를 구별한다 (V38). 대문자로 와도 같은 사람이고, 다른 표기 둘이 와도 "서로 다름"이 아니다
        GuestTokenStore tokens = mock(GuestTokenStore.class);
        when(tokens.check(GUEST, "t")).thenReturn(GuestTokenStore.Check.OK);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.addHeader(GuestAuthFilter.HEADER_ID, GUEST.toUpperCase());   // 헤더는 대문자, 주소(파라미터)는 소문자
        req.addHeader(GuestAuthFilter.HEADER_TOKEN, "t");
        req.setParameter("guestId", GUEST);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new GuestAuthFilter(tokens, true).doFilter(req, res, chain);   // true = 토큰 강제 켬

        assertThat(res.getStatus()).isEqualTo(200);
        verify(tokens).check(GUEST, "t");   // 소문자로 맞춘 번호로 토큰을 확인했다
        // 다음 필터(요청 수 제한)가 쓸 게스트 번호도 소문자로 넘어간다
        assertThat(chain.getRequest().getAttribute(GuestAuthFilter.ATTR_GUEST)).isEqualTo(GUEST);
    }
}
