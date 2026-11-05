package com.saferoute.route.engine.explain;

import java.util.ArrayList;
import java.util.List;

import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.score.Coverage;
import com.saferoute.route.engine.score.Criterion;
import com.saferoute.route.engine.search.RoadPath;

/**
 * 경로 위 어두운 구간 (FR-31 "앞으로 150미터는 조명이 적습니다").
 * 경로를 고른 뒤 고른 경로에만 계산하고 경로 선택에는 쓰지 않는다. 조명 커버율과 같은 표본(10m)을
 * 경로 순서대로 이어, 가로등 범위 밖 표본이 이어진 곳을 구간으로 묶는다.
 */
final class DarkStretches {

    /** 출발부터 경로를 따라 잰 구간의 시작·끝(m) */
    record Stretch(double fromM, double toM) {
    }

    private DarkStretches() {
    }

    /**
     * @param startM    요청한 출발점에서 경로 첫 노드까지 거리. 시설 자리(atM)와 같은 기준으로 맞춘다
     * @param mergeGapM 어두운 표본 사이 밝은 틈이 이 길이 이하면 잇는다
     * @param minM      이은 뒤 이보다 짧은 구간은 버린다
     * @return 조명을 계산하지 않았으면(낮) 빈 목록
     */
    static List<Stretch> along(RoadPath path, double startM, Coverage cov, double mergeGapM, double minM) {
        if (path.edges().isEmpty()) {
            return List.of();
        }
        List<Double> pos = new ArrayList<>();
        List<Boolean> dark = new ArrayList<>();
        boolean[] fwd = path.forward();
        double base = startM;
        for (int ei = 0; ei < path.edges().size(); ei++) {
            RoadEdge e = path.edges().get(ei);
            Coverage.Sampled s = cov.sampled(e, Criterion.LIGHT);
            if (s == null) {
                return List.of();
            }
            boolean forward = fwd[ei];
            int n = s.offsetM().length;
            for (int i = 0; i < n; i++) {
                int k = forward ? i : n - 1 - i;
                pos.add(base + (forward ? s.offsetM()[k] : e.lengthM() - s.offsetM()[k]));
                dark.add(!s.inside()[k]);
            }
            base += e.lengthM();
        }
        double[] p = pos.stream().mapToDouble(Double::doubleValue).toArray();
        boolean[] d = new boolean[dark.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = dark.get(i);
        }
        return runs(p, d, mergeGapM, minM);
    }

    /**
     * 표본 자리(오름차순)와 어두운지로 구간을 만든다. 표본 하나는 앞뒤 표본과의 가운데까지를 덮는다.
     * 1. 어두운 표본이 이어진 곳을 구간으로 묶고 2. 밝은 틈이 mergeGapM 이하면 잇고 3. minM보다 짧으면 버린다
     */
    static List<Stretch> runs(double[] pos, boolean[] dark, double mergeGapM, double minM) {
        List<Stretch> raw = new ArrayList<>();
        int i = 0;
        while (i < pos.length) {
            if (!dark[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < pos.length && dark[j + 1]) {
                j++;
            }
            raw.add(new Stretch(edgeBefore(pos, i), edgeAfter(pos, j)));
            i = j + 1;
        }
        List<Stretch> merged = new ArrayList<>();
        for (Stretch s : raw) {
            Stretch last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            // 반올림 차이로 10m 틈이 10.0001m가 되지 않게 조금 여유를 둔다
            if (last != null && s.fromM() - last.toM() <= mergeGapM + 1e-6) {
                merged.set(merged.size() - 1, new Stretch(last.fromM(), s.toM()));
            } else {
                merged.add(s);
            }
        }
        return merged.stream().filter(s -> s.toM() - s.fromM() >= minM - 1e-6).toList();
    }

    private static double edgeBefore(double[] pos, int i) {
        double half = (i > 0 ? pos[i] - pos[i - 1] : pos.length > 1 ? pos[1] - pos[0] : 0) / 2;
        return Math.max(0, pos[i] - half);
    }

    private static double edgeAfter(double[] pos, int j) {
        return pos[j] + (j + 1 < pos.length ? pos[j + 1] - pos[j] : j > 0 ? pos[j] - pos[j - 1] : 0) / 2;
    }
}
