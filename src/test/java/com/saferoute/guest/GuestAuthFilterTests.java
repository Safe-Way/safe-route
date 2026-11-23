package com.saferoute.guest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuestAuthFilterTests {

    static final String GUEST = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    @Test
    void readsOnlyTopLevelStringGuestId() {
        assertThat(GuestAuthFilter.guestIdFromJson(
                ("{\"guestId\":\" " + GUEST + " \"}").getBytes())).isEqualTo(GUEST);
        assertThat(GuestAuthFilter.guestIdFromJson(
                "{\"x\":{\"guestId\":\"a\"}}".getBytes())).isNull();
        assertThat(GuestAuthFilter.guestIdFromJson("{not json".getBytes())).isNull();
        assertThat(GuestAuthFilter.guestIdFromJson("{\"guestId\":3}".getBytes())).isNull();
    }
}
