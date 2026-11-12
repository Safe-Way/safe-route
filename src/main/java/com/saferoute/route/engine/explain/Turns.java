package com.saferoute.route.engine.explain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.Penalty;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.search.RoadPath;

/**
 * 경로의 꺾는 곳 (FR-34 "80m 앞 왼쪽"). 경로를 고른 뒤 고른 경로에만 계산한다.
 * <ul>
 *   <li>경로 선의 모든 꺾임점을 본다 (요구사항 FR-34 "경로 선에서 방향이 45° 넘게 바뀌는 곳"). 처음에는 갈림길에서만 봤는데,
 *       OSM에서 한 줄로 휘어지는 큰길 모퉁이(동구청 → 충장로, 파랑새안과의원 맞은편 68°)를 놓쳤다</li>
 *   <li>들어오는 방향과 나가는 방향을 경로 선을 따라 갈림길 앞뒤 lookM 구간으로 잰다. 길 하나 안에서만 재면
 *       4m짜리 길(인도에서 횡단보도로 옆걸음) 때문에 거의 직진인 곳이 90° 꺾음으로 나왔다 (조선대 장지연 → 중앙도서관)</li>
 *   <li>방향이 minAngle도 넘게 바뀌면 꺾는 곳이다. 갈림길 점이 몇 m 간격으로 몰린 곳은 한 번 꺾는 것을 점마다 잡으므로,
 *       lookM 안에서 같은 방향으로 잡힌 꺾음은 가장 크게 꺾는 하나만 남긴다</li>
 *   <li>꺾는 곳은 하나도 빼지 않는다. 다음 꺾는 곳이 linkM 안에 이어지면 그 거리(thenM)를, 꺾은 뒤 횡단보도를 지나면
 *       crossing을 붙여 앱이 "오른쪽 횡단보도를 건너 왼쪽"처럼 한 번에 말하게 한다. 인도 → 횡단보도 → 건너편 인도처럼
 *       12m쯤 사이로 꺾는 곳이 광주 실제 경로에 많았고, 이를 지우면 길을 건너야 한다는 안내가 사라진다</li>
 * </ul>
 */
final class Turns {

    /**
     * @param atM      출발부터 경로를 따라 꺾는 곳까지 거리(m). 시설 along.atM과 같은 기준
     * @param left     왼쪽으로 꺾으면 true
     * @param angle    방향이 바뀌는 정도(도, 0~180)
     * @param crossing 꺾은 직후 linkM 안에서 횡단보도가 시작되면 true ("오른쪽 횡단보도를 건너세요")
     * @param thenM    다음 꺾는 곳이 linkM 안에 이어지면 그 거리(m), 아니면 null
     */
    record Turn(double atM, boolean left, double angle, boolean crossing, Double thenM) {
    }

    private Turns() {
    }

    /** @param startM 요청한 출발점에서 경로 첫 노드까지 거리 */
    static List<Turn> along(RoadPath path, double startM, double minAngle, double lookM, double linkM) {
        List<RoadEdge> edges = path.edges();
        if (edges.isEmpty()) {
            return List.of();
        }
        // 걸어가는 방향으로 이은 경로 선과, 점마다 그 점 다음으로 가는 길 번호
        boolean[] fwd = path.forward();
        List<double[]> line = new ArrayList<>();
        List<Integer> edgeOf = new ArrayList<>();
        for (int e = 0; e < edges.size(); e++) {
            List<double[]> g = new ArrayList<>(edges.get(e).geometry());
            if (!fwd[e]) {
                Collections.reverse(g);
            }
            for (int i = line.isEmpty() ? 0 : 1; i < g.size(); i++) {
                line.add(g.get(i));
                edgeOf.add(e);
            }
        }
        double[] cum = new double[line.size()];
        for (int i = 1; i < line.size(); i++) {
            cum[i] = cum[i - 1] + Geo.distance(line.get(i - 1), line.get(i));
        }
        // 1. 꺾임점마다 앞뒤 lookM으로 잰 방향 변화 {atM, 부호 있는 각도, 꺾은 뒤 길 번호}
        List<double[]> raw = new ArrayList<>();
        for (int k = 1; k + 1 < line.size(); k++) {
            double[] p = line.get(k);
            double turn = normalize(heading(p, walk(line, k, lookM, +1)) - heading(walk(line, k, lookM, -1), p));
            if (Math.abs(turn) > minAngle) {
                raw.add(new double[] {startM + cum[k], turn, edgeOf.get(k + 1)});
            }
        }
        // 2. 한 번 꺾는 것을 이웃한 점들이 같이 잡으므로, lookM 안에서 같은 방향이면 가장 큰 하나만
        List<double[]> kept = new ArrayList<>();
        for (double[] r : raw) {
            double[] last = kept.isEmpty() ? null : kept.get(kept.size() - 1);
            if (last != null && r[0] - last[0] <= lookM && Math.signum(r[1]) == Math.signum(last[1])) {
                if (Math.abs(r[1]) > Math.abs(last[1])) {
                    kept.set(kept.size() - 1, r);
                }
            } else {
                kept.add(r);
            }
        }
        // 3. 꺾은 직후 횡단보도가 시작되는지, 다음 꺾는 곳이 가까이 이어지는지
        double[] edgeStart = new double[edges.size()];
        double run = startM;
        for (int e = 0; e < edges.size(); e++) {
            edgeStart[e] = run;
            run += edges.get(e).lengthM();
        }
        List<Turn> out = new ArrayList<>();
        for (int k = 0; k < kept.size(); k++) {
            double atM = kept.get(k)[0];
            boolean crossing = false;
            for (int e = (int) kept.get(k)[2]; e < edges.size() && edgeStart[e] - atM <= linkM; e++) {
                crossing |= edges.get(e).penalty().get(Penalty.CROSSWALK) >= 1;
            }
            Double then = k + 1 < kept.size() && kept.get(k + 1)[0] - atM <= linkM ? kept.get(k + 1)[0] - atM : null;
            double turn = kept.get(k)[1];
            // 방위각은 북쪽 0°, 시계 방향으로 커진다. 음수면 반시계 = 왼쪽
            out.add(new Turn(atM, turn < 0, Math.abs(turn), crossing, then));
        }
        return out;
    }

    /** 선의 k번째 점에서 dir(+1 앞, −1 뒤) 쪽으로 선을 따라 lookM 간 점. 선 끝에 닿으면 끝 점 */
    static double[] walk(List<double[]> line, int k, double lookM, int dir) {
        double left = lookM;
        for (int i = k; i + dir >= 0 && i + dir < line.size(); i += dir) {
            double[] a = line.get(i);
            double[] b = line.get(i + dir);
            double seg = Geo.distance(a, b);
            if (seg >= left) {
                return lerp(a, b, seg == 0 ? 0 : left / seg);
            }
            left -= seg;
        }
        return line.get(dir > 0 ? line.size() - 1 : 0);
    }

    /** 경로에서 횡단보도 길이 이어진 곳 (출발부터 m). 꺾지 않고 곧장 건너는 횡단보도도 알리려는 것 */
    static List<double[]> crossings(RoadPath path, double startM) {
        List<double[]> out = new ArrayList<>();
        double base = startM;
        for (RoadEdge e : path.edges()) {
            if (e.penalty().get(Penalty.CROSSWALK) >= 1) {
                double[] last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && Math.abs(last[1] - base) < 1e-6) {
                    last[1] = base + e.lengthM();
                } else {
                    out.add(new double[] {base, base + e.lengthM()});
                }
            }
            base += e.lengthM();
        }
        return out;
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    /** a에서 b로 가는 방위각(도). 북쪽 0°, 동쪽 90° */
    static double heading(double[] a, double[] b) {
        double dx = (b[0] - a[0]) * Math.cos(Math.toRadians((a[1] + b[1]) / 2));
        double dy = b[1] - a[1];
        return Math.toDegrees(Math.atan2(dx, dy));
    }

    /** -180~180도로 */
    static double normalize(double d) {
        return ((d % 360) + 540) % 360 - 180;
    }
}
