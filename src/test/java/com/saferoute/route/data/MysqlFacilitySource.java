package com.saferoute.route.data;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import com.saferoute.ingest.FacilityTable;

import com.saferoute.route.engine.facility.Facility;
import com.saferoute.route.engine.geo.BBox;

/**
 * 종류별 원본 시설 표에서 범위 안 시설을 읽는다. 실험 도구(ChosunNetworkBuilder·DecisionTests) 전용이다.
 * 서버는 정제 표 poi를 읽는다 ({@link MysqlPoiSource}).
 * facility_all 뷰 대신 표마다 MBRContains로 물어 공간 인덱스를 타게 한다 (schema.sql 주석).
 */
public class MysqlFacilitySource implements FacilitySource {

    private final JdbcTemplate jdbc;

    public MysqlFacilitySource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Facility> within(BBox box, Set<FacilityTable> types) {
        List<Facility> out = new ArrayList<>();
        for (FacilityTable t : types) {
            // 표 이름·칸 이름은 FacilityTable에 있는 것만 들어가므로 그대로 넣어도 된다
            String count = t.countColumn() == null ? "1" : t.countColumn();
            // 교통·쓰레기·시설·재난 단속용 CCTV는 걷는 사람을 비추지 않아 세지 않는다 (결정 39, 목적 모름은 셈)
            String filter = t == FacilityTable.CCTV ? " AND purpose_group NOT IN ('교통', '쓰레기', '시설·재난')" : "";
            String sql = "SELECT id, lon, lat, " + count + " AS cnt FROM " + t.table()
                    + " WHERE MBRContains(ST_GeomFromText(?, 0), geom)" + filter;
            jdbc.query(sql, rs -> {
                out.add(new Facility(t, rs.getLong("id"), rs.getDouble("lon"), rs.getDouble("lat"),
                        Math.max(1, rs.getInt("cnt"))));
            }, box.toWkt());
        }
        return out;
    }

    @Override
    public LocalDate dataDate() {
        Timestamp at = jdbc.queryForObject("SELECT MAX(loaded_at) FROM data_source", Timestamp.class);
        return at == null ? null : at.toLocalDateTime().toLocalDate();
    }
}
