package com.saferoute.transit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 11-13 버스+지하철 같이 타는 길도 후보에: 앞 3개에 없으면 마지막 자리를 그 길로 */
class MixedPathTests {

    static OdsayPath path(int min, int... types) {
        List<OdsayPath.Seg> segs = new java.util.ArrayList<>();
        for (int t : types) {
            segs.add(new OdsayPath.Seg(t, 100, 1, null, null, List.of(), null, 0));
        }
        return new OdsayPath(min, 1250, segs);
    }

    static final int W = 3;
    static final int S = OdsayPath.SUBWAY;
    static final int B = OdsayPath.BUS;

    @Test
    void mixedPathTakesLastSeatWhenTopThreeHaveNone() {
        OdsayPath bus1 = path(20, W, B, W);
        OdsayPath bus2 = path(22, W, B, W);
        OdsayPath bus3 = path(24, W, B, W);
        OdsayPath mixed = path(25, W, B, W, S, W);
        assertThat(TransitService.pick(List.of(bus1, bus2, bus3, mixed))).containsExactly(bus1, bus2, mixed);
    }

    @Test
    void keepsOrderWhenMixedIsAlreadyIn() {
        OdsayPath mixed = path(18, W, B, W, S, W);
        OdsayPath bus1 = path(20, W, B, W);
        OdsayPath bus2 = path(22, W, B, W);
        OdsayPath sub = path(24, W, S, W);
        assertThat(TransitService.pick(List.of(mixed, bus1, bus2, sub))).containsExactly(mixed, bus1, bus2);
    }

    @Test
    void noMixedAnywhereKeepsTopThree() {
        OdsayPath a = path(20, W, B, W);
        OdsayPath b = path(21, W, S, W);
        OdsayPath c = path(22, W, B, W);
        OdsayPath d = path(23, W, B, W);
        assertThat(TransitService.pick(List.of(a, b, c, d))).containsExactly(a, b, c);
        assertThat(TransitService.pick(List.of(a))).containsExactly(a);
    }
}
