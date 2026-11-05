-- 사람이 걸을 수 없는 자동차전용도로를 도로망에서 빼고, 뺀 길은 따로 보관한다 (2026-11-04).
-- OSM 태그 motorroad=yes(자동차전용도로)·expressway=yes(고속화도로)인 길이다. 광주는 모두 trunk 도로
-- (2순환로, 빛가람장성로, 하남진곡산단로, 무진대로, 빛고을대로 등)이고 인도(sidewalk) 표시가 없다.
-- 도로망을 처음 받을 때는 고속도로(motorway)만 빼서 이 길들이 들어가 있었고, 경로 엔진도 이 태그를 보지 않아
-- 걸어서 못 가는 길로 경로가 나올 수 있었다.
--   road_edge_excluded : 뺀 길 (road_edge와 같은 칸 + 원래 id + 뺀 이유)
--   edge_stat_excluded : 뺀 길의 edge_stat 원값 (되돌릴 때 다시 계산하지 않아도 되게)
-- 되돌리려면 road_edge_excluded를 road_edge로, edge_stat_excluded를 edge_stat으로 다시 넣고
-- scripts/build_edge_poi.py를 다시 돌린다.
-- 2026-11-04 학교 DB: motorroad 479개(125.9km), expressway 36개(10.1km) 옮김.

CREATE TABLE IF NOT EXISTS road_edge_excluded (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    edge_id     BIGINT       NULL COMMENT 'road_edge에 있던 때의 id (적재 스크립트가 바로 넣으면 NULL)',
    way_id      BIGINT       NOT NULL COMMENT 'OSM 길 번호',
    seq         INT          NOT NULL COMMENT '그 길 안에서 몇 번째 구간인지 (0부터)',
    from_node   BIGINT       NOT NULL,
    to_node     BIGINT       NOT NULL,
    length_m    DOUBLE       NOT NULL,
    highway     VARCHAR(30)  NOT NULL,
    name        VARCHAR(200) NULL,
    footway     VARCHAR(30)  NULL,
    steps       TINYINT      NOT NULL DEFAULT 0,
    covered     TINYINT      NOT NULL DEFAULT 0,
    bridge      TINYINT      NOT NULL DEFAULT 0,
    surface     VARCHAR(30)  NULL,
    tags        JSON         NULL COMMENT '그 밖의 OSM 태그',
    geom        LINESTRING   NOT NULL SRID 0,
    reason      VARCHAR(20)  NOT NULL COMMENT '뺀 이유: motorroad(자동차전용도로), expressway(고속화도로)',
    excluded_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_road_edge_excluded_way_seq (way_id, seq),
    KEY idx_road_edge_excluded_reason (reason)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '도로망에서 뺀 길 (사람이 걸을 수 없는 자동차전용도로)';

CREATE TABLE IF NOT EXISTS edge_stat_excluded LIKE edge_stat;
ALTER TABLE edge_stat_excluded COMMENT = '도로망에서 뺀 길의 edge_stat 원값 (road_edge_excluded.edge_id로 이어짐)';

-- 이미 들어간 도로망에서 옮긴다 (빈 DB에서는 아무것도 하지 않음)
INSERT IGNORE INTO road_edge_excluded (edge_id, way_id, seq, from_node, to_node, length_m, highway, name, footway,
                                       steps, covered, bridge, surface, tags, geom, reason)
SELECT id, way_id, seq, from_node, to_node, length_m, highway, name, footway, steps, covered, bridge, surface, tags, geom,
       CASE WHEN tags ->> '$.motorroad' = 'yes' THEN 'motorroad' ELSE 'expressway' END
FROM road_edge
WHERE tags ->> '$.motorroad' = 'yes' OR tags ->> '$.expressway' = 'yes';

INSERT IGNORE INTO edge_stat_excluded
SELECT s.* FROM edge_stat s JOIN road_edge_excluded x ON x.edge_id = s.edge_id;

DELETE p FROM edge_poi p JOIN road_edge_excluded x ON x.edge_id = p.edge_id;
DELETE s FROM edge_stat s JOIN road_edge_excluded x ON x.edge_id = s.edge_id;
DELETE e FROM road_edge e JOIN road_edge_excluded x ON x.edge_id = e.id;
