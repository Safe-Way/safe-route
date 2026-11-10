package com.saferoute.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReporterHashTests {

    private static final String GUEST = "00000000-0000-4000-8000-000000000123";

    @Test
    void sameGuestGivesSameHash() {
        ReporterHash h = new ReporterHash("s3cret#with-hash");
        assertThat(h.of(GUEST)).isEqualTo(h.of(" " + GUEST + " ")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(h.of(GUEST)).isNotEqualTo(h.of("00000000-0000-4000-8000-000000000124"));
    }

    @Test
    void knownHmacValue() {
        // RFC 4231 시험 2: 키 "Jefe", 글 "what do ya want for nothing?"
        assertThat(new ReporterHash("Jefe").of("what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void differentSecretGivesDifferentHash() {
        assertThat(new ReporterHash("a").of(GUEST)).isNotEqualTo(new ReporterHash("b").of(GUEST));
    }

    @Test
    void noSecretOrNoGuestGivesNull() {
        assertThat(new ReporterHash("").of(GUEST)).isNull();
        assertThat(new ReporterHash(null).ready()).isFalse();
        ReporterHash h = new ReporterHash("s");
        assertThat(h.of(null)).isNull();
        assertThat(h.of("  ")).isNull();
        assertThat(h.of("x".repeat(ReporterHash.MAX_GUEST_ID + 1))).isNull();
    }
}
