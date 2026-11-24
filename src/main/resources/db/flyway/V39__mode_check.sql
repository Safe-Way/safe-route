-- 모드 값 검사 (2026-11-24, 공개 배포 준비 DB 7). 지금은 서버 코드만 네 값으로 검사한다.
-- NULL 은 그대로 받는다(CHECK 는 NULL 을 막지 않음). 2026-11-24 학교 DB의 값은 모두 네 값 안 (읽어서 확인).
-- walk_profile → user_profile 외래 키는 부모 줄이 없을 수 있어(걸음 속도만 쓴 사람) 두지 않는다.
ALTER TABLE user_profile
    ADD CONSTRAINT ck_user_profile_mode CHECK (mode IN ('adult', 'child', 'senior', 'wheelchair')),
    ADD CONSTRAINT ck_user_profile_temp_mode CHECK (temp_mode IN ('adult', 'child', 'senior', 'wheelchair'));
ALTER TABLE walk_profile
    ADD CONSTRAINT ck_walk_profile_mode CHECK (mode IN ('adult', 'child', 'senior', 'wheelchair'));
ALTER TABLE walk_speed_sample
    ADD CONSTRAINT ck_walk_speed_sample_mode CHECK (mode IN ('adult', 'child', 'senior', 'wheelchair'));
ALTER TABLE trip_log
    ADD CONSTRAINT ck_trip_log_mode CHECK (mode IN ('adult', 'child', 'senior', 'wheelchair'));
