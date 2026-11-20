package com.saferoute.report;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 신고 사진 종류 판단 (A파트 PR #287 ml/report_photo 모델, 036 road_report.photo_label).
 * 사진을 224×224로 줄이고 labels.json 의 평균·표준편차로 맞춰 model.onnx 에 넣는다.
 * 사진을 가운데 정사각형으로 자르고 224×224로 줄여 labels.json 평균·표준편차로 맞춘다(A파트 참고 코드 ReportPhotoClassifier 와 같은 순서).
 * 가장 높은 확률이 {@link #MIN_PROB} 이상이고 그게 vehicle 이면 "vehicle", 아니면 null.
 * <p>
 * 모델 파일 찾는 곳: report.photo-model-dir(REPORT_PHOTO_MODEL_DIR) 폴더가 정해져 있으면 그 폴더,
 * 아니면 jar 안 models/report_photo/. 둘 다 없거나 읽다가 실패하면 판단하지 않는다(늘 null).
 * 사진 판단이 안 돼도 신고는 그대로 받는다 (결정 57: 신고를 막지 않음). 사진은 서버 밖으로 나가지 않는다.
 */
@Component
public class PhotoClassifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PhotoClassifier.class);

    static final String VEHICLE = "vehicle";
    /**
     * 이 확률보다 낮으면 확신이 없다고 보고 쓰지 않는다. 차량으로 잘못 보면 막는 물건 신고가 3시간 만에 풀려 위험해서 높게 잡음.
     * A파트 v4 모델, 처음 보는 장소 사진: 0.8 → 차량 84% 잡고 막는 물건을 차량으로 2.2%, 0.95 → 68%·0.3% (11-20 결정 0.95).
     */
    static final double MIN_PROB = 0.95;
    static final String CLASSPATH_DIR = "models/report_photo/";
    static final int DEFAULT_SIZE = 224;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final List<String> labels;
    private final float[] mean;
    private final float[] std;
    private final int size;

    @Autowired // 생성자가 둘이라 스프링이 쓸 것을 정해 준다
    public PhotoClassifier(@Value("${report.photo-model-dir:}") String dir) {
        this(load(dir));
    }

    private PhotoClassifier(Model m) {
        this.env = m == null ? null : m.env;
        this.session = m == null ? null : m.session;
        this.inputName = m == null ? null : m.inputName;
        this.labels = m == null ? List.of() : m.labels;
        this.mean = m == null ? null : m.mean;
        this.std = m == null ? null : m.std;
        this.size = m == null ? DEFAULT_SIZE : m.size;
    }

    /** 모델 없이 (늘 null, 시험용) */
    public static PhotoClassifier off() {
        return new PhotoClassifier((Model) null);
    }

    /** 모델을 읽었는지 (false 면 vehicleLabel 은 늘 null) */
    public boolean ready() {
        return session != null;
    }

    /** 판단 결과 한 장: 가장 높은 종류와 그 확률 */
    public record Guess(String label, double prob) {
    }

    /**
     * 신고 사진들 → photo_label. 한 장이라도 확신 95% 이상으로 vehicle 이면 "vehicle", 아니면 null.
     * 모델이 없거나, 사진이 없거나, 사진을 못 읽으면 null. 예외를 밖으로 던지지 않는다.
     */
    public String vehicleLabel(List<NewReport.Photo> photos) {
        if (!ready() || photos == null) {
            return null;
        }
        for (NewReport.Photo p : photos) {
            Guess g = guess(p.data());
            if (g != null) {
                // 사진 내용·위치는 남기지 않고 판단 결과만 (현장 시험 때 로그로 확인)
                log.info("신고 사진 판단: {} {}%", g.label(), Math.round(g.prob() * 100));
            }
            if (g != null && VEHICLE.equals(g.label()) && g.prob() >= MIN_PROB) {
                return VEHICLE;
            }
        }
        return null;
    }

    /** 사진 한 장 판단. 모델이 없거나 그림을 못 읽으면(HEIC·WEBP 등) null */
    public Guess guess(byte[] image) {
        if (!ready() || image == null || image.length == 0) {
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            if (img == null) {
                return null; // 자바 기본으로는 JPG·PNG 만 읽힌다
            }
            img = upright(img, ExifOrientation.of(image));
            float[] probs = run(pixels(resize(centerSquare(img), size)));
            int best = 0;
            for (int i = 1; i < probs.length; i++) {
                if (probs[i] > probs[best]) {
                    best = i;
                }
            }
            String label = best < labels.size() ? labels.get(best) : String.valueOf(best);
            return new Guess(label, probs[best]);
        } catch (Exception | LinkageError e) {
            log.warn("신고 사진 판단 실패 (신고는 그대로 저장): {}", e.toString());
            return null;
        }
    }

    private float[] run(float[] chw) throws Exception {
        long[] shape = {1, 3, size, size};
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), shape);
             OrtSession.Result r = session.run(Map.of(inputName, t))) {
            float[][] out = (float[][]) r.get(0).getValue();
            return probabilities(out[0]);
        }
    }

    /** 모델이 확률을 내면 그대로, 점수(logit)를 내면 softmax 로 확률로 바꾼다 */
    static float[] probabilities(float[] v) {
        double sum = 0;
        boolean probs = true;
        for (float x : v) {
            if (x < 0 || x > 1) {
                probs = false;
            }
            sum += x;
        }
        if (probs && Math.abs(sum - 1) < 1e-3) {
            return v;
        }
        float max = Float.NEGATIVE_INFINITY;
        for (float x : v) {
            max = Math.max(max, x);
        }
        double total = 0;
        double[] e = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            e[i] = Math.exp(v[i] - max);
            total += e[i];
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (e[i] / total);
        }
        return out;
    }

    /** RGB 사진 → [3][size][size] 순서로 펼친 값, (픽셀/255 - 평균) / 표준편차 */
    float[] pixels(BufferedImage img) {
        int n = size * size;
        float[] out = new float[3 * n];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int rgb = img.getRGB(x, y);
                int i = y * size + x;
                out[i] = (((rgb >> 16) & 0xFF) / 255f - mean[0]) / std[0];
                out[n + i] = (((rgb >> 8) & 0xFF) / 255f - mean[1]) / std[1];
                out[2 * n + i] = ((rgb & 0xFF) / 255f - mean[2]) / std[2];
            }
        }
        return out;
    }

    /** 가운데 정사각형(짧은 변 길이)으로 자른다. 학습 사진이 정사각형이라 A파트 참고 코드와 같게 */
    static BufferedImage centerSquare(BufferedImage src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        return src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
    }

    /**
     * size×size 로 줄인다 (가운데 정사각형으로 자른 뒤 부른다).
     * 큰 사진을 한 번에 줄이면 계단 무늬가 생겨서 절반씩 여러 번 줄인 뒤 마지막에 맞춘다.
     */
    static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage cur = toRgb(src);
        int w = cur.getWidth();
        int h = cur.getHeight();
        while (w / 2 >= size && h / 2 >= size) {
            w /= 2;
            h /= 2;
            cur = draw(cur, w, h);
        }
        return draw(cur, size, size);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(java.awt.Color.WHITE); // 투명한 PNG 는 흰 바탕
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    /** 휴대폰 사진은 픽셀은 옆으로 두고 '돌려서 보라'는 표시(EXIF)만 붙인 경우가 있어 바로 세운다 */
    static BufferedImage upright(BufferedImage img, int orientation) {
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        int nw = w;
        int nh = h;
        switch (orientation) {
            case 3 -> {
                t.translate(w, h);
                t.rotate(Math.PI);
            }
            case 6 -> {
                nw = h;
                nh = w;
                t.translate(h, 0);
                t.rotate(Math.PI / 2);
            }
            case 8 -> {
                nw = h;
                nh = w;
                t.translate(0, w);
                t.rotate(-Math.PI / 2);
            }
            default -> {
                return img; // 1(그대로)과 드문 뒤집힘(2·4·5·7)은 그대로 둔다
            }
        }
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(img, t, null);
        g.dispose();
        return dst;
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.debug("사진 모델 닫기 실패: {}", e.toString());
            }
        }
    }

    // ---- 모델 읽기 ----

    private record Model(OrtEnvironment env, OrtSession session, String inputName, List<String> labels,
                         float[] mean, float[] std, int size) {
    }

    private static Model load(String dir) {
        byte[] model;
        byte[] labelsJson;
        String where;
        try {
            if (dir != null && !dir.isBlank()) {
                Path d = Path.of(dir.trim());
                Path m = d.resolve("model.onnx");
                Path l = d.resolve("labels.json");
                if (!Files.isRegularFile(m) || !Files.isRegularFile(l)) {
                    log.warn("신고 사진 모델이 {} 에 없어 사진 종류를 판단하지 않아요 (model.onnx, labels.json 필요)", d);
                    return null;
                }
                model = Files.readAllBytes(m);
                labelsJson = Files.readAllBytes(l);
                where = d.toString();
            } else {
                model = resource(CLASSPATH_DIR + "model.onnx");
                labelsJson = resource(CLASSPATH_DIR + "labels.json");
                if (model == null || labelsJson == null) {
                    log.info("신고 사진 모델이 없어 사진 종류를 판단하지 않아요 ({}model.onnx·labels.json)", CLASSPATH_DIR);
                    return null;
                }
                where = "classpath:" + CLASSPATH_DIR;
            }
        } catch (IOException e) {
            log.warn("신고 사진 모델 파일을 못 읽었어요: {}", e.toString());
            return null;
        }
        try {
            Labels lb = Labels.parse(labelsJson);
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opt = new OrtSession.SessionOptions();
            opt.setIntraOpNumThreads(1); // 사진 한 장 몇 ms 라 한 줄로 충분, 서버의 다른 일을 방해하지 않게
            OrtSession s = env.createSession(model, opt);
            String input = s.getInputNames().iterator().next();
            log.info("신고 사진 모델 읽음: {} (종류 {}개: {})", where, lb.labels().size(), lb.labels());
            return new Model(env, s, input, lb.labels(), lb.mean(), lb.std(), lb.size());
        } catch (Exception | LinkageError e) {
            // LinkageError: 서버 CPU·운영체제에 맞는 ONNX Runtime 이 없을 때
            log.warn("신고 사진 모델을 못 켰어요 (사진 종류 판단 없이 신고만 받아요): {}", e.toString());
            return null;
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = PhotoClassifier.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** labels.json (train.py export_onnx): classes(모델 출력 순서)·mean·std(RGB)·input [1,3,224,224] */
    record Labels(List<String> labels, float[] mean, float[] std, int size) {

        static Labels parse(byte[] json) {
            JsonNode root = JsonMapper.builder().build().readTree(json);
            JsonNode names = first(root, "labels", "classes", "class_names", "names");
            List<String> labels = new ArrayList<>();
            if (names != null && names.isArray()) {
                names.forEach(n -> labels.add(n.isString() ? n.asString() : first(n, "name", "label", "id").asString()));
            } else if (names != null && names.isObject()) {
                // {"0": "construction", "1": ...} 처럼 번호→이름이면 번호 순으로
                for (int i = 0; names.has(String.valueOf(i)); i++) {
                    labels.add(names.get(String.valueOf(i)).asString());
                }
            }
            if (labels.isEmpty()) {
                throw new IllegalArgumentException("labels.json 에 종류 목록(labels)이 없어요");
            }
            float[] mean = three(first(root, "mean", "image_mean"), "mean");
            float[] std = three(first(root, "std", "image_std"), "std");
            JsonNode s = first(root, "input_size", "image_size", "size", "input");
            int size = DEFAULT_SIZE;
            if (s != null && s.isNumber()) {
                size = s.asInt();
            } else if (s != null && s.isArray() && !s.isEmpty()) {
                size = s.get(s.size() - 1).asInt();
            }
            return new Labels(List.copyOf(labels), mean, std, size);
        }

        private static JsonNode first(JsonNode n, String... keys) {
            if (n == null) {
                return null;
            }
            for (String k : keys) {
                if (n.has(k)) {
                    return n.get(k);
                }
            }
            JsonNode pre = n.get("preprocess");
            return pre != null && pre != n ? first(pre, keys) : null;
        }

        private static float[] three(JsonNode a, String name) {
            if (a == null || !a.isArray() || a.size() != 3) {
                throw new IllegalArgumentException("labels.json 의 " + name + " 는 숫자 3개(R,G,B)여야 해요");
            }
            float[] out = new float[3];
            for (int i = 0; i < 3; i++) {
                out[i] = (float) a.get(i).asDouble();
            }
            if (name.equals("std")) {
                for (float v : out) {
                    if (v <= 0) {
                        throw new IllegalArgumentException("labels.json 의 std 는 0보다 커야 해요");
                    }
                }
            }
            return out;
        }
    }

    /** JPG 의 EXIF 방향 값(1~8)만 읽는다. 없거나 못 읽으면 1 */
    static final class ExifOrientation {

        private ExifOrientation() {
        }

        static int of(byte[] d) {
            try {
                if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
                    return 1;
                }
                int p = 2;
                while (p + 4 <= d.length && (d[p] & 0xFF) == 0xFF) {
                    int marker = d[p + 1] & 0xFF;
                    int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
                    if (marker == 0xDA || len < 2) {
                        return 1; // 그림 데이터 시작, 더 앞에 EXIF 가 없었음
                    }
                    if (marker == 0xE1 && p + 10 <= d.length && d[p + 4] == 'E' && d[p + 5] == 'x'
                            && d[p + 6] == 'i' && d[p + 7] == 'f') {
                        return tiff(d, p + 10, Math.min(d.length, p + 2 + len));
                    }
                    p += 2 + len;
                }
            } catch (RuntimeException e) {
                // 깨진 EXIF 는 무시
            }
            return 1;
        }

        private static int tiff(byte[] d, int t, int end) {
            boolean le = d[t] == 'I';
            int ifd = t + u32(d, t + 4, le);
            int count = u16(d, ifd, le);
            for (int i = 0; i < count; i++) {
                int e = ifd + 2 + i * 12;
                if (e + 12 > end) {
                    break;
                }
                if (u16(d, e, le) == 0x0112) {
                    int v = u16(d, e + 8, le);
                    return v >= 1 && v <= 8 ? v : 1;
                }
            }
            return 1;
        }

        private static int u16(byte[] d, int i, boolean le) {
            return le ? (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8) : ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
        }

        private static int u32(byte[] d, int i, boolean le) {
            return le ? u16(d, i, true) | (u16(d, i + 2, true) << 16) : (u16(d, i, false) << 16) | u16(d, i + 2, false);
        }
    }
}
