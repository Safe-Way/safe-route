-- 계단에 휠체어 경사로가 붙어 있는지 (2026-11-03). OSM 태그는 이미 road_edge.tags에 있어서 그 값으로 계산하는 칸만 더한다.
-- 도로망을 다시 넣을 필요가 없고, 다시 넣어도 자동으로 맞춰진다.
--   1    = 이 계단에 휠체어 경사로가 있음 (ramp:wheelchair=yes) → 휠체어 모드에서 빼지 않아도 됨
--   0    = 경사로 없음 (ramp=no 또는 ramp:wheelchair=no)
--   NULL = 모름. 계단이 아닌 길, ramp=yes만 있어 휠체어용인지 모르는 계단,
--          경사로가 따로 그려진 계단(ramp:wheelchair=separate, 그 경사로 길은 도로망에 따로 있음)
-- 2026-11-03 시연 범위 계단 27개: 1 = 0개, 0 = 11개, NULL = 16개
ALTER TABLE road_edge
    ADD COLUMN wheelchair_ramp TINYINT AS (
        CASE
            WHEN steps = 0 THEN NULL
            WHEN tags ->> '$."ramp:wheelchair"' = 'yes' THEN 1
            WHEN tags ->> '$."ramp:wheelchair"' = 'no' OR tags ->> '$.ramp' = 'no' THEN 0
        END) VIRTUAL COMMENT '계단의 휠체어 경사로: 1 있음, 0 없음, NULL 모름 (OSM ramp:wheelchair·ramp)';
