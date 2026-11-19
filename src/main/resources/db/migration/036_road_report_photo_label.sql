-- 차·오토바이가 막은 길은 3시간만 피해 가게 한다 (2026-11-19, 결정 57 보탬, 재휘 요청).
-- 인도를 막은 차·오토바이는 '막는 물건'(obstacle)으로 신고되는데, 지금은 신고한 날 하루 내내 모든 모드가 피한다.
-- 차는 금방 빠지므로 하루는 너무 길다. 신고 사진을 분류 모델(ml/report_photo)로 보고 '차량'이면 3시간만 막는다.
--
-- photo_label: 서버(C)가 신고 사진을 모델로 판단한 결과. 지금 쓰는 값은 'vehicle'(차·트럭·버스·오토바이) 하나.
--   모델이 확신하지 못했거나 사진이 없거나 아직 판단 전이면 NULL → 예전처럼 하루.
--   NULL 이 기본이라 서버가 이 칸을 채우기 전까지는 아무것도 바뀌지 않는다 (지금 신고 저장도 그대로 동작).
-- 뷰 road_report_active(033)를 다시 만든다. 바뀐 곳은 live 단계 하나:
--   - 'obstacle' + photo_label 'vehicle' 신고는 올린 뒤 3시간이 지나면 빠진다. 같은 곳에 또 신고가 오면 그 신고부터 3시간.
--   - 같은 곳의 차량 아닌 막는 물건 신고는 그대로 하루 (차량 신고가 빠져도 지점은 남는다).
--   - 밤 11시 신고가 자정에 끊기지 않게 차량 신고의 날짜(d)는 '올린 시각 + 3시간'의 날짜로 센다.
--     그래서 그런 신고는 starts_on 이 다음 날로 보일 수 있다.
--   엔진(MysqlPoiSource)은 뷰를 1분마다 다시 읽으므로 3시간이 지나면 1분 안에 길안내가 다시 열린다.
-- 알려진 한계: 서버의 신고 목록·사진 보관(ReportRepository.stillKept)은 날짜로 세므로 차량 신고도 그날까지는 목록에 보인다.
-- 다시 돌리면 ADD COLUMN 이 이미 있다고 실패한다.
-- 시험: scripts/check_report_active.py (이 파일의 뷰로 계산)
SET NAMES utf8mb4;

ALTER TABLE road_report
    ADD COLUMN photo_label VARCHAR(20) NULL
        COMMENT '신고 사진 분류 결과 (036): vehicle 차·오토바이 → 막는 물건이면 3시간만. 모르면 NULL'
        AFTER reporter_hash;

CREATE OR REPLACE VIEW road_report_active AS
WITH RECURSIVE
base AS (
    -- @@BASE@@
    SELECT r.id, r.report_type, r.lat, r.lon, r.geom, r.edge_id, r.accuracy_m, r.memo, r.status, r.reporter_hash,
           r.photo_label, r.created_at,
           DATE(r.created_at) AS d,
           (SELECT COUNT(*) FROM road_report_photo ph WHERE ph.report_id = r.id) AS photos
    FROM road_report r
    WHERE r.status <> 'hidden'
    -- @@END@@
),
live AS (
    -- 036: 차·오토바이가 막은 신고는 3시간만
    SELECT b.id, b.report_type, b.lat, b.lon, b.geom, b.edge_id, b.accuracy_m, b.memo, b.status, b.reporter_hash,
           b.photo_label, b.created_at,
           CASE WHEN b.report_type = 'obstacle' AND b.photo_label = 'vehicle'
                THEN DATE(DATE_ADD(b.created_at, INTERVAL 3 HOUR)) ELSE b.d END AS d,
           b.photos
    FROM base b
    -- photo_label 이 NULL 이면 '=' 비교가 NULL 이 되어 NOT 이 행을 지우므로 COALESCE 로 빈 글자로 바꿔 비교한다
    WHERE NOT (b.report_type = 'obstacle' AND COALESCE(b.photo_label, '') = 'vehicle'
               AND DATE_ADD(b.created_at, INTERVAL 3 HOUR) <= NOW()   -- @@NOW@@
              )
),
spotted AS (
    SELECT b.*,
           (SELECT MIN(o.id) FROM live o
            WHERE o.report_type = b.report_type AND ST_Distance_Sphere(o.geom, b.geom) <= 30) AS spot_id,
           CASE b.report_type WHEN 'obstacle' THEN 1 WHEN 'accident' THEN 1 WHEN 'construction' THEN 7
                              WHEN 'curb' THEN 30 WHEN 'steep' THEN 30 WHEN 'broken' THEN 30 ELSE 7 END AS days
    FROM live b
),
spot_days AS (
    SELECT spot_id, report_type, days, d, ROW_NUMBER() OVER (PARTITION BY spot_id ORDER BY d) AS rn
    FROM (SELECT DISTINCT spot_id, report_type, days, d FROM spotted) x
),
periods AS (
    SELECT spot_id, report_type, days, rn, d AS starts_on, DATE_ADD(d, INTERVAL days - 1 DAY) AS ends_on
    FROM spot_days WHERE rn = 1
    UNION ALL
    SELECT n.spot_id, n.report_type, n.days, n.rn,
           CASE WHEN n.d > p.ends_on THEN n.d ELSE p.starts_on END,
           CASE WHEN n.d > p.ends_on THEN DATE_ADD(n.d, INTERVAL n.days - 1 DAY)
                WHEN n.report_type = 'construction' AND n.d = p.ends_on THEN DATE_ADD(p.ends_on, INTERVAL 1 DAY)
                ELSE p.ends_on END
    FROM periods p JOIN spot_days n ON n.spot_id = p.spot_id AND n.rn = p.rn + 1
),
latest AS (
    SELECT p.* FROM periods p
    WHERE p.rn = (SELECT MAX(q.rn) FROM spot_days q WHERE q.spot_id = p.spot_id)
)
SELECT l.spot_id,
       l.report_type,
       a.lat, a.lon, a.edge_id,
       l.starts_on, l.ends_on,
       COUNT(s.id)                                        AS report_count,
       COUNT(DISTINCT COALESCE(s.reporter_hash, CONCAT('#', s.id))) AS reporter_count,
       SUM(s.photos)                                      AS photo_count,
       MIN(s.accuracy_m)                                  AS best_accuracy_m,
       MIN(s.created_at)                                  AS first_at,
       MAX(s.created_at)                                  AS last_at,
       SUBSTRING_INDEX(GROUP_CONCAT(s.memo ORDER BY s.created_at DESC SEPARATOR '\n'), '\n', 1) AS last_memo,
       GROUP_CONCAT(DISTINCT s.edge_id ORDER BY s.edge_id) AS edge_ids,
       CASE WHEN COUNT(DISTINCT COALESCE(s.reporter_hash, CONCAT('#', s.id))) >= 2
                 OR SUM(s.status = 'confirmed') > 0 THEN 1 ELSE 0 END AS confirmed,
       CASE WHEN l.report_type IN ('obstacle', 'accident', 'construction') THEN 'adult,child,senior,wheelchair'
            WHEN l.report_type IN ('curb', 'steep', 'broken') THEN 'senior,wheelchair'
            ELSE '' END                                   AS avoid_modes,
       CASE WHEN l.report_type IN ('curb', 'steep', 'broken') THEN 'adult,child'
            WHEN l.report_type IN ('obstacle', 'accident', 'construction') THEN ''
            ELSE 'adult,child,senior,wheelchair' END      AS cost_modes
FROM latest l
JOIN spotted a ON a.id = l.spot_id
JOIN spotted s ON s.spot_id = l.spot_id AND s.d BETWEEN l.starts_on AND l.ends_on
WHERE l.ends_on >= CURDATE()   -- @@TODAY@@
GROUP BY l.spot_id, l.report_type, a.lat, a.lon, a.edge_id, l.starts_on, l.ends_on;
