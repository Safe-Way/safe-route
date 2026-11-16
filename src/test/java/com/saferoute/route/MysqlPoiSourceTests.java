package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.saferoute.route.data.MysqlPoiSource;
import com.saferoute.route.engine.facility.Poi;
import com.saferoute.route.engine.geo.BBox;

/**
 * DB의 poi·holiday·data_source를 읽는다 (DB가 있어야 돈다).
 * CI는 빈 표로 돌므로 쿼리가 도는지와 schema.sql의 공휴일만 보고, 시설 개수는 데이터가 있을 때만 확인한다.
 */
@SpringBootTest
class MysqlPoiSourceTests {

    @Autowired
    MysqlPoiSource source;

    @Test
    void readsPoiAroundAsianCultureCenter() {
        double[] center = {126.9204, 35.1466}; // 국립아시아문화전당
        BBox box = BBox.around(List.of(center), 1000);

        List<Poi> found = source.within(box, Set.of("light", "cctv", "police", "store", "toilet", "shelter"));
        Map<String, Long> byKind = found.stream().collect(Collectors.groupingBy(Poi::kind, Collectors.counting()));
        System.out.println("문화전당 1km 사각형 안 poi: " + byKind + ", 기준일 " + source.dataDate());

        assertThat(found).allMatch(p -> box.contains(p.lon(), p.lat()));
        assertThat(found).allMatch(p -> p.count() >= 1);
        assertThat(source.holidays()).isNotEmpty(); // schema.sql에 공휴일이 들어 있다
        assertThat(source.reports(box)).allMatch(r -> box.contains(r.lon(), r.lat())); // 신고 표시 쿼리·뷰가 실제 DB에서 돈다
        assumeTrue(!found.isEmpty(), "시설 데이터가 없는 DB (CI)");
        assertThat(byKind.getOrDefault("light", 0L)).isPositive();
        assertThat(byKind.getOrDefault("cctv", 0L)).isPositive();
    }
}
