-- 한 표(facility)에 모아 두던 시설을 종류별 표로 옮긴다. (2026-10-29, 한 번만 실행)
-- 먼저 schema.sql로 새 표를 만든 뒤 실행한다:
--   mysql ... < src/main/resources/db/schema.sql
--   mysql ... < src/main/resources/db/migration/001_split_facility.sql
-- 옮긴 건수를 확인한 뒤 맨 아래 DROP 문으로 옛 표를 지운다.

INSERT IGNORE INTO streetlight (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs FROM facility WHERE type = 'streetlight';

INSERT IGNORE INTO security_light (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs, lamp_count)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs, unit_count FROM facility WHERE type = 'security_light';

INSERT IGNORE INTO cctv (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs, camera_count)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs, unit_count FROM facility WHERE type = 'cctv';

INSERT IGNORE INTO police (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs FROM facility WHERE type = 'police';

INSERT IGNORE INTO emergency_bell (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs FROM facility WHERE type = 'emergency_bell';

INSERT IGNORE INTO convenience_store (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs FROM facility WHERE type = 'convenience_store';

INSERT IGNORE INTO child_safety_house (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs FROM facility WHERE type = 'child_safety_house';

INSERT IGNORE INTO heat_shelter (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs FROM facility WHERE type = 'heat_shelter';

INSERT IGNORE INTO cold_shelter (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs FROM facility WHERE type = 'cold_shelter';

INSERT IGNORE INTO public_toilet (kind, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs)
SELECT subtype, source, source_id, name, address, sido_code, sgg_code, lon, lat, geom, attrs FROM facility WHERE type = 'public_toilet';

-- 확인: 두 칸의 숫자가 같아야 한다
SELECT f.type, f.n AS old_count, a.n AS new_count
FROM (SELECT type, COUNT(*) n FROM facility GROUP BY type) f
LEFT JOIN (SELECT type, COUNT(*) n FROM facility_all GROUP BY type) a ON a.type = f.type
ORDER BY f.type;

-- 숫자가 모두 같으면 옛 표를 지운다
-- DROP TABLE facility;
