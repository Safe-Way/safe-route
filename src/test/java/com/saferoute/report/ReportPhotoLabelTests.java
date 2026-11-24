package com.saferoute.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 신고 저장 때 사진 종류(036 photo_label)를 남기는지, 무슨 일이 있어도 신고는 저장되는지 (DB 없이) */
class ReportPhotoLabelTests {

    static PhotoClassifier model;

    @BeforeAll
    static void load() {
        model = new PhotoClassifier(Path.of(PhotoClassifierTests.TEST_DIR).toAbsolutePath().toString());
    }

    @AfterAll
    static void close() {
        model.close();
    }

    /** DB 대신 부른 것만 적어 두는 저장소 */
    static class FakeRepo extends ReportRepository {
        final List<String> labels = new ArrayList<>();
        /** 부른 차례 (insert·photo·label) 와 트랜잭션 시작·끝 (begin·commit·rollback) */
        final List<String> steps = new ArrayList<>();
        boolean labelFails;
        int inserted;

        FakeRepo() {
            super(null);
        }

        @Override
        public Long nearestEdge(double lat, double lon, int maxM) {
            return null;
        }

        @Override
        public long insert(NewReport r, Long edgeId, String via, String reporterHash) {
            inserted++;
            steps.add("insert");
            return 42;
        }

        @Override
        public void insertPhoto(long reportId, int seq, NewReport.Photo p) {
            steps.add("photo");
        }

        @Override
        public void setPhotoLabel(long reportId, String label) {
            if (labelFails) {
                throw new DataAccessResourceFailureException("DB 끊김");
            }
            steps.add("label");
            labels.add(reportId + ":" + label);
        }
    }

    static NewReport report(String type, byte[]... photos) {
        List<NewReport.Photo> ps = new ArrayList<>();
        for (byte[] p : photos) {
            ps.add(new NewReport.Photo("image/jpeg", p));
        }
        return new NewReport(type, null, null, 35.15, 126.92, null, List.copyOf(ps));
    }

    @Test
    void vehiclePhotoSavesLabel() throws IOException {
        FakeRepo repo = new FakeRepo();
        ReportService s = new ReportService(repo, new ReporterHash(""), model);
        ReportCreated c = s.create(report("obstacle", PhotoClassifierTests.jpg(Color.RED, 800, 600)), null);
        assertEquals(42, c.id());
        assertEquals(List.of("42:vehicle"), repo.labels);
    }

    @Test
    void unsureOrNoPhotoLeavesNull() throws IOException {
        FakeRepo repo = new FakeRepo();
        ReportService s = new ReportService(repo, new ReporterHash(""), model);
        s.create(report("obstacle", PhotoClassifierTests.jpg(Color.GRAY, 300, 300)), null);
        s.create(report("obstacle"), null);
        assertTrue(repo.labels.isEmpty());
        assertEquals(2, repo.inserted);
    }

    @Test
    void labelFailureNeverBlocksReport() throws IOException {
        FakeRepo repo = new FakeRepo();
        repo.labelFails = true;
        ReportService s = new ReportService(repo, new ReporterHash(""), model);
        ReportCreated c = s.create(report("obstacle", PhotoClassifierTests.jpg(Color.RED, 300, 300)), null);
        assertEquals(42, c.id());
        assertEquals(1, c.photoCount());
    }

    @Test
    void noModelMeansNoLabel() throws IOException {
        FakeRepo repo = new FakeRepo();
        new ReportService(repo).create(report("obstacle", PhotoClassifierTests.jpg(Color.RED, 300, 300)), null);
        assertTrue(repo.labels.isEmpty());
        assertEquals(1, repo.inserted);
    }

    @Test
    void listUsesThreeHoursOnlyForVehicleObstacles() {
        String withRule = ReportRepository.stillKept(0, true);
        assertTrue(withRule.contains("n.photo_label = 'vehicle'"));
        assertTrue(withRule.contains("DATE_ADD(n.created_at, INTERVAL 3 HOUR) > NOW()")); // 036 뷰와 같은 기준
        // 지우기 SQL 은 review_reports.py 와 같게 그대로
        assertFalse(ReportRepository.PHOTO_DUE.contains("photo_label"));
        assertFalse(ReportRepository.ROW_DUE.contains("photo_label"));
        assertEquals(ReportRepository.stillKept(0, false), ReportRepository.stillKept(0));
    }

    /** 트랜잭션 시작·끝만 적어 두는 가짜 */
    static org.springframework.transaction.PlatformTransactionManager fakeTx(List<String> steps) {
        return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition d) {
                steps.add("begin");
            }

            @Override
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) {
                steps.add("commit");
            }

            @Override
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) {
                steps.add("rollback");
            }
        };
    }

    @Test
    void photoIsJudgedAfterReportIsCommitted() throws IOException {
        // 사진 판단은 DB 트랜잭션이 끝난 뒤에 (판단이 오래 걸려도 DB 연결을 붙잡지 않게, 2026-11-23)
        FakeRepo repo = new FakeRepo();
        ReportService s = new ReportService(repo, new ReporterHash(""), model, fakeTx(repo.steps));
        s.create(report("obstacle", PhotoClassifierTests.jpg(Color.RED, 300, 300)), null);
        assertEquals(List.of("begin", "insert", "photo", "commit", "label"), repo.steps);
    }

    @Test
    void outOfMemoryWhileJudgingKeepsReport() throws IOException {
        // 아주 큰 사진을 풀다가 메모리가 모자라도 신고는 이미 저장(commit)돼 있고 답도 정상
        FakeRepo repo = new FakeRepo();
        PhotoClassifier broken = org.mockito.Mockito.mock(PhotoClassifier.class);
        org.mockito.Mockito.when(broken.ready()).thenReturn(true);
        org.mockito.Mockito.when(broken.vehicleLabel(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new OutOfMemoryError("Java heap space"));
        ReportService s = new ReportService(repo, new ReporterHash(""), broken, fakeTx(repo.steps));
        ReportCreated c = s.create(report("obstacle", PhotoClassifierTests.jpg(Color.RED, 300, 300)), null);
        assertEquals(42, c.id());
        assertEquals(List.of("begin", "insert", "photo", "commit"), repo.steps);
    }
}
