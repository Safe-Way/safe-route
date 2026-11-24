package com.saferoute.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.saferoute.me.PreferenceLearner.Done;

/** 다닌 기록으로 대중교통·계단 선호 배우기 */
class PreferenceLearnerTests {

    static final Done BUS = new Done("transit", "arrived", null, null);
    static final Done WALK = new Done("walk", "arrived", null, null);
    static final Done STAIRS = new Done("walk", "arrived", "bad", "계단이 많았음");

    static List<Done> list(Done... ds) {
        return List.of(ds);
    }

    @Test
    void mostlyTransitMeansLikesTransit() {
        List<MeRequests.Learned> l = PreferenceLearner.learn(list(BUS, BUS, BUS, BUS, WALK), BUS, 1, null);
        assertThat(l).extracting(MeRequests.Learned::what, MeRequests.Learned::to).containsExactly(
                org.assertj.core.groups.Tuple.tuple("preferTransit", 2));
        assertThat(l.get(0).say()).contains("대중교통");
    }

    @Test
    void mostlyWalkingMeansLikesWalking() {
        List<MeRequests.Learned> l = PreferenceLearner.learn(list(WALK, WALK, WALK, WALK, BUS, WALK), WALK, null, null);
        assertThat(l.get(0).to()).isZero();
        assertThat(l.get(0).from()).isNull();
    }

    @Test
    void mixedIsNormalAndSameValueChangesNothing() {
        assertThat(PreferenceLearner.learn(list(WALK, BUS, WALK, BUS, WALK, BUS), BUS, 0, null).get(0).to())
                .isEqualTo(1);
        assertThat(PreferenceLearner.learn(list(WALK, BUS, WALK, BUS, WALK, BUS), BUS, 1, null)).isEmpty();
    }

    @Test
    void tooFewTripsLearnNothing() {
        assertThat(PreferenceLearner.learn(list(BUS, BUS, BUS, BUS), BUS, 0, null)).isEmpty();
        // 그만둔 기록은 대중교통 비율에 넣지 않음
        Done stopped = new Done("transit", "stopped", null, null);
        assertThat(PreferenceLearner.learn(list(BUS, BUS, BUS, BUS, stopped), BUS, 0, null)).isEmpty();
    }

    @Test
    void stairsComplaintsLowerOneStepAtATime() {
        List<MeRequests.Learned> l = PreferenceLearner.learn(list(STAIRS, WALK, STAIRS), STAIRS, null, 2);
        assertThat(l).singleElement().satisfies(x -> {
            assertThat(x.what()).isEqualTo("stairsOk");
            assertThat(x.to()).isEqualTo(1);
            assertThat(x.say()).contains("계단이 적은 길");
        });
        assertThat(PreferenceLearner.learn(list(STAIRS, STAIRS), STAIRS, null, 1).get(0).say())
                .contains("계단 없는 길");
        assertThat(PreferenceLearner.learn(list(STAIRS, STAIRS), STAIRS, null, null).get(0).to()).isEqualTo(1);
    }

    @Test
    void stairsNeverGoBelowZeroOrUpAndNeedTwoComplaints() {
        assertThat(PreferenceLearner.learn(list(STAIRS, STAIRS), STAIRS, null, 0)).isEmpty();
        assertThat(PreferenceLearner.learn(list(STAIRS, WALK), STAIRS, null, 2)).isEmpty();
        // 방금 끝난 이동에 계단 불편이 없으면 옛 불편만으로 또 낮추지 않음
        assertThat(PreferenceLearner.learn(list(WALK, STAIRS, STAIRS), WALK, null, 1)).isEmpty();
    }

    @Test
    void serviceSavesLearnedValues() {
        MeRepository repo = mock(MeRepository.class);
        MeService service = new MeService(repo, Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"),
                ZoneId.of("Asia/Seoul")));
        String g = MeServiceTests.GUEST;
        when(repo.endTrip(g, 5L, "arrived", 12, null, null)).thenReturn(1);
        when(repo.find(g)).thenReturn(Optional.of(MeServiceTests.row(true))); // prefer_transit 2
        List<Done> walks = new ArrayList<>(Collections.nCopies(6, WALK));
        when(repo.recentDone(g, PreferenceLearner.RECENT)).thenReturn(walks);

        MeRequests.TripEnded r = service.endTrip(5L, new MeRequests.TripEnd(g, "arrived", 12, null, null));
        assertThat(r.learned()).extracting(MeRequests.Learned::to).containsExactly(0);
        verify(repo).save(g, Map.of("prefer_transit", 0));

        // 같은 프로필(prefer_transit 2)이라도 끝난 기록이 1건뿐이면 배우지 않는다.
        // 가짜 응답을 quiet에 걸어야 한다 (repo에 걸면 quiet는 기록·프로필이 없어서 저절로 통과)
        MeRepository quiet = mock(MeRepository.class);
        when(quiet.endTrip(g, 5L, "arrived", 12, null, null)).thenReturn(1);
        when(quiet.find(g)).thenReturn(Optional.of(MeServiceTests.row(true)));
        when(quiet.recentDone(g, PreferenceLearner.RECENT)).thenReturn(List.of(WALK));
        new MeService(quiet, Clock.systemUTC()).endTrip(5L, new MeRequests.TripEnd(g, "arrived", 12, null, null));
        verify(quiet, never()).save(anyString(), anyMap());
    }
}
