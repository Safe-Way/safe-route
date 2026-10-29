package com.saferoute.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 적재에 쓰는 좌표 변환과 CSV 읽기 확인 (DB 없이 돈다) */
class IngestHelpersTests {

    @Test
    void mercatorToLonLat() {
        // 경찰청 본청: 재난안전데이터의 XMAP_CRTS / YMAP_CRTS 값. 서울 서대문구 미근동 근처가 나와야 한다
        double[] ll = Mercator.toLonLat(14133956.66, 4517926.194);
        assertThat(ll[0]).isCloseTo(126.968, within(0.01));
        assertThat(ll[1]).isCloseTo(37.563, within(0.01));
        assertThat(Mercator.inKorea(ll[0], ll[1])).isTrue();
    }

    @Test
    void outsideKoreaIsRejected() {
        assertThat(Mercator.inKorea(0, 0)).isFalse();
        assertThat(Mercator.inKorea(126.93, 35.14)).isTrue();
    }

    @Test
    void csvKeepsCommasInsideQuotes() {
        List<String> f = FacilityImporter.parseCsvLine("a,\"서울특별시 종로구, 1\",,\"따옴표 \"\"안\"\"\"");
        assertThat(f).containsExactly("a", "서울특별시 종로구, 1", "", "따옴표 \"안\"");
    }

    @Test
    void everyDatasetHasItsTable() {
        // 적재 규칙의 종류 이름이 모두 실제 표 이름이어야 한다 (오타가 있으면 여기서 걸림)
        for (FacilityDataset ds : FacilityDataset.ALL) {
            assertThat(FacilityTable.of(ds.type()).table()).isEqualTo(ds.type());
        }
        for (FacilityImporter.RegionalCsv rc : FacilityImporter.REGIONAL) {
            assertThat(FacilityTable.of(rc.type()).table()).isEqualTo(rc.type());
        }
        assertThat(FacilityTable.of("cctv")).isEqualTo(FacilityTable.CCTV);
    }

    @Test
    void unknownTypeIsRejected() {
        // 표 이름을 SQL에 직접 넣으므로 목록에 없는 이름은 막아야 한다
        assertThatThrownBy(() -> FacilityTable.of("facility; DROP TABLE cctv"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void insertSqlAddsCountColumnOnlyWhereItExists() {
        assertThat(FacilityImporter.insertSql(FacilityTable.CCTV))
                .startsWith("INSERT IGNORE INTO cctv ")
                .contains(", camera_count)")
                .endsWith(", ?, ?)");
        assertThat(FacilityImporter.insertSql(FacilityTable.POLICE))
                .startsWith("INSERT IGNORE INTO police ")
                .doesNotContain("count")
                .endsWith("0), ?)");
    }
}
