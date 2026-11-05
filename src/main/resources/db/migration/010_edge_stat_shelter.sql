-- edge_stat에 무더위쉼터·한파쉼터 칸을 따로 더한다. (2026-11-02, 한 번만 실행)
-- 폭염·한파 가중치(요구사항 6.5 "날씨 가중치")에 쓴다. rest_m은 쉼터와 화장실을 합친 값이라 폭염·한파에는 맞지 않는다.
-- 실행 뒤 값을 채운다:  python3 scripts/build_edge_stat.py
ALTER TABLE edge_stat
    ADD COLUMN heat_m     DOUBLE   NULL COMMENT '가장 가까운 무더위쉼터까지 m. 2km 넘으면 NULL' AFTER rest_m,
    ADD COLUMN cold_m     DOUBLE   NULL COMMENT '가장 가까운 한파쉼터까지 m. 2km 넘으면 NULL' AFTER heat_m,
    ADD COLUMN heat_n_1km SMALLINT NOT NULL DEFAULT 0 COMMENT '반경 1km 안 무더위쉼터 수' AFTER rest_n_1km,
    ADD COLUMN cold_n_1km SMALLINT NOT NULL DEFAULT 0 COMMENT '반경 1km 안 한파쉼터 수' AFTER heat_n_1km;
