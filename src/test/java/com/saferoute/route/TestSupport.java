package com.saferoute.route;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.AStarShortestPath;
import org.jgrapht.graph.AsWeightedGraph;

import com.saferoute.route.engine.geo.Geo;
import com.saferoute.route.engine.graph.RoadEdge;
import com.saferoute.route.engine.graph.RoadGraph;

/** 여러 테스트가 같이 쓰는 도우미: 결과 출력, .env 읽기, 비용을 바꿔 끼운 경로 탐색 */
final class TestSupport {

    private TestSupport() {
    }

    /** 시험 지역: 이름과 OSM 파일 */
    record Region(String name, Path osm) {
    }

    /** 찾은 경로: 지나는 길과 길이(m) */
    record Route(List<RoadEdge> edges, double length) {
    }

    /** cost로 A* 탐색. 길이 아래로 내려가지 않는 비용만 넣는다 */
    static Route search(RoadGraph g, Graph<Long, RoadEdge> usable, long a, long b, ToDoubleFunction<RoadEdge> cost) {
        var weighted = new AsWeightedGraph<>(usable, cost::applyAsDouble, false, false);
        GraphPath<Long, RoadEdge> p = new AStarShortestPath<>(weighted,
                (x, y) -> 0.99 * Geo.distance(g.point(x), g.point(y))).getPath(a, b);
        return p == null ? null : new Route(p.getEdgeList(), p.getEdgeList().stream().mapToDouble(RoadEdge::lengthM).sum());
    }

    /** 프로젝트 폴더의 .env (없으면 빈 값) */
    static Map<String, String> readEnv() throws IOException {
        Map<String, String> env = new HashMap<>();
        Path p = Path.of(".env");
        if (!Files.exists(p)) {
            return env;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            line = line.strip();
            if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                String[] kv = line.split("=", 2);
                env.put(kv[0].strip(), kv[1].strip());
            }
        }
        return env;
    }

    /** 사람이 읽을 결과를 모아 한 번에 출력한다 */
    static final class Out {
        private final StringBuilder sb = new StringBuilder();

        Out(String title) {
            sb.append("\n========== ").append(title).append(" ==========\n");
        }

        void line(String format, Object... args) {
            sb.append(args.length == 0 ? format : format.formatted(args)).append('\n');
        }

        void print() {
            System.out.print(sb);
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
