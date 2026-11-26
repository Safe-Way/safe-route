package com.saferoute.report;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 신고 사진 종류 판단. 진짜 모델(6MB) 대신 시험용 작은 모델(src/test/resources/report_photo_test)을 쓴다:
 * 사진 평균색이 빨가면 vehicle, 파라면 obstacle, 회색이면 확신 없음(모두 20%).
 */
class PhotoClassifierTests {

    static final String TEST_DIR = "src/test/resources/report_photo_test";
    static PhotoClassifier c;

    @BeforeAll
    static void load() {
        c = new PhotoClassifier(Path.of(TEST_DIR).toAbsolutePath().toString());
    }

    @AfterAll
    static void close() {
        c.close();
    }

    @Test
    void redPhotoIsVehicle() throws IOException {
        assertTrue(c.ready());
        PhotoClassifier.Guess g = c.guess(jpg(Color.RED, 1200, 900));
        assertNotNull(g);
        assertEquals("vehicle", g.label());
        assertTrue(g.prob() >= PhotoClassifier.MIN_PROB, "확률 " + g.prob());
        assertEquals("vehicle", c.vehicleLabel(List.of(photo(jpg(Color.RED, 640, 480)))));
    }

    @Test
    void otherKindIsNull() throws IOException {
        PhotoClassifier.Guess g = c.guess(png(Color.BLUE));
        assertEquals("obstacle", g.label());
        assertNull(c.vehicleLabel(List.of(photo(png(Color.BLUE)))));
    }

    @Test
    void unsureIsNull() throws IOException {
        PhotoClassifier.Guess g = c.guess(jpg(Color.GRAY, 300, 300));
        assertTrue(g.prob() < PhotoClassifier.MIN_PROB, "확률 " + g.prob());
        assertNull(c.vehicleLabel(List.of(photo(jpg(Color.GRAY, 300, 300)))));
    }

    @Test
    void anyConfidentVehiclePhotoCounts() throws IOException {
        assertEquals("vehicle", c.vehicleLabel(List.of(photo(jpg(Color.GRAY, 300, 300)), photo(jpg(Color.RED, 300, 300)))));
    }

    @Test
    void noPhotosOrUnreadablePhotoIsNull() {
        assertNull(c.vehicleLabel(List.of()));
        assertNull(c.vehicleLabel(null));
        byte[] heic = "\0\0\0\u0018ftypheic________".getBytes(StandardCharsets.ISO_8859_1);
        assertNull(c.guess(heic));
        assertNull(c.vehicleLabel(List.of(new NewReport.Photo("image/heic", heic))));
        assertNull(c.guess(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3}));
    }

    @Test
    void missingModelMeansNoOp() throws IOException {
        try (PhotoClassifier none = new PhotoClassifier("/no/such/folder")) {
            assertFalse(none.ready());
            assertNull(none.vehicleLabel(List.of(photo(jpg(Color.RED, 100, 100)))));
        }
        assertFalse(PhotoClassifier.off().ready());
    }

    @Test
    void realModelInJarLoads() throws IOException {
        // src/main/resources/models/report_photo/ (A파트 v6 모델). 단색 사진은 차량이 아니어야 함
        try (PhotoClassifier real = new PhotoClassifier("")) {
            assertTrue(real.ready());
            PhotoClassifier.Guess g = real.guess(jpg(Color.GRAY, 1200, 900));
            assertNotNull(g);
            assertNull(real.vehicleLabel(List.of(photo(jpg(Color.GRAY, 1200, 900)))));
        }
    }

    @Test
    void centerSquareCrop() {
        BufferedImage wide = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        BufferedImage sq = PhotoClassifier.centerSquare(wide);
        assertEquals(300, sq.getWidth());
        assertEquals(300, sq.getHeight());
    }

    @Test
    void softmaxWhenModelGivesScores() {
        float[] p = PhotoClassifier.probabilities(new float[] {0, 0, 10});
        assertTrue(p[2] > 0.99f);
        float[] already = {0.1f, 0.9f};
        assertArrayEquals(already, PhotoClassifier.probabilities(already));
    }

    @Test
    void labelsJsonShapes() {
        PhotoClassifier.Labels a = PhotoClassifier.Labels.parse(("{\"classes\":{\"0\":\"construction\",\"1\":\"vehicle\"},"
                + "\"preprocess\":{\"mean\":[0.485,0.456,0.406],\"std\":[0.229,0.224,0.225],\"size\":[224,224]}}")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("construction", "vehicle"), a.labels());
        assertEquals(0.485f, a.mean()[0], 1e-6);
        assertEquals(224, a.size());
    }

    @Test
    void realLabelsJsonFromTrainPy() {
        PhotoClassifier.Labels a = PhotoClassifier.Labels.parse(("{\"classes\": [\"construction\", \"broken\", \"obstacle\","
                + " \"vehicle\", \"curb\", \"none\"], \"names\": {\"vehicle\": \"차량\"}, \"input\": [1, 3, 224, 224],"
                + " \"layout\": \"NCHW RGB 0~1\", \"mean\": [0.485, 0.456, 0.406], \"std\": [0.229, 0.224, 0.225],"
                + " \"output\": \"logits\"}").getBytes(StandardCharsets.UTF_8));
        assertEquals("vehicle", a.labels().get(3));
        assertEquals(6, a.labels().size());
        assertEquals(224, a.size());
        assertEquals(0.225f, a.std()[2], 1e-6);
    }

    @Test
    void exifOrientationTurnsPhotoUpright() {
        byte[] jpg = exifJpegHeader(6);
        assertEquals(6, PhotoClassifier.ExifOrientation.of(jpg));
        assertEquals(1, PhotoClassifier.ExifOrientation.of(new byte[] {1, 2, 3}));
        BufferedImage wide = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        BufferedImage up = PhotoClassifier.upright(wide, 6);
        assertEquals(20, up.getWidth());
        assertEquals(40, up.getHeight());
    }

    // ---- 시험 사진 만들기 ----

    static NewReport.Photo photo(byte[] d) {
        return new NewReport.Photo("image/jpeg", d);
    }

    static byte[] jpg(Color color, int w, int h) throws IOException {
        return image(color, w, h, "jpg");
    }

    static byte[] png(Color color) throws IOException {
        return image(color, 500, 500, "png");
    }

    static byte[] png(Color color, int w, int h) throws IOException {
        return image(color, w, h, "png");
    }

    private static byte[] image(Color color, int w, int h, String format) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** SOI + APP1(Exif, 리틀 엔디언 TIFF, 방향 태그 하나) */
    private static byte[] exifJpegHeader(int orientation) {
        byte[] tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
                0, 0, 0, 0};
        int len = 2 + 6 + tiff.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len,
                'E', 'x', 'i', 'f', 0, 0});
        o.writeBytes(tiff);
        o.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 2});
        return o.toByteArray();
    }

    @Test
    @DisplayName("사진이 작으면 크기를 줄이지 않고 그대로 읽는다")
    void smallPhotoIsReadAsIs() throws IOException {
        assertEquals(1, PhotoClassifier.skipStep(4032L * 3024L, PhotoClassifier.MAX_PIXELS));   // 휴대폰 1,200만 화소
        BufferedImage img = PhotoClassifier.decode(jpg(Color.RED, 1200, 900), PhotoClassifier.MAX_PIXELS);
        assertEquals(1200, img.getWidth());
        assertEquals(900, img.getHeight());
    }

    @Test
    @DisplayName("한도보다 큰 사진은 건너뛰며 읽어 한도 안으로 줄인다")
    void bigPhotoIsSubsampled() throws IOException {
        // 100×80 = 8,000 픽셀, 한도 1,000 → 3칸마다 한 칸 (34×27 = 918)
        assertEquals(3, PhotoClassifier.skipStep(8_000, 1_000));
        BufferedImage img = PhotoClassifier.decode(png(Color.BLUE, 100, 80), 1_000);
        assertEquals(34, img.getWidth());
        assertEquals(27, img.getHeight());
    }

    @Test
    @DisplayName("파일은 작은데 픽셀이 아주 큰 PNG도 메모리를 터뜨리지 않고 판단한다")
    void pixelBombPngIsJudgedSafely() throws IOException {
        // 8,200×8,200 빨간 PNG: 파일은 1MB 안팎인데 그대로 풀면 약 270MB(픽셀당 4바이트), 복사까지 하면 그 몇 배
        int side = 8_200;
        byte[] bomb = solidPng(side, side, 255, 0, 0);
        assertTrue(bomb.length < 5 * 1024 * 1024, "신고 사진 한 장 한도(5MB) 안이어야 시험 뜻이 있음: " + bomb.length);

        BufferedImage img = PhotoClassifier.decode(bomb, PhotoClassifier.MAX_PIXELS);
        assertTrue((long) img.getWidth() * img.getHeight() <= PhotoClassifier.MAX_PIXELS,
                img.getWidth() + "×" + img.getHeight());

        PhotoClassifier.Guess g = c.guess(bomb);   // 줄여 읽어도 판단은 그대로
        assertNotNull(g);
        assertEquals("vehicle", g.label());
    }

    /** 한 가지 색으로 가득 찬 RGB PNG 를 픽셀을 메모리에 다 만들지 않고 바로 쓴다 (줄마다 압축해 흘려보냄) */
    private static byte[] solidPng(int w, int h, int r, int g, int b) throws IOException {
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        byte[] row = new byte[1 + w * 3];   // 맨 앞 1바이트 = 줄 필터(0, 없음)
        for (int x = 0; x < w; x++) {
            row[1 + x * 3] = (byte) r;
            row[2 + x * 3] = (byte) g;
            row[3 + x * 3] = (byte) b;
        }
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat, new Deflater(Deflater.BEST_SPEED))) {
            for (int y = 0; y < h; y++) {
                z.write(row);
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(png, "IHDR", ihdr.array());
        chunk(png, "IDAT", idat.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
