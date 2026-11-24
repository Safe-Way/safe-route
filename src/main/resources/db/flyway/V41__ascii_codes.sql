-- 영문 코드·토큰 칸을 ascii 로 (2026-11-24, 자료형 개선 1·2).
-- 1. guardian_share.token: 대문자·소문자·숫자를 섞은 무작위 32글자인데 칸이 대소문자 무시 비교라 'Ab…'와 'aB…'를 같은 토큰으로 봤다.
--    ascii_bin 이면 글자 그대로 비교한다 (서버가 만든 토큰은 그대로 오므로 동작은 같음).
-- 2. 값이 영문 코드뿐인 칸: 지도 시설 이름(layer, 기본 키), 적재 현황 종류(data_source.type, 기본 키),
--    신고 상태(open·confirmed·hidden), 이동 방법(walk·transit). 서버·스크립트는 모두 소문자 정해진 글자로 넣는다
--    (layer 는 MeService 가 소문자로 맞춤). 2026-11-24 학교 DB 값도 모두 소문자 영문 (읽어서 확인).
-- user_map_layer.layer 는 map_layer.layer 와 조인하므로 같이 바꾼다.
SET NAMES utf8mb4;

ALTER TABLE guardian_share MODIFY token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '보호자 링크에 들어가는 무작위 32자 (서버가 만듦)';
ALTER TABLE map_layer MODIFY layer VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '시설 묶음 이름 (종류:조건). 앱·서버가 이 이름으로 주고받음';
ALTER TABLE user_map_layer MODIFY layer VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'map_layer.layer';
ALTER TABLE data_source MODIFY type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '시설 표 이름';
ALTER TABLE road_report MODIFY status VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'open'
    COMMENT 'open 지도에 보임, confirmed 확인됨, hidden 숨김(잘못 올림·얼굴·차량 번호)';
ALTER TABLE trip_log MODIFY travel VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'walk 걸어서, transit 대중교통';
