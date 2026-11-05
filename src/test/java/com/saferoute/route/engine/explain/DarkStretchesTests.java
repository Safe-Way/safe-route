package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 어두운 구간 묶기 (FR-31): 밝은 틈 10m 이하는 잇고, 50m 미만은 버린다 */
class DarkStretchesTests {

    /** 10m 간격 표본 (자리 5, 15, 25, ...). 'x' = 어두움 */
    static double[] pos(String pattern) {
        double[] p = new double[pattern.length()];
        for (int i = 0; i < p.length; i++) {
            p[i] = 5 + 10 * i;
        }
        return p;
    }

    static boolean[] dark(String pattern) {
        boolean[] d = new boolean[pattern.length()];
        for (int i = 0; i < d.length; i++) {
            d[i] = pattern.charAt(i) == 'x';
        }
        return d;
    }

    static java.util.List<DarkStretches.Stretch> runs(String pattern) {
        return DarkStretches.runs(pos(pattern), dark(pattern), 10, 50);
    }

    @Test
    void shortLitGapsAreJoinedAndShortDarkIsDropped() {
        // xoxox는 밝은 틈이 10m씩이라 이어 20~70m 한 구간, 끝의 x 하나(10m)는 버림
        assertThat(runs("ooxoxoxooox")).containsExactly(new DarkStretches.Stretch(20, 70));
    }

    @Test
    void longerLitGapSplitsAndBothHalvesAreTooShort() {
        // 어두움 20m, 밝음 20m, 어두움 20m → 잇지 않고, 둘 다 50m 미만
        assertThat(runs("xxooxx")).isEmpty();
    }

    @Test
    void wholeDarkRouteIsOneStretch() {
        assertThat(runs("xxxxxxxx")).containsExactly(new DarkStretches.Stretch(0, 80));
        assertThat(runs("oooooooo")).isEmpty();
    }
}
