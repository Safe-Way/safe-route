package com.saferoute.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MeServiceTests {

    static final String GUEST = "00000000-0000-4000-8000-00000000de05";
    /** 2026-11-06 (금) 13:47 한국 시각 */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T04:47:00Z"), ZoneId.of("Asia/Seoul"));
    static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    final MeRepository repo = mock(MeRepository.class);
    final MeService service = new MeService(repo, CLOCK);

    static MeRepository.Row row(boolean consent) {
        return new MeRepository.Row("senior", "cane", false, true, false, "senior", "cane", 1, 1.2, 300, 2, null,
                null, null, null, consent);
    }

    static MeRequests.Update update(String mode, String aid, Integer restEveryM, Boolean consent, String tempMode,
                                    String tempUntil, Boolean clearTemp) {
        return new MeRequests.Update(GUEST, mode, aid, null, null, restEveryM, null, consent, tempMode, null,
                tempMode == null ? null : "다리 다침", tempUntil, clearTemp, null);
    }

    @Test
    void unknownGuestGetsEmptyViewThatAsksSetup() {
        when(repo.find(GUEST)).thenReturn(Optional.empty());
        MeRequests.View v = service.view(GUEST.toUpperCase());
        assertThat(v.known()).isFalse();
        assertThat(v.askToday()).isTrue();
        assertThat(v.layers()).isEmpty();
    }

    @Test
    void knownGuestShowsProfileTripsAndLayers() {
        when(repo.find(GUEST)).thenReturn(Optional.of(row(true)));
        when(repo.tripCount(GUEST)).thenReturn(12);
        when(repo.layers(GUEST)).thenReturn(List.of(new MeRequests.Layer("shelter", "쉼터", true, null, null)));
        MeRequests.View v = service.view(GUEST);
        assertThat(v.known()).isTrue();
        assertThat(v.modeNow()).isEqualTo("senior");
        assertThat(v.restEveryM()).isEqualTo(300);
        assertThat(v.trips()).isEqualTo(12);
        assertThat(v.layers()).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void updateSavesOnlySentFieldsAndRestInterval() {
        when(repo.find(GUEST)).thenReturn(Optional.of(row(true)));
        service.update(update("Senior", "none", 300, true, null, null, null));
        ArgumentCaptor<Map<String, Object>> f = ArgumentCaptor.forClass(Map.class);
        verify(repo).save(eq(GUEST), f.capture());
        assertThat(f.getValue()).containsEntry("mode", "senior").containsEntry("rest_every_m", 300)
                .containsEntry("mobility_aid", null).containsEntry("consent_trips", 1)
                .containsKey("consent_at").doesNotContainKey("stairs_ok").doesNotContainKey("temp_mode");
        verify(repo, never()).deleteTrips(anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void tempModeWithoutEndDateLastsSevenDaysAndAsksAgain() {
        when(repo.find(GUEST)).thenReturn(Optional.of(row(true)));
        service.update(update(null, null, null, null, "wheelchair", null, null));
        ArgumentCaptor<Map<String, Object>> f = ArgumentCaptor.forClass(Map.class);
        verify(repo).save(eq(GUEST), f.capture());
        assertThat(f.getValue()).containsEntry("temp_mode", "wheelchair").containsEntry("temp_reason", "다리 다침")
                .containsEntry("temp_until", TODAY.plusDays(7)).containsEntry("next_ask_on", TODAY.plusDays(7));
    }

    @SuppressWarnings("unchecked")
    @Test
    void clearTempEmptiesAllTempFields() {
        when(repo.find(GUEST)).thenReturn(Optional.of(row(true)));
        service.update(update(null, null, null, null, null, null, true));
        ArgumentCaptor<Map<String, Object>> f = ArgumentCaptor.forClass(Map.class);
        verify(repo).save(eq(GUEST), f.capture());
        assertThat(f.getValue()).containsKeys("temp_mode", "temp_aid", "temp_reason", "temp_until", "next_ask_on")
                .allSatisfy((k, v) -> assertThat(v).isNull());
    }

    @Test
    void turningConsentOffDeletesTrips() {
        when(repo.find(GUEST)).thenReturn(Optional.of(row(false)));
        service.update(update(null, null, null, false, null, null, null));
        verify(repo).deleteTrips(GUEST);
    }

    @Test
    void updateRejectsBadValues() {
        assertThatThrownBy(() -> service.update(update("flying", null, null, null, null, null, null)))
                .hasMessageContaining("모드");
        assertThatThrownBy(() -> service.update(update(null, "skateboard", null, null, null, null, null)))
                .hasMessageContaining("이동 보조");
        assertThatThrownBy(() -> service.update(update(null, null, 10, null, null, null, null)))
                .hasMessageContaining("쉬는 간격");
        assertThatThrownBy(() -> service.update(update(null, null, null, null, "wheelchair", "2026-11-01", null)))
                .hasMessageContaining("1년 안");
        assertThatThrownBy(() -> service.update(update(null, null, null, null, "wheelchair", "다음주", null)))
                .hasMessageContaining("2026-11-20");
        assertThatThrownBy(() -> service.update(new MeRequests.Update("abc", null, null, null, null, null, null,
                null, null, null, null, null, null, null))).hasMessageContaining("게스트 번호");
        verify(repo, never()).save(anyString(), any());
    }

    @Test
    void tripNeedsConsent() {
        when(repo.find(GUEST)).thenReturn(Optional.of(row(false)));
        MeRequests.TripStart t = new MeRequests.TripStart(GUEST, "남광주시장", 35.13849, 126.92876, "senior",
                "walk", "safe", true, "rain", false, 15, null);
        assertThatThrownBy(() -> service.startTrip(t)).extracting("code").isEqualTo("NO_CONSENT");
        when(repo.find(GUEST)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.startTrip(t)).extracting("code").isEqualTo("NO_CONSENT");
        verify(repo, never()).insertTrip(anyString(), any(), any(Integer.class), any(), any());
    }

    @Test
    void tripRoundsDestinationAndUsesThirtyMinuteSlot() {
        when(repo.find(GUEST)).thenReturn(Optional.of(row(true)));
        when(repo.insertTrip(eq(GUEST), any(), any(Integer.class), any(), any())).thenReturn(7L);
        MeRequests.TripStarted s = service.startTrip(new MeRequests.TripStart(GUEST, "남광주시장", 35.13849,
                126.92876, "senior", "transit", "bus", true, "dust, Rain,clear", false, 15, "다리 아픔"));
        assertThat(s.id()).isEqualTo(7L);
        assertThat(s.startSlot()).isEqualTo("13:30");
        ArgumentCaptor<MeRepository.Trip> trip = ArgumentCaptor.forClass(MeRepository.Trip.class);
        verify(repo).insertTrip(eq(GUEST), eq(TODAY), eq(5), eq(LocalTime.of(13, 30)), trip.capture());
        assertThat(trip.getValue().toLat()).isEqualTo(35.138);
        assertThat(trip.getValue().toLon()).isEqualTo(126.929);
        assertThat(trip.getValue().weather()).isEqualTo("dust,rain");
        verify(repo).trimTrips(GUEST);
    }

    @Test
    void tripRejectsBadValues() {
        assertThatThrownBy(() -> service.startTrip(new MeRequests.TripStart(GUEST, "집", 10.0, 126.9, "senior",
                "walk", null, null, null, null, null, null))).hasMessageContaining("우리나라");
        assertThatThrownBy(() -> service.startTrip(new MeRequests.TripStart(GUEST, "집", 35.1, 126.9, "senior",
                "taxi", null, null, null, null, null, null))).hasMessageContaining("travel");
        assertThatThrownBy(() -> service.startTrip(new MeRequests.TripStart(GUEST, "집", 35.1, 126.9, "senior",
                "walk", null, null, "snow", null, null, null))).hasMessageContaining("날씨");
    }

    @Test
    void endTripChecksOwnerAndDropsTimeWhenStopped() {
        when(repo.endTrip(GUEST, 7L, "stopped", null, "bad", "계단")).thenReturn(1);
        service.endTrip(7L, new MeRequests.TripEnd(GUEST, "stopped", 20, "bad", "계단"));
        verify(repo).endTrip(GUEST, 7L, "stopped", null, "bad", "계단");
        when(repo.endTrip(eq(GUEST), eq(8L), anyString(), any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.endTrip(8L, new MeRequests.TripEnd(GUEST, "arrived", 12, null, null)))
                .extracting("code").isEqualTo("TRIP_NOT_FOUND");
        assertThatThrownBy(() -> service.endTrip(7L, new MeRequests.TripEnd(GUEST, "lost", null, null, null)))
                .hasMessageContaining("result");
    }

    @Test
    void layerNeedsShownAndGoodName() {
        assertThatThrownBy(() -> service.setLayer("shelter", new MeRequests.LayerSet(GUEST, null, null, null)))
                .hasMessageContaining("shown");
        assertThatThrownBy(() -> service.setLayer("x; DROP", new MeRequests.LayerSet(GUEST, true, null, null)))
                .hasMessageContaining("모양");
        service.setLayer("Toilet:Wheelchair", new MeRequests.LayerSet(GUEST, true, "2026-11-20", "다리 다침"));
        verify(repo).ensure(GUEST);
        verify(repo).saveLayer(GUEST, "toilet:wheelchair", true, LocalDate.of(2026, 11, 20), "다리 다침");
    }

    @Test
    void deleteAllPassesNormalizedGuest() {
        when(repo.deleteAll(GUEST)).thenReturn(new MeRequests.Deleted(true, 12, 2, 1, 0));
        assertThat(service.deleteAll(GUEST.toUpperCase()).trips()).isEqualTo(12);
        verify(repo, never()).deleteTrips(anyString());
        verify(repo, never()).endTrip(anyString(), anyLong(), anyString(), any(), any(), any());
    }
}
