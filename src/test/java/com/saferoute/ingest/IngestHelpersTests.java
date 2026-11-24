package com.saferoute.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 적재에 쓰는 좌표 변환과 CSV 읽기 확인 (DB 없이 돈다) */
@DisplayName("적재 도우미: 좌표 변환·CSV 읽기")
class IngestHelpersTests {

    @Test
    @DisplayName("웹 메르카토르 좌표를 경도·위도로 바꾼다")
    void mercatorToLonLat() {
        // 경찰청 본청: 재난안전데이터의 XMAP_CRTS / YMAP_CRTS 값. 서울 서대문구 미근동 근처가 나와야 한다
        double[] ll = Mercator.toLonLat(14133956.66, 4517926.194);
        assertThat(ll[0]).isCloseTo(126.968, within(0.01));
        assertThat(ll[1]).isCloseTo(37.563, within(0.01));
        assertThat(Mercator.inKorea(ll[0], ll[1])).isTrue();
    }

    @Test
    @DisplayName("한국 밖 좌표는 버린다")
    void outsideKoreaIsRejected() {
        assertThat(Mercator.inKorea(0, 0)).isFalse();
        assertThat(Mercator.inKorea(126.93, 35.14)).isTrue();
    }

    @Test
    @DisplayName("CSV: 따옴표 안의 쉼표는 나누지 않는다")
    void csvKeepsCommasInsideQuotes() {
        List<String> f = FacilityImporter.parseCsvLine("a,\"서울특별시 종로구, 1\",,\"따옴표 \"\"안\"\"\"");
        assertThat(f).containsExactly("a", "서울특별시 종로구, 1", "", "따옴표 \"안\"");
    }

    @Test
    @DisplayName("모든 데이터셋에 넣을 표가 정해져 있다")
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
    @DisplayName("모르는 데이터 종류는 거절한다")
    void unknownTypeIsRejected() {
        // 표 이름을 SQL에 직접 넣으므로 목록에 없는 이름은 막아야 한다
        assertThatThrownBy(() -> FacilityTable.of("facility; DROP TABLE cctv"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한파쉼터: 경도·위도 칸과 종류 이름을 쓴다")
    void coldShelterRowUsesLonLatAndKindNames() {
        // 재난안전데이터 한파쉼터 원본 한 줄 (값 일부)
        String item = """
                {"REARE_NM": "청운효자동주민센터", "RONA_DADDR": "서울특별시 종로구 자하문로 92 (궁정동)",
                 "UTZTN_PSBLTY_TNOP": 20, "WKDY_OPER_BGNG_HR": "0900", "WKDY_OPER_END_HR": "1800",
                 "FCLT_TYPE": "001", "FCLTY_SCLAS": "001", "REARE_FCLT_NO": 11110515083865,
                 "LOT": 126.970656, "LAT": 37.584095, "SNDY_OPER_BGNG_HR": null}
                """;
        FacilityImporter importer = new FacilityImporter(null);
        FacilityRow row = importer.toRow(FacilityDataset.COLD_SHELTER,
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(item));

        assertThat(row.lon()).isEqualTo(126.970656);
        assertThat(row.lat()).isEqualTo(37.584095);
        assertThat(row.kind()).isEqualTo("공공시설");
        assertThat(row.sourceId()).isEqualTo("11110515083865");
        assertThat(row.sidoCode()).isEqualTo("11");
        assertThat(row.sggCode()).isEqualTo("11110");
        assertThat(row.attrsJson()).contains("\"UTZTN_PSBLTY_TNOP\":\"20\"").doesNotContain("SNDY_OPER_BGNG_HR");
    }

    @Test
    @DisplayName("보안등 표준데이터: 등 개수와 줄 해시를 쓴다")
    void securityLightStdRowUsesCountAndRowHash() {
        // 전국보안등정보표준데이터 원본 한 줄. 원본 번호가 없어 줄 전체로 번호를 만든다
        String item = """
                {"lmpLcNm": "신학리102", "installationCo": "2", "rdnmadr": "",
                 "lnmadr": "전남광주통합특별시 무안군 무안읍 신학리 188",
                 "latitude": "34.98853107", "longitude": "126.4360657", "installationYear": "2011",
                 "installationType": "한전주", "phoneNumber": "061-450-5456", "insttCode": "5880000"}
                """;
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        FacilityImporter importer = new FacilityImporter(null);
        FacilityRow row = importer.toRow(FacilityDataset.SECURITY_LIGHT_STD, mapper.readTree(item));
        FacilityRow again = importer.toRow(FacilityDataset.SECURITY_LIGHT_STD, mapper.readTree(item));

        assertThat(row.type()).isEqualTo("security_light");
        assertThat(row.source()).isEqualTo("data_go_kr");
        assertThat(row.lon()).isEqualTo(126.4360657);
        assertThat(row.lat()).isEqualTo(34.98853107);
        assertThat(row.kind()).isEqualTo("한전주");
        assertThat(row.unitCount()).isEqualTo(2);
        assertThat(row.address()).isEqualTo("전남광주통합특별시 무안군 무안읍 신학리 188");
        assertThat(row.sourceId()).isNotBlank().isEqualTo(again.sourceId());
        assertThat(row.attrsJson()).contains("installationYear").doesNotContain("installationCo");
    }

    @Test
    @DisplayName("육교: 장애인 편의 종류를 세부 종류로 둔다")
    void footbridgeRowKeepsHandicapTypeAsKind() {
        // 전국육교정보표준데이터 원본 한 줄 (값 일부). 장애인 편의시설 종류가 세부 종류가 된다
        String item = """
                {"ovrpsNm": "크라운호텔앞보도육교", "rdnmadr": "서울특별시 용산구 녹사평대로 132",
                 "lctnLotnoAddr": "", "latitude": "37.53072639", "longitude": "126.9911211",
                 "ovrpsEt": "26.7", "handicapCvntlYn": "N", "handicapCvntlType": "없음",
                 "safeGrad": "보통", "institutionNm": "서울특별시 용산구청"}
                """;
        FacilityImporter importer = new FacilityImporter(null);
        FacilityRow row = importer.toRow(FacilityDataset.FOOTBRIDGE,
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(item));

        assertThat(row.type()).isEqualTo("footbridge");
        assertThat(FacilityTable.of(row.type())).isEqualTo(FacilityTable.FOOTBRIDGE);
        assertThat(row.name()).isEqualTo("크라운호텔앞보도육교");
        assertThat(row.kind()).isEqualTo("없음");
        assertThat(row.lon()).isEqualTo(126.9911211);
        assertThat(row.address()).isEqualTo("서울특별시 용산구 녹사평대로 132");
        assertThat(row.attrsJson()).contains("\"ovrpsEt\":\"26.7\"").doesNotContain("handicapCvntlType");
    }

    @Test
    @DisplayName("횡단보도: 도로 이름과 두 자리 종류 코드를 쓴다")
    void crosswalkRowUsesRoadNameAndTwoDigitKind() {
        // 전국횡단보도표준데이터 원본 한 줄 (값 일부). 종류 코드 "1"은 "01"로 맞추고, 빈 칸(" ")은 attrs에서 빠진다
        String item = """
                {"roadNm": "승지로", "rdnmadr": "", "lnmadr": "경기도 의정부시 민락동 610-15",
                 "crslkKnd": "1", "latitude": "37.73857062", "longitude": "127.0903328",
                 "ftpthLowerYn": "Y", "brllBlckYn": "Y", "sondSgngnrYn": " ", "greenSgngnrTime": "0"}
                """;
        FacilityImporter importer = new FacilityImporter(null);
        FacilityRow row = importer.toRow(FacilityDataset.CROSSWALK,
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(item));

        assertThat(FacilityTable.of(row.type())).isEqualTo(FacilityTable.CROSSWALK);
        assertThat(row.name()).isEqualTo("승지로");
        assertThat(row.kind()).isEqualTo("01");
        assertThat(row.address()).isEqualTo("경기도 의정부시 민락동 610-15");
        assertThat(row.attrsJson()).contains("\"ftpthLowerYn\":\"Y\"").doesNotContain("sondSgngnrYn");
    }

    @Test
    @DisplayName("어린이보호구역은 전국 CSV에서 읽는다")
    void childProtectionZoneComesFromNationalCsv() {
        // API 대신 포털 CSV(CP949)로 넣는다. 개수 칸이 없는 표라 INSERT에도 개수 칸이 없어야 한다
        FacilityImporter.RegionalCsv rc = FacilityImporter.REGIONAL.stream()
                .filter(r -> r.type().equals("child_protection_zone")).findFirst().orElseThrow();
        assertThat(rc.file()).isEqualTo("national/child_protection_zone.csv");
        assertThat(rc.charset()).isEqualTo("MS949");
        assertThat(rc.countCol()).isNull();
        assertThat(FacilityImporter.insertSql(FacilityTable.CHILD_PROTECTION_ZONE))
                .startsWith("INSERT IGNORE INTO child_protection_zone ")
                .doesNotContain("count");
    }

    @Test
    @DisplayName("넣는 SQL에 개수 칸은 그 칸이 있는 표에만 붙인다")
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
