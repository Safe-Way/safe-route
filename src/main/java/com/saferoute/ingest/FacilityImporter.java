package com.saferoute.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * data/raw 에 내려받은 시설 파일을 종류별 시설 표(police, cctv, ...)에 넣는다.
 * 같은 종류·출처를 다시 넣으면 먼저 지우고 새로 넣으므로 몇 번을 실행해도 결과가 같다.
 *
 * 실행: ./mvnw spring-boot:run -Dspring-boot.run.profiles=import
 * 일부만: -Dspring-boot.run.arguments=--types=police,cctv (폴더 이름도 됨: --types=security_light_std)
 */
@Component
@Profile("import")
public class FacilityImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FacilityImporter.class);
    private static final Path RAW = Path.of("data", "raw", "national");
    private static final int PAGE_SIZE = 1000;
    private static final int BATCH = 5000;

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public FacilityImporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Set<String> only = new HashSet<>();
        if (args.containsOption("types")) {
            for (String v : args.getOptionValues("types")) {
                only.addAll(List.of(v.split(",")));
            }
        }
        for (FacilityDataset ds : FacilityDataset.ALL) {
            // 종류 이름(security_light)이나 폴더 이름(security_light_std)으로 고를 수 있다
            if (only.isEmpty() || only.contains(ds.type()) || only.contains(ds.folder())) {
                importApiDataset(ds);
            }
        }
        if (only.isEmpty() || only.contains("cctv")) {
            importCctvCsv();
        }
        for (RegionalCsv rc : REGIONAL) {
            if (only.isEmpty() || only.contains(rc.type())) {
                importRegionalCsv(rc);
            }
        }
        log.info("적재 끝");
    }

    // ---------- API로 받은 JSON 페이지 ----------

    private void importApiDataset(FacilityDataset ds) throws IOException {
        Path dir = RAW.resolve(ds.folder());
        Path totalFile = dir.resolve("_total.txt");
        if (!Files.exists(totalFile)) {
            log.info("{}: 아직 받은 파일이 없어 건너뜀", ds.title());
            return;
        }
        int total = Integer.parseInt(Files.readString(totalFile).trim());
        List<Path> pages;
        try (Stream<Path> s = Files.list(dir)) {
            pages = s.filter(p -> p.getFileName().toString().matches("p\\d{5}\\.json")).sorted().toList();
        }
        int needPages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        boolean complete = pages.size() >= needPages;

        deleteType(ds.type(), ds.source());
        List<FacilityRow> batch = new ArrayList<>(BATCH);
        int skipped = 0;
        for (Path page : pages) {
            for (JsonNode item : json.readTree(page.toFile())) {
                FacilityRow row = toRow(ds, item);
                if (row == null) {
                    skipped++;
                    continue;
                }
                batch.add(row);
                if (batch.size() >= BATCH) {
                    insert(batch);
                    batch.clear();
                }
            }
        }
        insert(batch);
        int loaded = saveSource(ds.type(), ds.source(), ds.title(), ds.api(), ds.license(), total, skipped, complete);
        log.info("{}: {}건 적재, {}건 제외 (받은 쪽 {}/{}{})", ds.title(), loaded, skipped,
                pages.size(), needPages, complete ? "" : ", 아직 다 받지 않음");
    }

    FacilityRow toRow(FacilityDataset ds, JsonNode item) {
        Double x = number(item, ds.xKey());
        Double y = number(item, ds.yKey());
        if (x == null || y == null) {
            return null;
        }
        double[] ll = ds.lonLat() ? new double[] {x, y} : Mercator.toLonLat(x, y);
        if (!Mercator.inKorea(ll[0], ll[1])) {
            return null;
        }
        String name = first(item, ds.nameKeys());
        String id = first(item, ds.idKeys());
        if (id == null) {
            // 원본에 번호가 없으면 줄 전체로 번호를 만든다 (똑같은 줄이 두 번 있으면 하나만 들어감)
            id = hash(item.toString());
        }
        Set<String> used = new HashSet<>(List.of(ds.xKey(), ds.yKey()));
        used.addAll(ds.idKeys());
        used.addAll(ds.nameKeys());
        used.addAll(ds.addrKeys());
        used.addAll(ds.subtypeKeys());
        if (ds.countKey() != null) {
            used.add(ds.countKey());
        }
        if (ds.sidoKey() != null) {
            used.add(ds.sidoKey());
        }
        if (ds.sggKey() != null) {
            used.add(ds.sggKey());
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : item.properties()) {
            String v = clean(e.getValue());
            if (!used.contains(e.getKey()) && v != null && !e.getKey().equals("GEOM")) {
                attrs.put(e.getKey(), v);
            }
        }
        String kind = first(item, ds.subtypeKeys());
        if (kind != null) {
            // Map.of()는 null 키를 받지 않아서 값이 있을 때만 바꾼다
            kind = ds.kindNames().getOrDefault(kind, kind);
        }
        return new FacilityRow(ds.type(), cut(kind, 50), ds.source(), cut(id, 64),
                cut(name, 200), cut(first(item, ds.addrKeys()), 300),
                code(item, ds.sidoKey(), 2), code(item, ds.sggKey(), 5),
                ll[0], ll[1], count(item, ds.countKey()), attrs.isEmpty() ? null : json.writeValueAsString(attrs));
    }

    // ---------- CCTV CSV (지방행정 인허가 데이터, CP949) ----------

    private void importCctvCsv() throws IOException {
        Path file = RAW.resolve("cctv_national.csv");
        if (!Files.exists(file)) {
            log.info("CCTV: 파일이 없어 건너뜀");
            return;
        }
        deleteType("cctv", "localdata");
        List<FacilityRow> batch = new ArrayList<>(BATCH);
        int total = 0;
        int skipped = 0;
        try (BufferedReader r = Files.newBufferedReader(file, Charset.forName("MS949"))) {
            List<String> header = parseCsvLine(r.readLine());
            Map<String, Integer> col = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                col.put(header.get(i).trim(), i);
            }
            String line;
            while ((line = r.readLine()) != null) {
                total++;
                List<String> f = parseCsvLine(line);
                FacilityRow row = cctvRow(f, col);
                if (row == null) {
                    skipped++;
                    continue;
                }
                batch.add(row);
                if (batch.size() >= BATCH) {
                    insert(batch);
                    batch.clear();
                }
            }
        }
        insert(batch);
        int loaded = saveSource("cctv", "localdata", "행정안전부_CCTV정보 (지방행정 인허가 데이터)", "cctv_national.csv",
                "이용허락범위 제한 없음", total, skipped, true);
        log.info("CCTV: {}건 적재, {}건 제외", loaded, skipped);
    }

    private FacilityRow cctvRow(List<String> f, Map<String, Integer> col) {
        Double lat = parseDouble(get(f, col, "WGS84위도"));
        Double lon = parseDouble(get(f, col, "WGS84경도"));
        String id = get(f, col, "관리번호");
        if (lat == null || lon == null || id == null || !Mercator.inKorea(lon, lat)) {
            return null;
        }
        String address = get(f, col, "소재지도로명주소");
        if (address == null) {
            address = get(f, col, "소재지지번주소");
        }
        Double cameras = parseDouble(get(f, col, "카메라대수"));
        int count = cameras == null || cameras < 1 ? 1 : (int) Math.min(cameras, Short.MAX_VALUE);
        Map<String, String> attrs = new LinkedHashMap<>();
        for (String k : List.of("카메라화소수", "촬영방면정보", "보관일수", "설치연월", "관리기관전화번호", "데이터기준일자")) {
            String v = get(f, col, k);
            if (v != null) {
                attrs.put(k, v);
            }
        }
        return new FacilityRow("cctv", cut(get(f, col, "설치목적구분"), 50), "localdata", cut(id, 64),
                cut(get(f, col, "관리기관명"), 200), cut(address, 300), null, null, lon, lat, count,
                attrs.isEmpty() ? null : json.writeValueAsString(attrs));
    }

    // ---------- 지자체가 직접 낸 보충 파일 (시연 지역) ----------
    // 전국 데이터에는 조선대 1km 안 CCTV가 25곳, 보안등이 5개뿐이다.
    // 광주시·동구청 파일에는 각각 92곳, 742개가 있어 시연 지역은 이 파일로 보충한다.
    // 전국 데이터와 같은 시설이 겹칠 수 있어서, 길 점수를 매길 때 10m 안의 같은 종류는 하나로 본다.

    /** 위도·경도 칸이 있는 CSV 하나를 읽는 규칙. file은 data/raw 아래 경로 */
    record RegionalCsv(String file, String charset, String type, String source, String title, String api,
            String license, String nameCol, String subtypeCol, String countCol, List<String> attrCols) {
    }

    static final List<RegionalCsv> REGIONAL = List.of(
            new RegionalCsv("regional/gwangju_cctv.csv", "MS949", "cctv", "gwangju", "광주광역시_CCTV 설치 현황",
                    "공공데이터포털 15084631", "이용허락범위 제한 없음", "관리기관명", null, "카메라대수",
                    List.of("카메라화소", "촬영방면", "보관일수", "설치연도", "데이터기준일자")),
            new RegionalCsv("regional/donggu_security_light.csv", "UTF-8", "security_light", "gwangju_donggu",
                    "광주광역시 동구_보안등정보 (표준데이터)", "공공데이터포털 전국보안등정보표준데이터 15017320",
                    "확인 필요", "보안등위치명", "설치형태",
                    "설치개수", List.of("설치연도", "관리기관명", "데이터기준일자")),
            // 전국 파일이지만 API 활용 신청이 아직 반영되지 않아 포털에서 받은 CSV로 넣는다 (2026-11-02)
            new RegionalCsv("national/child_protection_zone.csv", "MS949", "child_protection_zone", "data_go_kr",
                    "전국어린이보호구역표준데이터", "공공데이터포털 15012891", "확인 필요", "대상시설명", "시설종류",
                    null, List.of("관리기관명", "관할경찰서명", "CCTV설치여부", "CCTV설치대수", "어린이보호구역지정여부",
                            "보호구역도로폭", "데이터기준일자", "제공기관명")));

    private void importRegionalCsv(RegionalCsv rc) throws IOException {
        Path file = Path.of("data", "raw").resolve(rc.file());
        if (!Files.exists(file)) {
            log.info("{}: 파일이 없어 건너뜀", rc.title());
            return;
        }
        deleteType(rc.type(), rc.source());
        List<FacilityRow> batch = new ArrayList<>();
        int total = 0;
        int skipped = 0;
        try (BufferedReader r = Files.newBufferedReader(file, Charset.forName(rc.charset()))) {
            List<String> header = parseCsvLine(r.readLine().replace("\uFEFF", ""));
            Map<String, Integer> col = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                col.put(header.get(i).trim(), i);
            }
            String line;
            while ((line = r.readLine()) != null) {
                total++;
                List<String> f = parseCsvLine(line);
                Double lat = parseDouble(get(f, col, "위도"));
                Double lon = parseDouble(get(f, col, "경도"));
                if (lat == null || lon == null || !Mercator.inKorea(lon, lat)) {
                    skipped++;
                    continue;
                }
                String address = get(f, col, "소재지도로명주소");
                if (address == null) {
                    address = get(f, col, "소재지지번주소");
                }
                Double units = parseDouble(get(f, col, rc.countCol()));
                int count = units == null || units < 1 ? 1 : (int) Math.min(units, Short.MAX_VALUE);
                Map<String, String> attrs = new LinkedHashMap<>();
                for (String k : rc.attrCols()) {
                    String v = get(f, col, k);
                    if (v != null) {
                        attrs.put(k, v);
                    }
                }
                // 원본에 번호가 없어서 줄 내용으로 번호를 만든다 (똑같은 줄이 두 번 있으면 하나만 들어감)
                batch.add(new FacilityRow(rc.type(), cut(rc.subtypeCol() == null ? null : get(f, col, rc.subtypeCol()), 50),
                        rc.source(), hash(line), cut(get(f, col, rc.nameCol()), 200), cut(address, 300), null, null,
                        lon, lat, count, attrs.isEmpty() ? null : json.writeValueAsString(attrs)));
            }
        }
        insert(batch);
        int loaded = saveSource(rc.type(), rc.source(), rc.title(), rc.api() + " (" + file.getFileName() + ")", rc.license(),
                total, skipped, true);
        log.info("{}: {}건 적재, {}건 제외, {}건 중복", rc.title(), loaded, skipped, total - skipped - loaded);
    }

    /** 따옴표 안의 쉼표를 지키는 간단한 CSV 한 줄 읽기 */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String get(List<String> f, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= f.size()) {
            return null;
        }
        String v = f.get(i).trim();
        return v.isEmpty() ? null : v;
    }

    // ---------- DB ----------

    private void deleteType(String type, String source) {
        String table = FacilityTable.of(type).table();
        int n;
        do {
            // 한 번에 많이 지우면 MySQL이 무거워져서 나눠 지운다
            n = jdbc.update("DELETE FROM " + table + " WHERE source = ? LIMIT 50000", source);
        } while (n > 0);
    }

    /** 표마다 INSERT 문. 개수 칸(camera_count, lamp_count)이 있는 표만 그 칸을 넣는다 */
    static String insertSql(FacilityTable t) {
        String count = t.countColumn() == null ? "" : ", " + t.countColumn();
        String countParam = t.countColumn() == null ? "" : ", ?";
        return "INSERT IGNORE INTO " + t.table()
                + " (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs" + count + ")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ST_SRID(POINT(?, ?), 0), ?" + countParam + ")";
    }

    private void insert(List<FacilityRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        // 한 묶음에는 한 종류만 들어오지만, 섞여 와도 표별로 나눠 넣는다
        Map<String, List<FacilityRow>> byType = new LinkedHashMap<>();
        for (FacilityRow r : rows) {
            byType.computeIfAbsent(r.type(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<FacilityRow>> e : byType.entrySet()) {
            FacilityTable t = FacilityTable.of(e.getKey());
            List<FacilityRow> part = e.getValue();
            jdbc.batchUpdate(insertSql(t), part, part.size(), (ps, r) -> {
                ps.setString(1, r.kind());
                ps.setString(2, r.source());
                ps.setString(3, r.sourceId());
                ps.setString(4, r.name());
                ps.setString(5, r.address());
                ps.setString(6, r.sidoCode());
                ps.setString(7, r.sggCode());
                ps.setDouble(8, r.lon());
                ps.setDouble(9, r.lat());
                ps.setDouble(10, r.lon());
                ps.setDouble(11, r.lat());
                ps.setString(12, r.attrsJson());
                if (t.countColumn() != null) {
                    ps.setInt(13, r.unitCount());
                }
            });
        }
    }

    /** 출처별 현황을 남기고, DB에 실제로 들어간 건수를 돌려준다 (중복으로 빠진 줄은 세지 않음) */
    private int saveSource(String type, String source, String title, String api, String license,
            int total, int skipped, boolean complete) {
        Integer loaded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + FacilityTable.of(type).table() + " WHERE source = ?",
                Integer.class, source);
        jdbc.update("""
                INSERT INTO data_source (type, source, title, api, license, total_count, loaded_count,
                                         skipped_count, complete, loaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON DUPLICATE KEY UPDATE title = VALUES(title), api = VALUES(api), license = VALUES(license),
                  total_count = VALUES(total_count), loaded_count = VALUES(loaded_count),
                  skipped_count = VALUES(skipped_count), complete = VALUES(complete), loaded_at = NOW()
                """, type, source, title, api, license, total, loaded, skipped, complete);
        return loaded;
    }

    // ---------- 값 다듬기 ----------

    private static String clean(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        String v = n.asString().trim();
        return v.isEmpty() || v.equals("-") || v.equalsIgnoreCase("null") ? null : v;
    }

    private static String first(JsonNode item, List<String> keys) {
        for (String k : keys) {
            String v = clean(item.get(k));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 개수 칸 값. 없거나 이상하면 1 */
    private static int count(JsonNode item, String key) {
        Double v = key == null ? null : number(item, key);
        return v == null || v < 1 ? 1 : (int) Math.min(v, Short.MAX_VALUE);
    }

    private static Double number(JsonNode item, String key) {
        return parseDouble(clean(item.get(key)));
    }

    private static Double parseDouble(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "11.0", "1.1320108E7" 같은 숫자 모양 코드를 "11", "11320108"로 */
    private static String code(JsonNode item, String key, int length) {
        if (key == null) {
            return null;
        }
        String v = clean(item.get(key));
        if (v == null) {
            return null;
        }
        try {
            v = new BigDecimal(v).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            // 숫자가 아니면 그대로
        }
        return v.length() >= length ? v.substring(0, length) : null;
    }

    private static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
