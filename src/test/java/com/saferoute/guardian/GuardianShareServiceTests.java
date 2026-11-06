package com.saferoute.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GuardianShareServiceTests {

    static final String GUEST = "00000000-0000-4000-8000-00000000de02";
    static final String TOKEN = "Abcdefghijklmnopqrstuvwxyz012345";
    /** 2026-11-06 12:00 (한국 시각) */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 12, 0);

    final GuardianShareRepository repo = mock(GuardianShareRepository.class);
    final GuardianShareService service = new GuardianShareService(repo, CLOCK, "https://safe-route.java21.net/");

    static GuardianShare walking(LocalDateTime lastAt) {
        return new GuardianShare(TOKEN, GUEST, "엄마", "국립아시아문화전당", 35.1467, 126.9203, "senior",
                NOW.minusMinutes(20), NOW.minusMinutes(20).plusHours(3), 35.1480, 126.9180, lastAt, 300, 6, false,
                null, null);
    }

    @Test
    void startMakesRandomTokenLinkAndThreeHourLimit() {
        GuardianRequests.Started s = service.start(new GuardianRequests.Start(GUEST.toUpperCase(), "엄마",
                "국립아시아문화전당", 35.1467, 126.9203, "senior", 35.1480, 126.9180));
        assertThat(s.token()).matches("[A-Za-z0-9]{32}");
        assertThat(s.url()).isEqualTo("https://safe-route.java21.net/s/" + s.token());
        assertThat(s.expiresAt()).isEqualTo("2026-11-06T15:00:00");
        ArgumentCaptor<GuardianShare> row = ArgumentCaptor.forClass(GuardianShare.class);
        verify(repo).insert(row.capture());
        assertThat(row.getValue().guestId()).isEqualTo(GUEST);
        assertThat(row.getValue().remainM()).isBetween(200, 320);
        verify(repo).endOthers(GUEST, s.token(), NOW);
        assertThat(service.newToken()).isNotEqualTo(service.newToken());
    }

    @Test
    void startRejectsBadInput() {
        assertThatThrownBy(() -> service.start(new GuardianRequests.Start("abc", null, null, null, null, null, null,
                null))).hasMessageContaining("게스트 번호");
        assertThatThrownBy(() -> service.start(new GuardianRequests.Start(null, null, null, null, null, "flying",
                null, null))).hasMessageContaining("모드");
        assertThatThrownBy(() -> service.start(new GuardianRequests.Start(null, "가".repeat(31), null, null, null,
                null, null, null))).hasMessageContaining("30자");
        assertThatThrownBy(() -> service.start(new GuardianRequests.Start(null, null, null, 35.0, null, null, null,
                null))).hasMessageContaining("좌표");
        verify(repo, never()).insert(any());
    }

    @Test
    void moveOverwritesLastPointAndComputesRemain() {
        when(repo.find(TOKEN)).thenReturn(Optional.of(walking(NOW.minusSeconds(10))));
        when(repo.move(eq(TOKEN), anyDouble(), anyDouble(), any(), any(), any(), anyBoolean())).thenReturn(1);
        String st = service.move(TOKEN, new GuardianRequests.Location(35.1475, 126.9195, null, 3, false));
        assertThat(st).isEqualTo("walking");
        ArgumentCaptor<Integer> remain = ArgumentCaptor.forClass(Integer.class);
        verify(repo).move(eq(TOKEN), eq(35.1475), eq(126.9195), eq(NOW), remain.capture(), eq(3), eq(false));
        assertThat(remain.getValue()).isBetween(100, 160);
    }

    @Test
    void closeToDestinationEndsAsArrived() {
        when(repo.find(TOKEN)).thenReturn(Optional.of(walking(NOW.minusSeconds(10))));
        String st = service.move(TOKEN, new GuardianRequests.Location(35.14672, 126.92032, null, null, null));
        assertThat(st).isEqualTo("arrived");
        verify(repo).end(TOKEN, "arrived", NOW);
    }

    @Test
    void thirtyMinutesSilentMeansExpired() {
        when(repo.find(TOKEN)).thenReturn(Optional.of(walking(NOW.minusMinutes(31))));
        assertThatThrownBy(() -> service.move(TOKEN, new GuardianRequests.Location(35.1475, 126.9195, null, null,
                null))).isInstanceOf(GuardianException.class).hasMessageContaining("끝났어요");
        verify(repo).end(TOKEN, "expired", NOW);
    }

    @Test
    void endedShareRefusesLocation() {
        GuardianShare done = new GuardianShare(TOKEN, GUEST, "엄마", "집", null, null, null, NOW.minusHours(1),
                NOW.plusHours(2), null, null, NOW.minusMinutes(5), 0, 0, false, NOW.minusMinutes(5), "arrived");
        when(repo.find(TOKEN)).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.move(TOKEN, new GuardianRequests.Location(35.1, 126.9, null, null, null)))
                .extracting("code").isEqualTo("SHARE_ENDED");
    }

    @Test
    void viewHidesGuestAndShowsArrivedAfterEnd() {
        when(repo.find(TOKEN)).thenReturn(Optional.of(walking(NOW.minusSeconds(20))));
        GuardianRequests.View v = service.view(TOKEN);
        assertThat(v.status()).isEqualTo("walking");
        assertThat(v.lat()).isEqualTo(35.1480);
        assertThat(v.secondsAgo()).isEqualTo(20);

        GuardianShare done = new GuardianShare(TOKEN, GUEST, "엄마", "집", null, null, null, NOW.minusHours(1),
                NOW.plusHours(2), null, null, NOW.minusMinutes(2), 0, 0, false, NOW.minusMinutes(2), "arrived");
        when(repo.find(TOKEN)).thenReturn(Optional.of(done));
        GuardianRequests.View end = service.view(TOKEN);
        assertThat(end.status()).isEqualTo("arrived");
        assertThat(end.lat()).isNull();
        assertThat(end.endedAt()).isEqualTo("2026-11-06T11:58:00");
    }

    @Test
    void unknownOrBadTokenIsNotFound() {
        assertThatThrownBy(() -> service.view("short")).extracting("code").isEqualTo("SHARE_NOT_FOUND");
        when(repo.find(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.view(TOKEN)).extracting("code").isEqualTo("SHARE_NOT_FOUND");
    }

    @Test
    void endWithBadReasonIsRefusedAndSosIsKept() {
        assertThatThrownBy(() -> service.end(TOKEN, new GuardianRequests.End("party")))
                .hasMessageContaining("arrived");
        when(repo.find(TOKEN)).thenReturn(Optional.of(walking(NOW.minusSeconds(5))));
        service.end(TOKEN, new GuardianRequests.End("sos"));
        verify(repo).end(TOKEN, "sos", NOW);
    }

    @Test
    void cleanUpExpiresSilentAndDeletesTenMinutesAfterEnd() {
        when(repo.expire(NOW, NOW.minusMinutes(30))).thenReturn(2);
        when(repo.deleteEndedBefore(NOW.minusMinutes(10))).thenReturn(3);
        assertThat(service.cleanUp()).containsExactly(2, 3);
    }
}
