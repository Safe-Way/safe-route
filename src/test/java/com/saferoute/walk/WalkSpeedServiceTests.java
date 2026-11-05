package com.saferoute.walk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WalkSpeedServiceTests {

    static final String GUEST = "3F2504E0-4F89-11D3-9A0C-0305E82C3301";
    static final String GUEST_LOWER = GUEST.toLowerCase();
    static final Map<String, Double> BASE = Map.of("adult", 80.0, "child", 70.0, "senior", 60.0, "wheelchair", 50.0);

    private final WalkSpeedRepository repo = mock(WalkSpeedRepository.class);
    // 한국 시각 11/06 00:30 = UTC 11/05 15:30 → 날짜는 11/06 이어야 함
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    private final WalkSpeedService service = new WalkSpeedService(repo, BASE::get, clock);

    @Test
    void blendFollowsTeamExample() {
        // 노인(기본 60)이 매번 45로 걸으면 1번 57.5 → 5번 52.5 → 10번 50 → 20번 48
        assertThat(WalkSpeedService.blend(60, 45, 1)).isEqualTo(57.5);
        assertThat(WalkSpeedService.blend(60, 45 * 5, 5)).isEqualTo(52.5);
        assertThat(WalkSpeedService.blend(60, 45 * 10, 10)).isEqualTo(50.0);
        assertThat(WalkSpeedService.blend(60, 45 * 20, 20)).isEqualTo(48.0);
    }

    @Test
    void addSavesSampleAndReturnsBlendedSpeed() {
        when(repo.sampleSum(GUEST_LOWER, "senior")).thenReturn(new WalkSpeedRepository.SampleSum(1, 45.0));
        when(repo.find(GUEST_LOWER, "senior")).thenReturn(new WalkSpeedRepository.Profile(57.5, 1));

        // 450m 를 600초(10분)에 → 분당 45m
        WalkSpeed w = service.add(new WalkSample(GUEST, "Senior", 450, 600));

        assertThat(w).isEqualTo(new WalkSpeed("senior", 57.5, 60.0, 1, true));
        InOrder order = inOrder(repo);
        order.verify(repo).lockProfile(GUEST_LOWER, "senior");
        order.verify(repo).insertSample(GUEST_LOWER, "senior", 450, 600, 45.0, LocalDate.of(2026, 11, 6));
        order.verify(repo).trimSamples(GUEST_LOWER, "senior", 20);
        order.verify(repo).updateProfile(GUEST_LOWER, "senior", 57.5, 450, 600);
    }

    @Test
    void blankModeIsAdult() {
        when(repo.sampleSum(anyString(), anyString())).thenReturn(new WalkSpeedRepository.SampleSum(1, 90.0));
        WalkSpeed w = service.add(new WalkSample(GUEST, null, 900, 600));
        assertThat(w.mode()).isEqualTo("adult");
        assertThat(w.speedMMin()).isEqualTo(81.7); // (80×5 + 90) ÷ 6 = 81.67
    }

    @Test
    void rejectsShortWalk() {
        assertBad(new WalkSample(GUEST, "adult", 299, 200), "300m");
    }

    @Test
    void rejectsTooFastOrTooSlow() {
        assertBad(new WalkSample(GUEST, "adult", 3000, 600), "20~150");  // 분당 300m: 버스
        assertBad(new WalkSample(GUEST, "adult", 300, 1200), "20~150");  // 분당 15m
    }

    @Test
    void rejectsBadGuestModeAndTime() {
        assertBad(new WalkSample("010-1234-5678", "adult", 500, 400), "guestId");
        assertBad(new WalkSample(null, "adult", 500, 400), "guestId");
        assertBad(new WalkSample(GUEST, "runner", 500, 400), "mode");
        assertBad(new WalkSample(GUEST, "adult", 500, 0), "movingS");
        assertBad(new WalkSample(GUEST, "adult", null, 400), "distanceM");
        verify(repo, never()).insertSample(anyString(), anyString(), anyInt(), anyInt(), anyDouble(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getWithoutRecordGivesModeBase() {
        assertThat(service.get(GUEST, "wheelchair")).isEqualTo(new WalkSpeed("wheelchair", 50.0, 50.0, 0, false));
    }

    @Test
    void getWithRecordGivesSavedSpeed() {
        when(repo.find(GUEST_LOWER, "child")).thenReturn(new WalkSpeedRepository.Profile(66.2, 7));
        assertThat(service.get(GUEST, "child")).isEqualTo(new WalkSpeed("child", 66.2, 70.0, 7, true));
    }

    @Test
    void deleteRemovesAllModes() {
        when(repo.deleteGuest(GUEST_LOWER)).thenReturn(2);
        assertThat(service.delete(GUEST)).isEqualTo(2);
    }

    private void assertBad(WalkSample s, String hint) {
        assertThatThrownBy(() -> service.add(s))
                .isInstanceOf(WalkSpeedException.class)
                .hasMessageContaining(hint);
    }
}
