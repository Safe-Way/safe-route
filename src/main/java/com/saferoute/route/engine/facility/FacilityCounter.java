package com.saferoute.route.engine.facility;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.saferoute.ingest.FacilityTable;

import com.saferoute.route.RouteProperties;
import com.saferoute.route.data.FacilitySource;
import com.saferoute.route.engine.geo.BBox;
import com.saferoute.route.engine.geo.Geo;

/**
 * 경로 선에서 반경 안에 있는 시설을 센다 (요구사항 6.3 차이 설명, 결정 12).
 * 출처가 달라 같은 시설이 두 번 들어 있는 경우가 있어, 같은 종류가 dedupe-m 안에 겹치면 하나로 센다 (5.3).
 */
@Component
public class FacilityCounter {

    private final FacilitySource source;
    private final RouteProperties.CountRadius radius;
    private final double dedupeM;

    public FacilityCounter(FacilitySource source, RouteProperties props) {
        this.source = source;
        this.radius = props.countRadiusM();
        this.dedupeM = props.dedupeM();
    }

    /** 여러 경로 선 각각이 지나는 시설 수. 주변 시설은 한 번만 읽고 중복을 합친 뒤 센다 */
    public List<Counts> countAlong(List<List<double[]>> lines) {
        double max = maxRadius();
        List<double[]> all = lines.stream().flatMap(List::stream).toList();
        List<Facility> near = new ArrayList<>();
        for (Facility f : source.within(BBox.around(all, max), FacilityGroup.allTables())) {
            if (lines.stream().anyMatch(line -> Geo.distanceToLine(f.point(), line) <= max)) {
                near.add(f);
            }
        }
        List<Facility> unique = dedupe(near);
        return lines.stream().map(line -> count(line, unique)).toList();
    }

    /** 겹치는 시설을 하나로 합친다. 합칠 때는 개수가 큰 쪽을 남긴다 */
    List<Facility> dedupe(List<Facility> facilities) {
        Map<FacilityTable, List<Facility>> kept = new EnumMap<>(FacilityTable.class);
        for (Facility f : facilities) {
            List<Facility> same = kept.computeIfAbsent(sameKind(f.type()), k -> new ArrayList<>());
            int dup = -1;
            for (int i = 0; i < same.size() && dup < 0; i++) {
                if (Geo.distance(same.get(i).point(), f.point()) <= dedupeM) {
                    dup = i;
                }
            }
            if (dup < 0) {
                same.add(f);
            } else if (f.count() > same.get(dup).count()) {
                same.set(dup, f);
            }
        }
        return kept.values().stream().flatMap(List::stream).toList();
    }

    Counts count(List<double[]> line, List<Facility> facilities) {
        Map<FacilityGroup, Integer> byGroup = new EnumMap<>(FacilityGroup.class);
        List<Facility> passed = new ArrayList<>();
        for (Facility f : facilities) {
            FacilityGroup g = FacilityGroup.of(f.type());
            if (g != null && Geo.distanceToLine(f.point(), line) <= radiusOf(g)) {
                byGroup.merge(g, f.count(), Integer::sum);
                passed.add(f);
            }
        }
        return new Counts(byGroup, passed);
    }

    private double maxRadius() {
        return Math.max(Math.max(radius.light(), radius.cctv()), Math.max(radius.bell(), radius.help()));
    }

    private double radiusOf(FacilityGroup g) {
        return switch (g) {
            case LIGHT -> radius.light();
            case CCTV -> radius.cctv();
            case BELL -> radius.bell();
            case HELP -> radius.help();
        };
    }

    /** 가로등과 보안등은 출처마다 같은 등을 서로 다른 표에 넣기도 해서 같은 종류로 본다 */
    private static FacilityTable sameKind(FacilityTable t) {
        return t == FacilityTable.STREETLIGHT ? FacilityTable.SECURITY_LIGHT : t;
    }

    /** 경로 하나가 지나는 시설 수와, 센 시설 목록 */
    public record Counts(Map<FacilityGroup, Integer> byGroup, List<Facility> passed) {

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
