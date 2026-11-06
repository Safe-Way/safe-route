package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JosaTests {

    @Test
    void picksParticleByLastSyllable() {
        assertThat(Josa.eun("화장실")).isEqualTo("화장실은");
        assertThat(Josa.eun("약국 근처")).isEqualTo("약국 근처는");
        assertThat(Josa.iGa("카페")).isEqualTo("카페가");
        assertThat(Josa.iGa("엘리베이터 출구")).isEqualTo("엘리베이터 출구가");
        assertThat(Josa.eun("'서울역'")).isEqualTo("'서울역'은");
        assertThat(Josa.iGa("공사")).isEqualTo("공사가");
    }
}
