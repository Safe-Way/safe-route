package com.saferoute.walk;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/**
 * 개인 보행 속도 표 SQL (가짜 DB).
 * 무엇을: WalkSpeedRepository 가 walk_profile·walk_speed_sample 표에 맞는 SQL과 값 순서로 읽고 쓰는지.
 * 어떻게: FakeDb(진짜 JdbcTemplate 아래 가짜 연결)로 실행한 SQL·값을 모으고, SQL 글자로 돌려줄 줄을 정한다.
 *        정해 두지 않은 SELECT 는 빈 결과, UPDATE·INSERT·DELETE 는 1줄이다.
 * 왜: ? 순서가 하나만 어긋나도 거리와 시간이 바뀌어 저장되거나 남의 모드 줄을 고치고,
 *     잠금(FOR UPDATE)이 빠지면 동시에 올린 측정 둘이 서로 덮어써 속도가 틀어진다.
 */
@DisplayName("개인 걷기 속도 SQL")
class WalkSpeedRepositoryTests {

    private final FakeDb db = new FakeDb();
    private final WalkSpeedRepository repo = new WalkSpeedRepository(db.jdbc());

    @Test
    @DisplayName("잠그고, 측정을 넣고, 합을 구해 속도를 고친다")
    void lockInsertSumUpdate() {
        // 측정 하나를 올릴 때 서비스가 부르는 순서 그대로: 줄 만들고 잠금 → 측정 넣기 → 합 구하기 → 속도 고치기
        // FOR UPDATE 는 queryForObject 라 줄이 꼭 하나 있어야 해서 정해 준다. 합은 측정 3개, 합 141.0
        db.rows("FOR UPDATE", row("samples", 3))
                .rows("COALESCE(SUM(speed_m_min), 0)", row("n", 3, "s", 141.0));

        repo.lockProfile("g1", "senior");
        assertThat(db.call("INSERT IGNORE INTO walk_profile").params()).containsExactly("g1", "senior");
        assertThat(db.call("FOR UPDATE").params()).containsExactly("g1", "senior");

        repo.insertSample("g1", "senior", 420, 600, 42.0, LocalDate.of(2026, 11, 16));
        assertThat(db.call("INSERT INTO walk_speed_sample").params())
                .containsExactly("g1", "senior", 420, 600, 42.0, LocalDate.of(2026, 11, 16));

        assertThat(repo.sampleSum("g1", "senior")).isEqualTo(new WalkSpeedRepository.SampleSum(3, 141.0));

        repo.updateProfile("g1", "senior", 46.9, 420, 600);   // 46.9 는 서비스가 계산했다고 치는 아무 값
        assertThat(db.call("UPDATE walk_profile").params()).containsExactly(46.9, 420, 600, "g1", "senior");
    }

    @Test
    @DisplayName("정해 둔 개수보다 오래된 측정은 지운다")
    void trimDeletesOlderThanKeepth() {
        // 측정을 끝없이 쌓지 않고 최근 keep(20)개만 남긴다. 20번째로 최근 측정의 id 를 찾아 그보다 오래된 것을 지운다
        repo.trimSamples("g1", "adult", 20); // 20개가 안 되면 지우지 않음
        assertThat(db.calls("DELETE")).isEmpty();
        assertThat(db.call("OFFSET ?").params()).containsExactly("g1", "adult", 19);   // OFFSET 은 0부터라 20번째 = 19

        db.rows("OFFSET ?", row("id", 100L));   // 20번째 측정 id = 100 → id < 100 인 것을 지움
        repo.trimSamples("g1", "adult", 20);
        assertThat(db.call("DELETE FROM walk_speed_sample").params()).containsExactly("g1", "adult", 100L);
    }

    @Test
    @DisplayName("걷기 속도를 찾고 지운다")
    void findAndDelete() {
        // 줄이 없을 때(null)와 줄은 있는데 속도 칸이 비었을 때(Profile(null, 0))를 구별해야 서비스가 기본 속도를 고른다
        assertThat(repo.find("g1", "adult")).isNull();   // 정한 줄이 없으면 빈 결과

        db.rows("SELECT speed_m_min, samples", row("speed_m_min", null, "samples", 0));
        assertThat(repo.find("g1", "adult")).isEqualTo(new WalkSpeedRepository.Profile(null, 0));

        // FakeDb 는 먼저 정한 규칙이 이기므로, 다른 줄을 돌려주려면 새 FakeDb 를 쓴다
        FakeDb db2 = new FakeDb().rows("SELECT speed_m_min", row("speed_m_min", 48.5, "samples", 4))
                .updates("DELETE FROM walk_profile", 4);
        WalkSpeedRepository repo2 = new WalkSpeedRepository(db2.jdbc());
        assertThat(repo2.find("g1", "adult")).isEqualTo(new WalkSpeedRepository.Profile(48.5, 4));
        assertThat(repo2.deleteGuest("g1")).isEqualTo(4);   // 지운 줄 수 = 지운 모드 수 (모드 4개)
    }
}
