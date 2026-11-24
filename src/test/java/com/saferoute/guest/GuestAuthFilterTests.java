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

@DisplayName("게스트 본인 확인 필터")
class GuestAuthFilterTests {

    static final String GUEST = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    @Test
    @DisplayName("본문 맨 위의 글자 guestId만 읽는다")
    void readsOnlyTopLevelStringGuestId() {
        assertThat(GuestAuthFilter.guestIdFromJson(
                ("{\"guestId\":\" " + GUEST + " \"}").getBytes())).isEqualTo(GUEST);
        assertThat(GuestAuthFilter.guestIdFromJson(
                "{\"x\":{\"guestId\":\"a\"}}".getBytes())).isNull();
        assertThat(GuestAuthFilter.guestIdFromJson("{not json".getBytes())).isNull();
        assertThat(GuestAuthFilter.guestIdFromJson("{\"guestId\":3}".getBytes())).isNull();
    }

    @Test
    @DisplayName("게스트 번호를 소문자로 맞춰 헤더와 주소가 같게 본다")
    void guestIdIsLowerCasedSoHeaderAndParamAgree() throws Exception {
        // DB 게스트 칸은 대소문자를 구별한다 (V38). 대문자로 와도 같은 사람이고, 다른 표기 둘이 와도 "서로 다름"이 아니다
        GuestTokenStore tokens = mock(GuestTokenStore.class);
        when(tokens.check(GUEST, "t")).thenReturn(GuestTokenStore.Check.OK);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.addHeader(GuestAuthFilter.HEADER_ID, GUEST.toUpperCase());
        req.addHeader(GuestAuthFilter.HEADER_TOKEN, "t");
        req.setParameter("guestId", GUEST);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new GuestAuthFilter(tokens, true).doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        verify(tokens).check(GUEST, "t");
        assertThat(chain.getRequest().getAttribute(GuestAuthFilter.ATTR_GUEST)).isEqualTo(GUEST);
    }
}
