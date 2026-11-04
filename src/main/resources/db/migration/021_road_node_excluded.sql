-- 도로망에서 뺀 길(road_edge_excluded, 020)에만 쓰이던 점을 road_node에서 빼고 따로 보관한다 (2026-11-04).
-- 020에서 자동차전용도로를 뺀 뒤 이 점들은 어떤 길과도 이어지지 않은 채 road_node에 남아 있었다.
-- 경로 계산은 가장 큰 연결 덩어리에만 붙여 영향이 없었지만, 도로망 점을 골라 쓰는 운영 테스트(RouteRandomCheck)가
-- 이 점을 출발·도착으로 뽑으면 "경로 없음"이 실제보다 많이 나온다.
-- 남은 길(road_edge)이 쓰는 점은 옮기지 않는다 (자동차전용도로와 일반 도로가 만나는 점은 그대로 남음).
-- 되돌리려면 road_node_excluded를 road_node로 다시 넣는다 (020 되돌리기와 같이).
-- 2026-11-04 학교 DB: 371개 옮김 (표시 없는 점 357, 고속도로 나들목 6, 과속 카메라 7, 신호등 1. 횡단보도는 없음).

CREATE TABLE IF NOT EXISTS road_node_excluded (
    id          BIGINT      NOT NULL COMMENT 'OSM 노드 번호 (road_node에 있던 id)',
    lon         DOUBLE      NOT NULL,
    lat         DOUBLE      NOT NULL,
    geom        POINT       NOT NULL SRID 0,
    tags        JSON        NULL COMMENT 'OSM 태그 (표시가 있는 점만)',
    reason      VARCHAR(20) NOT NULL COMMENT '뺀 이유: excluded_edge (뺀 길에만 쓰이던 점)',
    excluded_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    SPATIAL INDEX sx_road_node_excluded_geom (geom)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '도로망에서 뺀 점 (뺀 길에만 쓰이던 점)';

-- 뺀 길의 끝점 중 남은 길이 쓰지 않는 점만 옮긴다 (빈 DB에서는 아무것도 하지 않음)
INSERT IGNORE INTO road_node_excluded (id, lon, lat, geom, tags, reason)
SELECT n.id, n.lon, n.lat, n.geom, n.tags, 'excluded_edge'
FROM road_node n
WHERE n.id IN (SELECT from_node FROM road_edge_excluded UNION SELECT to_node FROM road_edge_excluded)
  AND NOT EXISTS (SELECT 1 FROM road_edge e WHERE e.from_node = n.id)
  AND NOT EXISTS (SELECT 1 FROM road_edge e WHERE e.to_node = n.id);

DELETE n FROM road_node n JOIN road_node_excluded x ON x.id = n.id
WHERE NOT EXISTS (SELECT 1 FROM road_edge e WHERE e.from_node = n.id)
  AND NOT EXISTS (SELECT 1 FROM road_edge e WHERE e.to_node = n.id);
