-- 걸을 수 있는 길 그래프(OSM)를 담는 road_node·road_edge 표를 만든다. (2026-11-02, 한 번만 실행)
-- 시연 지역(국립아시아문화전당 반경 2km)부터 넣는다. 실행 뒤 데이터를 넣는다:
--   python3 scripts/load_road_network.py
-- 길마다 감점 원값을 넣는 edge_stat 표는 B파트와 칸(요구사항 6.5 C1~C6)을 맞춘 뒤 만든다.

-- 점: 길이 만나거나 끝나는 곳, 그리고 횡단보도·엘리베이터처럼 표시가 붙은 점. id는 OSM 노드 번호
CREATE TABLE IF NOT EXISTS road_node (
    id        BIGINT      NOT NULL COMMENT 'OSM 노드 번호',
    lon       DOUBLE      NOT NULL,
    lat       DOUBLE      NOT NULL,
    geom      POINT       NOT NULL SRID 0,
    tags      JSON        NULL COMMENT 'OSM 태그 (표시가 있는 점만)',
    highway   VARCHAR(30) AS (tags ->> '$.highway') VIRTUAL COMMENT 'crossing, traffic_signals, elevator 등',
    crossing  VARCHAR(30) AS (tags ->> '$.crossing') VIRTUAL COMMENT 'traffic_signals, uncontrolled, marked, unmarked 등. NULL = 모름',
    kerb      VARCHAR(20) AS (tags ->> '$.kerb') VIRTUAL COMMENT '보도턱 (lowered, raised, flush 등)',
    PRIMARY KEY (id),
    KEY idx_road_node_highway (highway),
    SPATIAL INDEX sx_road_node_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '도로망 점 (OSM)';

-- 길: 두 점 사이의 한 구간. OSM 길(way)을 교차점과 표시가 있는 점에서 잘랐다. 걷는 방향은 양쪽 모두
CREATE TABLE IF NOT EXISTS road_edge (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    way_id    BIGINT       NOT NULL COMMENT 'OSM 길 번호',
    seq       INT          NOT NULL COMMENT '그 길 안에서 몇 번째 구간인지 (0부터)',
    from_node BIGINT       NOT NULL,
    to_node   BIGINT       NOT NULL,
    length_m  DOUBLE       NOT NULL,
    highway   VARCHAR(30)  NOT NULL COMMENT 'OSM 길 종류 (footway, residential, primary, steps 등)',
    name      VARCHAR(200) NULL,
    footway   VARCHAR(30)  NULL COMMENT 'sidewalk(차도 옆 인도), crossing(횡단 구간) 등',
    steps     TINYINT      NOT NULL DEFAULT 0 COMMENT '계단이면 1',
    covered   TINYINT      NOT NULL DEFAULT 0 COMMENT '덮인 길(covered·tunnel·indoor)이면 1',
    bridge    TINYINT      NOT NULL DEFAULT 0 COMMENT '다리·육교 위면 1',
    surface   VARCHAR(30)  NULL COMMENT '바닥 재질 (asphalt, paving_stones 등)',
    tags      JSON         NULL COMMENT '그 밖의 OSM 태그',
    geom      LINESTRING   NOT NULL SRID 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_road_edge_way_seq (way_id, seq),
    KEY idx_road_edge_from (from_node),
    KEY idx_road_edge_to (to_node),
    KEY idx_road_edge_highway (highway),
    SPATIAL INDEX sx_road_edge_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '도로망 길 구간 (OSM)';
