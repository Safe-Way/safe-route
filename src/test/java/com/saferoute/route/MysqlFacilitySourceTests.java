package com.saferoute.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.saferoute.ingest.FacilityTable;

import com.saferoute.route.data.MysqlFacilitySource;
import com.saferoute.route.engine.facility.Facility;
import com.saferoute.route.engine.facility.FacilityGroup;
import com.saferoute.route.engine.geo.BBox;

/**
 * 실제 DB 시설 표에서 조선대 주변 시설을 읽는다 (DB가 있어야 돈다).
 * CI는 빈 표로 돌므로, 쿼리가 도는지만 보고 개수 확인은 데이터가 있을 때만 한다.
 * 캠퍼스 안은 조명·CCTV 자료가 없어서(요구사항 1장 제외 범위) 1km로 넓혀 본다.
 */
@SpringBootTest
class MysqlFacilitySourceTests {

    @Autowired
    MysqlFacilitySource source;

    @Test
    void readsFacilitiesAroundChosunUniversity() {
        double[] gate = {126.9306, 35.1408};
        BBox box = BBox.around(List.of(gate), 1000);

        List<Facility> found = source.within(box, FacilityGroup.allTables());
        Map<FacilityTable, Long> byType = found.stream()
                .collect(Collectors.groupingBy(Facility::type, Collectors.counting()));
        System.out.println("조선대 1km 사각형 안 시설: " + byType);
        System.out.println("시설 데이터 기준일: " + source.dataDate());

        assertThat(found).allMatch(f -> box.contains(f.lon(), f.lat()));
        assumeTrue(source.dataDate() != null, "시설 데이터가 없는 DB (CI)");
        assertThat(byType.getOrDefault(FacilityTable.CCTV, 0L)).isPositive();
        assertThat(byType.getOrDefault(FacilityTable.SECURITY_LIGHT, 0L)).isPositive();
    }
}
