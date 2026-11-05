package com.saferoute.route.engine.facility;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.engine.geo.Geo;

/**
 * 경로 선에서 반경 안에 있는 시설을 센다 (요구사항 6.3 차이 설명, 결정 12).
 * 시설은 poi(출처 중복을 이미 합친 표)에서 읽은 목록이라 여기서 다시 합치지 않는다.
 */
@Component
public class FacilityCounter {

    private final RouteProperties.CountRadius radius;

    public FacilityCounter(RouteProperties props) {
        this.radius = props.countRadiusM();
    }

    /** 여러 경로 선 각각이 지나는 시설 수 */
    public List<Counts> countAlong(List<List<double[]>> lines, List<Poi> pois) {
        return lines.stream().map(line -> count(line, pois)).toList();
    }

    Counts count(List<double[]> line, List<Poi> pois) {
        Map<FacilityGroup, Integer> byGroup = new EnumMap<>(FacilityGroup.class);
        List<Poi> passed = new ArrayList<>();
        for (Poi p : pois) {
            FacilityGroup g = FacilityGroup.of(p.kind());
            if (g != null && Geo.distanceToLine(p.point(), line) <= radiusOf(g)) {
                byGroup.merge(g, p.count(), Integer::sum);
                passed.add(p);
            }
        }
        return new Counts(byGroup, passed);
    }

    private double radiusOf(FacilityGroup g) {
        return switch (g) {
            case LIGHT -> radius.light();
            case CCTV -> radius.cctv();
            case BELL -> radius.bell();
            case HELP -> radius.help();
        };
    }

    /** 경로 하나가 지나는 시설 수와, 센 시설 목록 */
    public record Counts(Map<FacilityGroup, Integer> byGroup, List<Poi> passed) {

        public int get(FacilityGroup g) {
            return byGroup.getOrDefault(g, 0);
        }

        /** this − other, 묶음마다 */
        public Map<FacilityGroup, Integer> minus(Counts other) {
            Map<FacilityGroup, Integer> diff = new EnumMap<>(FacilityGroup.class);
            for (FacilityGroup g : FacilityGroup.values()) {
                diff.put(g, get(g) - other.get(g));
            }
            return diff;
        }
    }
}
