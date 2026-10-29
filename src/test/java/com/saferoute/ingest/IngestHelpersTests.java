package com.saferoute.ingest;

import static org.assertj.core.api.Assertions.assertThat;
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
}
