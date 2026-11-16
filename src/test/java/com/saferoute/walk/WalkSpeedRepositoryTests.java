package com.saferoute.walk;

import static com.saferoute.testsupport.FakeDb.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.saferoute.testsupport.FakeDb;

/** 개인 보행 속도 표 SQL (가짜 DB) */
class WalkSpeedRepositoryTests {

    private final FakeDb db = new FakeDb();
    private final WalkSpeedRepository repo = new WalkSpeedRepository(db.jdbc());

    @Test
    void lockInsertSumUpdate() {
        db.rows("FOR UPDATE", row("samples", 3))
                .rows("COALESCE(SUM(speed_m_min), 0)", row("n", 3, "s", 141.0));

        repo.lockProfile("g1", "senior");
        assertThat(db.call("INSERT IGNORE INTO walk_profile").params()).containsExactly("g1", "senior");
        assertThat(db.call("FOR UPDATE").params()).containsExactly("g1", "senior");

        repo.insertSample("g1", "senior", 420, 600, 42.0, LocalDate.of(2026, 11, 16));
        assertThat(db.call("INSERT INTO walk_speed_sample").params())
                .containsExactly("g1", "senior", 420, 600, 42.0, LocalDate.of(2026, 11, 16));

        assertThat(repo.sampleSum("g1", "senior")).isEqualTo(new WalkSpeedRepository.SampleSum(3, 141.0));

        repo.updateProfile("g1", "senior", 46.9, 420, 600);
        assertThat(db.call("UPDATE walk_profile").params()).containsExactly(46.9, 420, 600, "g1", "senior");
    }

    @Test
    void trimDeletesOlderThanKeepth() {
        repo.trimSamples("g1", "adult", 20); // 20개가 안 되면 지우지 않음
        assertThat(db.calls("DELETE")).isEmpty();
        assertThat(db.call("OFFSET ?").params()).containsExactly("g1", "adult", 19);

        db.rows("OFFSET ?", row("id", 100L));
        repo.trimSamples("g1", "adult", 20);
        assertThat(db.call("DELETE FROM walk_speed_sample").params()).containsExactly("g1", "adult", 100L);
    }

    @Test
    void findAndDelete() {
        assertThat(repo.find("g1", "adult")).isNull();

        db.rows("SELECT speed_m_min, samples", row("speed_m_min", null, "samples", 0));
        assertThat(repo.find("g1", "adult")).isEqualTo(new WalkSpeedRepository.Profile(null, 0));

        FakeDb db2 = new FakeDb().rows("SELECT speed_m_min", row("speed_m_min", 48.5, "samples", 4))
                .updates("DELETE FROM walk_profile", 4);
        WalkSpeedRepository repo2 = new WalkSpeedRepository(db2.jdbc());
        assertThat(repo2.find("g1", "adult")).isEqualTo(new WalkSpeedRepository.Profile(48.5, 4));
        assertThat(repo2.deleteGuest("g1")).isEqualTo(4);
    }
}
