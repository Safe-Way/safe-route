-- 게스트 번호 칸을 ascii_bin 으로 (2026-11-24, 공개 배포 준비 DB 6).
-- 게스트 번호는 UUID(36글자, 0-9 a-f -)라 utf8mb4 대소문자 무시 비교가 필요 없고 무겁다.
-- 서버는 모든 입구에서 소문자로 맞춘다(GuestAuthFilter·MeService·WalkSpeedService·GuardianShareService·Turn 등).
-- 2026-11-24 학교 DB: 6개 표 모두 대문자 번호 0개, 소문자로 바꿔도 겹치는 번호 0개, 형식이 다른 번호 0개 (읽어서 확인).
-- 외래 키로 이어진 칸은 자료형이 같아야 해서 외래 키 3개를 잠깐 뺐다가 같은 정의로 다시 건다.
SET NAMES utf8mb4;

ALTER TABLE walk_speed_sample DROP FOREIGN KEY fk_walk_speed_sample_profile;
ALTER TABLE trip_log DROP FOREIGN KEY fk_trip_log_profile;
ALTER TABLE user_map_layer DROP FOREIGN KEY fk_user_map_layer_profile;

ALTER TABLE user_profile MODIFY guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '앱이 처음 실행 때 만든 무작위 번호(UUID). walk_profile과 같은 번호';
ALTER TABLE walk_profile MODIFY guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '앱이 처음 실행 때 만든 무작위 번호(UUID). 사람을 알아볼 수 있는 값이 아님';
ALTER TABLE walk_speed_sample MODIFY guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE trip_log MODIFY guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE user_map_layer MODIFY guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
ALTER TABLE guardian_share MODIFY guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
    COMMENT '공유한 게스트 번호 (내 기록 지우기 때 같이 지움)';
ALTER TABLE guest_token MODIFY guest_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '앱이 처음 실행 때 만든 무작위 번호(UUID)';

ALTER TABLE walk_speed_sample ADD CONSTRAINT fk_walk_speed_sample_profile FOREIGN KEY (guest_id, mode)
    REFERENCES walk_profile (guest_id, mode) ON DELETE CASCADE;
ALTER TABLE trip_log ADD CONSTRAINT fk_trip_log_profile FOREIGN KEY (guest_id)
    REFERENCES user_profile (guest_id) ON DELETE CASCADE;
ALTER TABLE user_map_layer ADD CONSTRAINT fk_user_map_layer_profile FOREIGN KEY (guest_id)
    REFERENCES user_profile (guest_id) ON DELETE CASCADE;
