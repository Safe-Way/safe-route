package com.saferoute.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 범위 검사와 한국 시각 (DB 없이) */
class EventServiceTests {

    private final EventRepository repo = mock(EventRepository.class);
    private final EventService service = new EventService(repo,
            Clock.fixed(Instant.parse("2026-11-06T01:00:00Z"), ZoneOffset.UTC));

    @Test
    void asksTableWithKoreanNow() {
        RoadEvent e = new RoadEvent(7, "행사", null, "금남로 차 없는 거리 걷자잉", null, 35.149, 126.917, 300, null, null,
                null, null);
        when(repo.active(126.9, 35.1, 127.0, 35.2, LocalDateTime.parse("2026-11-06T10:00"), 300)).thenReturn(List.of(e));
        assertThat(service.within(126.9, 35.1, 127.0, 35.2)).containsExactly(e);
    }

    @Test
    void bboxMustBeInOrderAndSmall() {
        assertThatThrownBy(() -> service.within(127, 35, 126, 36)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.within(126, 35, 127, 36)).isInstanceOf(IllegalArgumentException.class);
    }
}
