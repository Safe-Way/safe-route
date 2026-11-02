-- 길마다 주변 시설 (poi) 묶음. 경로 응답의 시설 목록, "80m 앞 편의점" 안내, "쉬는 곳이 300m마다" 설명을
-- 경로의 길 id만 모아 바로 만들 수 있게 한다. scripts/build_edge_poi.py가 만든다 (poi를 다시 만들면 id가 바뀌므로 이것도 다시 만듦).
-- 등(light)은 너무 많아 넣지 않고 edge_stat의 수로 본다.
CREATE TABLE IF NOT EXISTS edge_poi (
    edge_id BIGINT   NOT NULL COMMENT 'road_edge.id',
    poi_id  INT      NOT NULL COMMENT 'poi.id',
    kind    VARCHAR(20) NOT NULL COMMENT 'poi.kind (조회 편하게 같이 둠)',
    dist_m  DOUBLE   NOT NULL COMMENT '길에서 시설까지 가장 가까운 거리 m (50m 안)',
    pos     DOUBLE   NOT NULL COMMENT '길 위 위치: from_node 0 → to_node 1 (가장 가까운 점 기준)',
    PRIMARY KEY (edge_id, poi_id),
    KEY idx_edge_poi_poi (poi_id),
    KEY idx_edge_poi_kind (kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '길별 주변 시설';
