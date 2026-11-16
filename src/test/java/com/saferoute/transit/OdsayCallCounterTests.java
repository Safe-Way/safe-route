package com.saferoute.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** ODsay 하루 부른 수: 그날 줄을 만들고, 한도보다 적을 때만 올린다 (DB 없이 돈다) */
class OdsayCallCounterTests {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OdsayCallCounter counter = new OdsayCallCounter(jdbc);
    private final Date day = Date.valueOf(LocalDate.of(2026, 11, 16));

    @Test
    void takesOneWhileUnderLimit() {
        when(jdbc.update(OdsayCallCounter.TAKE_ONE, day, 30)).thenReturn(1);
        assertThat(counter.take(LocalDate.of(2026, 11, 16), 30)).isTrue();
        verify(jdbc).update(OdsayCallCounter.ENSURE_DAY, day);
    }

    @Test
    void refusesAtLimit() {
        // 이미 30번이면 UPDATE 가 바꾼 줄이 0
        when(jdbc.update(OdsayCallCounter.TAKE_ONE, day, 30)).thenReturn(0);
        assertThat(counter.take(LocalDate.of(2026, 11, 16), 30)).isFalse();
    }
}
