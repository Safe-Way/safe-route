package com.saferoute.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import com.saferoute.testsupport.FakeDb;
import com.saferoute.testsupport.FakeDb.Call;

/** 내려받은 파일 → 시설 표 적재를 가짜 DB로 끝까지 돌려 본다 (어떤 줄이 들어가고 빠지는지, 출처 현황) */
class FacilityImporterTests {

    private static final Charset MS949 = Charset.forName("MS949");

    @TempDir
    Path raw;

    private FakeDb db;
    private FacilityImporter importer;

    @BeforeEach
    void setUp() {
        db = new FakeDb()
                .updates("DELETE FROM", 0) // 나눠 지우기는 0줄이 될 때까지 돈다
                .rows("SELECT COUNT(*) FROM", FakeDb.row("n", 1));
        importer = new FacilityImporter(db.jdbc()).rawRoot(raw);
    }

    private void write(String path, String body, Charset cs) throws Exception {
        Path p = raw.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, cs);
    }

    private void run(String... args) throws Exception {
        importer.run(new DefaultApplicationArguments(args));
    }

    private List<Call> inserts(String table) {
        return db.calls("INSERT IGNORE INTO " + table + " ");
    }

    private Call source(String type) {
        return db.calls("INSERT INTO data_source").stream().filter(c -> c.params().get(0).equals(type)).findFirst()
                .orElseThrow();
    }

    @Test
    void apiPagesKeepOnlyRowsWithKoreanCoordinates() throws Exception {
        // 재난안전데이터 경찰: 메르카토르 좌표. 좌표 없는 줄·한국 밖 줄은 빠진다
        write("national/police/_total.txt", "3\n", StandardCharsets.UTF_8);
        write("national/police/p00001.json", """
                [{"SN": "11", "FCLT_GVMNFC_NM": "충장치안센터", "POLSTN_NM": "광주동부", "ROAD_NM_ADDR": "광주 동구 금남로 1",
                  "FCLT_TYPE": "치안센터", "STDG_CTPV_CD": "29.0", "STDG_SGG_CD": "2.911E7", "XMAP_CRTS": "14128669.77",
                  "YMAP_CRTS": "4184284.27", "TELNO": "062-000-0000", "GEOM": "POINT(...)", "ETC": "-"},
                 {"SN": "12", "FCLT_GVMNFC_NM": "좌표 없음", "XMAP_CRTS": "", "YMAP_CRTS": null},
                 {"SN": "13", "FCLT_GVMNFC_NM": "바다 한가운데", "XMAP_CRTS": "0", "YMAP_CRTS": "0"}]
                """, StandardCharsets.UTF_8);

        run("--types=police");

        assertThat(db.call("DELETE FROM police").params()).containsExactly("safetydata");
        List<Call> rows = inserts("police");
        assertThat(rows).hasSize(1);
        List<Object> p = rows.get(0).params();
        // kind, source, source_id, name, address, sido, sgg, lon, lat, lon, lat, attrs
        assertThat(p.subList(0, 7)).containsExactly("치안센터", "safetydata", "11", "충장치안센터", "광주 동구 금남로 1",
                "29", "29110");
        assertThat((Double) p.get(7)).isCloseTo(126.92, within(1e-6));
        assertThat((Double) p.get(8)).isCloseTo(35.15, within(1e-6));
        // 쓰지 않은 칸만 attrs 로 ("-"·GEOM 은 버림)
        assertThat((String) p.get(11)).isEqualTo("{\"TELNO\":\"062-000-0000\"}");
        assertThat(p).hasSize(12); // 경찰 표에는 개수 칸이 없음

        Call src = source("police");
        // type, source, title, api, license, total, loaded, skipped, complete
        assertThat(src.params().subList(5, 9)).containsExactly(3, 1, 2, true);
        // --types 로 고른 것만
        assertThat(db.calls("DELETE FROM cctv")).isEmpty();
    }

    @Test
    void lonLatDatasetWithoutIdUsesRowHashAndCount() throws Exception {
        // 보안등 표준데이터: 경도·위도, 번호 칸 없음 → 줄 내용으로 번호, 설치 개수는 lamp 개수 칸
        write("national/security_light_std/_total.txt", "2500", StandardCharsets.UTF_8);
        write("national/security_light_std/p00001.json", """
                [{"lmpLcNm": "동구 보안등 1", "rdnmadr": "", "lnmadr": "광주 동구 서석동 1", "installationType": "벽부형",
                  "longitude": "126.93", "latitude": "35.14", "installationCo": "3"},
                 {"lmpLcNm": "동구 보안등 2", "longitude": "126.93", "latitude": "35.14", "installationCo": "0"},
                 {"lmpLcNm": "동구 보안등 3", "longitude": "126.93", "latitude": "35.14", "installationCo": "40000"}]
                """, StandardCharsets.UTF_8);
        write("national/security_light_std/notes.txt", "페이지 파일이 아님", StandardCharsets.UTF_8);

        run("--types=security_light_std");

        List<Call> rows = inserts("security_light");
        assertThat(rows).hasSize(3);
        List<Object> first = rows.get(0).params();
        assertThat(first.get(0)).isEqualTo("벽부형");
        assertThat((String) first.get(2)).hasSize(24); // SHA-1 앞 12바이트
        assertThat(first.get(4)).isEqualTo("광주 동구 서석동 1"); // 빈 도로명주소 다음 칸
        assertThat(first.get(5)).isNull(); // 시도 코드 없음
        assertThat(first.get(12)).isEqualTo(3); // 개수 칸
        assertThat(rows.get(1).params().get(12)).isEqualTo(1); // 0 → 1
        assertThat(rows.get(2).params().get(12)).isEqualTo((int) Short.MAX_VALUE); // 너무 크면 자름
        // 2,500건 중 1쪽(1,000건)만 받았으니 다 받지 않음
        assertThat(source("security_light").params().get(8)).isEqualTo(false);
    }

    @Test
    void kindCodesBecomeNamesAndLongValuesAreCut() throws Exception {
        write("national/crosswalk/_total.txt", "1", StandardCharsets.UTF_8);
        String longName = "가".repeat(250);
        write("national/crosswalk/p00001.json", "[{\"roadNm\": \"" + longName + "\", \"crslkKnd\": \"1\","
                + " \"longitude\": \"126.92\", \"latitude\": \"35.15\"}]", StandardCharsets.UTF_8);

        run("--types=crosswalk");

        List<Object> p = inserts("crosswalk").get(0).params();
        assertThat(p.get(0)).isEqualTo("01");
        assertThat((String) p.get(3)).hasSize(200);
    }

    @Test
    void missingFilesAreSkipped() throws Exception {
        run();

        assertThat(db.calls("INSERT")).isEmpty();
        assertThat(db.calls("DELETE")).isEmpty();
    }

    @Test
    void nationalCctvCsvInMs949() throws Exception {
        write("national/cctv_national.csv", String.join("\n",
                "관리번호,관리기관명,소재지도로명주소,소재지지번주소,설치목적구분,카메라대수,카메라화소수,촬영방면정보,보관일수,설치연월,관리기관전화번호,WGS84위도,WGS84경도,데이터기준일자",
                "C-1,광주 동구청,,광주 동구 서석동 2,생활방범,\"2\",200,\"북, 남\",30,2020-01,062,35.14,126.93,2026-01-01",
                "C-2,광주 동구청,광주 동구 필문대로 1,,어린이보호,,,,,,,35.14,126.93,",
                "C-3,광주 동구청,,,생활방범,1,,,,,,,,",
                "C-4,해외,,,생활방범,1,,,,,,10.0,10.0,"), MS949);

        run("--types=cctv");

        assertThat(db.call("DELETE FROM cctv").params()).containsExactly("localdata");
        List<Call> rows = db.calls("INSERT IGNORE INTO cctv");
        assertThat(rows).hasSize(2);
        List<Object> a = rows.get(0).params();
        assertThat(a.subList(0, 5)).containsExactly("생활방범", "localdata", "C-1", "광주 동구청", "광주 동구 서석동 2");
        assertThat((String) a.get(11)).contains("\"촬영방면정보\":\"북, 남\"");
        assertThat(a.get(12)).isEqualTo(2);
        List<Object> b = rows.get(1).params();
        assertThat(b.get(4)).isEqualTo("광주 동구 필문대로 1");
        assertThat(b.get(11)).isNull(); // 남길 칸이 없으면 attrs 없음
        assertThat(b.get(12)).isEqualTo(1);
        assertThat(source("cctv").params().subList(5, 9)).containsExactly(4, 1, 2, true);
    }

    @Test
    void regionalCsvFilesWithBomAndCharset() throws Exception {
        write("regional/gwangju_cctv.csv", String.join("\n",
                "관리기관명,소재지도로명주소,소재지지번주소,카메라대수,카메라화소,위도,경도",
                "광주광역시,,광주 동구 1,3,200,35.14,126.93",
                "광주광역시,,,1,,,"), MS949);
        write("regional/donggu_security_light.csv", "﻿" + String.join("\n",
                "보안등위치명,설치형태,설치개수,소재지도로명주소,위도,경도,설치연도",
                "서석동 1,벽부형,2,광주 동구 서석로 1,35.14,126.93,2019"), StandardCharsets.UTF_8);

        run("--types=cctv,security_light");

        List<Call> cctv = db.calls("INSERT IGNORE INTO cctv");
        assertThat(cctv).hasSize(1);
        assertThat(cctv.get(0).params().subList(0, 5)).containsExactly(null, "gwangju",
                cctv.get(0).params().get(2), "광주광역시", "광주 동구 1");
        assertThat(cctv.get(0).params().get(12)).isEqualTo(3);
        List<Call> lights = db.calls("INSERT IGNORE INTO security_light");
        assertThat(lights).hasSize(1);
        assertThat(lights.get(0).params().subList(0, 5)).containsExactly("벽부형", "gwangju_donggu",
                lights.get(0).params().get(2), "서석동 1", "광주 동구 서석로 1");
        assertThat((String) lights.get(0).params().get(11)).isEqualTo("{\"설치연도\":\"2019\"}");
        Call src = source("security_light");
        assertThat((String) src.params().get(3)).endsWith("(donggu_security_light.csv)");
    }

    @Test
    void insertSqlAddsCountColumnOnlyForTablesWithIt() {
        assertThat(FacilityImporter.insertSql(FacilityTable.of("police")))
                .doesNotContain("camera_count").endsWith("?, ?), 0), ?)");
        assertThat(FacilityImporter.insertSql(FacilityTable.CCTV)).contains(", camera_count)").endsWith(", ?, ?)");
    }
}
