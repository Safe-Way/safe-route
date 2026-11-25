package com.saferoute.route.engine.explain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.RouteTestProps;

/** 어두운 구간 묶기 (FR-31): 밝은 틈 10m 이하는 잇고, 50m 미만은 버린다 */
@DisplayName("어두운 구간 묶기")
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
    @DisplayName("짧은 밝은 틈은 잇고 짧은 어두운 구간은 버린다")
    void shortLitGapsAreJoinedAndShortDarkIsDropped() {
        // xoxox는 밝은 틈이 10m씩이라 이어 20~70m 한 구간, 끝의 x 하나(10m)는 버림
        assertThat(runs("ooxoxoxooox")).containsExactly(new DarkStretches.Stretch(20, 70));
    }

    @Test
    @DisplayName("밝은 틈이 길면 나누고 나뉜 짧은 구간은 버린다")
    void longerLitGapSplitsAndBothHalvesAreTooShort() {
        // 어두움 20m, 밝음 20m, 어두움 20m → 잇지 않고, 둘 다 50m 미만
        assertThat(runs("xxooxx")).isEmpty();
    }

    @Test
    @DisplayName("전체가 어두우면 처음부터 끝까지 한 구간이고 모두 밝으면 구간이 없다")
    void wholeDarkRouteIsOneStretch() {
        // FR-31
        assertThat(runs("xxxxxxxx")).containsExactly(new DarkStretches.Stretch(0, 80));
        assertThat(runs("oooooooo")).isEmpty();
    }

    @Test
    @DisplayName("이은 어두운 구간이 50m 이상이면 남기고 짧으면 버린다")
    void darkStretchOfExactly50mIsKept() {
        // dark-stretch.min-m (route.yml dark-stretch, FR-31)
        RouteProperties.DarkStretch ds = RouteTestProps.load().darkStretch();
        double minM = ds.minM();
        // 표본 다섯이 모두 어두우면 구간 길이 = 간격 × 5. 경계값 그대로면 남고, 경계 0.01m 아래면 버린다
        boolean[] dark = {true, true, true, true, true};
        assertThat(DarkStretches.runs(spaced(minM / 5), dark, ds.mergeGapM(), minM))
                .containsExactly(new DarkStretches.Stretch(0, minM));
        assertThat(DarkStretches.runs(spaced((minM - 0.01) / 5), dark, ds.mergeGapM(), minM)).isEmpty();
    }

    @Test
    @DisplayName("어두운 구간 사이 밝은 틈이 10m 이하면 잇는다")
    void litGapOfExactlyMergeGapIsJoined() {
        // dark-stretch.merge-gap-m (route.yml dark-stretch, DarkStretches.runs)
        double gap = RouteTestProps.load().darkStretch().mergeGapM();
        // 표본 0·gap·c 중 가운데만 밝으면 틈은 c/2 (표본은 앞뒤 표본과의 가운데까지 덮음). 버리기는 따로 보므로 min 0
        boolean[] dark = {true, false, true};
        assertThat(DarkStretches.runs(new double[] {0, gap, 2 * gap}, dark, gap, 0))
                .containsExactly(new DarkStretches.Stretch(0, 2.5 * gap));
        assertThat(DarkStretches.runs(new double[] {0, gap, 2 * (gap + 0.01)}, dark, gap, 0)).hasSize(2);
    }

    /** 표본 다섯, 간격 step, 첫 표본은 step/2 */
    static double[] spaced(double step) {
        double[] p = new double[5];
        for (int i = 0; i < p.length; i++) {
            p[i] = step / 2 + step * i;
        }
        return p;
    }
}
