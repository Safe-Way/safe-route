package com.saferoute.report;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 불편 신고 받기·보여 주기 (FR-49).
 * 로그인이 없어서 누가 올렸는지는 모른다. 앱 번호(guestId)가 오면 비밀값을 섞은 해시(reporter_hash, 033)만 남겨
 * 같은 사람이 여러 번 올린 신고를 한 명으로 센다(뷰 reporter_count·confirmed). 원래 번호는 남기지 않는다.
 * 신고 좌표는 표에 남지만 서버 로그에는 남기지 않는다 (NFR-07).
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    static final int MAX_PHOTOS = 3;
    static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;
    static final int MAX_MEMO = 300;
    static final int EDGE_SNAP_M = 30;
    static final int LIST_LIMIT = 300;
    static final double MAX_SPAN_DEG = 0.2;
    static final Set<String> MODES = Set.of("adult", "child", "senior", "wheelchair");

    private final ReportRepository repo;
    private final ReporterHash reporter;

    @Autowired
    public ReportService(ReportRepository repo, ReporterHash reporter) {
        this.repo = repo;
        this.reporter = reporter;
    }

    /** 신고자 해시 없이 (시험용) */
    public ReportService(ReportRepository repo) {
        this(repo, new ReporterHash(""));
    }

    /** 앱이 보낸 값을 검사해서 NewReport 로 만든다. 틀리면 ReportException(400) */
    NewReport check(String type, Double lat, Double lon, String mode, String memo, Integer accuracyM,
                    List<MultipartFile> files) {
        if (!ReportType.known(type)) {
            throw ReportException.bad("type 은 " + String.join(", ", ReportType.NAMES.keySet()) + " 중 하나여야 해요");
        }
        if (lat == null || lon == null || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw ReportException.bad("lat, lon 이 필요해요");
        }
        String m = blankToNull(mode);
        if (m != null && !MODES.contains(m)) {
            throw ReportException.bad("mode 는 adult, child, senior, wheelchair 중 하나예요");
        }
        String note = blankToNull(memo);
        if (note != null && note.length() > MAX_MEMO) {
            throw ReportException.bad("설명은 " + MAX_MEMO + "자까지 쓸 수 있어요");
        }
        if (accuracyM != null && (accuracyM < 0 || accuracyM > Short.MAX_VALUE)) {
            throw ReportException.bad("accuracyM 값이 맞지 않아요");
        }
        List<NewReport.Photo> photos = new ArrayList<>();
        for (MultipartFile f : files == null ? List.<MultipartFile>of() : files) {
            if (f == null || f.isEmpty()) {
                continue;
            }
            if (photos.size() == MAX_PHOTOS) {
                throw ReportException.bad("사진은 " + MAX_PHOTOS + "장까지 올릴 수 있어요");
            }
            if (f.getSize() > MAX_PHOTO_BYTES) {
                throw new ReportException(HttpStatus.CONTENT_TOO_LARGE, "PHOTO_TOO_BIG", "사진 한 장은 5MB까지예요");
            }
            byte[] data;
            try {
                data = f.getBytes();
            } catch (IOException e) {
                throw ReportException.bad("사진을 읽을 수 없어요");
            }
            String type2 = imageType(data);
            if (type2 == null) {
                throw ReportException.bad("사진은 JPG, PNG, WEBP, HEIC 만 올릴 수 있어요");
            }
            photos.add(new NewReport.Photo(type2, data));
        }
        return new NewReport(type, m, note, lat, lon, accuracyM, List.copyOf(photos));
    }

    /** 신고와 사진을 한 번에 저장한다 (사진 저장이 실패하면 신고도 안 남음) */
    public ReportCreated create(NewReport r) {
        return create(r, null);
    }

    /**
     * 신고와 사진을 한 번에 저장한다.
     *
     * @param guestId 앱 번호. 해시로만 남기고 원래 번호는 저장하지 않는다. 없으면 null
     */
    @Transactional
    public ReportCreated create(NewReport r, String guestId) {
        Long edge = null;
        try {
            edge = repo.nearestEdge(r.lat(), r.lon(), EDGE_SNAP_M);
        } catch (DataAccessException e) {
            // 길 찾기가 안 돼도 신고는 받는다 (edge_id 는 비어도 되는 칸)
            log.warn("신고 가까운 길 찾기 실패: {}", e.getMostSpecificCause().getMessage());
        }
        long id = repo.insert(r, edge, "photo", reporter.of(guestId));
        int seq = 1;
        for (NewReport.Photo p : r.photos()) {
            repo.insertPhoto(id, seq++, p);
        }
        return new ReportCreated(id, "open", r.photos().size());
    }

    /**
     * 비서에게 말로 한 신고 (사진 없음, via = voice). 비서가 "여기에 공사로 신고할까요?"를 묻고 "네"를 들은 뒤 부른다.
     *
     * @param type 유형 코드 (steep, construction, curb, broken, obstacle, accident, other)
     * @return 새 신고 번호
     */
    public long createVoice(String type, String mode, String memo, double lat, double lon) {
        return createVoice(type, mode, memo, lat, lon, null);
    }

    /** 말로 한 신고 + 앱 번호 (해시로만 남김) */
    @Transactional
    public long createVoice(String type, String mode, String memo, double lat, double lon, String guestId) {
        String t = ReportType.known(type) ? type : "other";
        String m = mode != null && MODES.contains(mode) ? mode : null;
        String note = blankToNull(memo);
        if (note != null && note.length() > MAX_MEMO) {
            note = note.substring(0, MAX_MEMO);
        }
        NewReport r = new NewReport(t, m, note, lat, lon, null, List.of());
        Long edge = null;
        try {
            edge = repo.nearestEdge(lat, lon, EDGE_SNAP_M);
        } catch (DataAccessException e) {
            log.warn("말 신고 가까운 길 찾기 실패: {}", e.getMostSpecificCause().getMessage());
        }
        return repo.insert(r, edge, "voice", reporter.of(guestId));
    }

    /** 유형 코드의 이름 ("construction" → "공사") */
    public static String typeName(String type) {
        return ReportType.name(type);
    }

    /** 지도 범위 안 신고 */
    public ReportsResponse within(double west, double south, double east, double north) {
        if (west >= east || south >= north) {
            throw ReportException.bad("bbox 는 서쪽경도,남쪽위도,동쪽경도,북쪽위도 순서예요");
        }
        if (east - west > MAX_SPAN_DEG || north - south > MAX_SPAN_DEG) {
            throw new ReportException(HttpStatus.BAD_REQUEST, "BBOX_TOO_LARGE", "지도를 조금 더 확대해 주세요");
        }
        List<ReportItem> found = repo.within(west, south, east, north, LIST_LIMIT + 1);
        boolean truncated = found.size() > LIST_LIMIT;
        return new ReportsResponse(truncated ? List.copyOf(found.subList(0, LIST_LIMIT)) : found, truncated);
    }

    /** 지도 범위 안 지금 유효한 신고 지점 (같은 곳 묶음, 기간 안) */
    public ActiveSpotsResponse active(double west, double south, double east, double north) {
        if (west >= east || south >= north) {
            throw ReportException.bad("bbox 는 서쪽경도,남쪽위도,동쪽경도,북쪽위도 순서예요");
        }
        if (east - west > MAX_SPAN_DEG || north - south > MAX_SPAN_DEG) {
            throw new ReportException(HttpStatus.BAD_REQUEST, "BBOX_TOO_LARGE", "지도를 조금 더 확대해 주세요");
        }
        List<ActiveSpot> found = repo.active(west, south, east, north, LIST_LIMIT + 1);
        boolean truncated = found.size() > LIST_LIMIT;
        return new ActiveSpotsResponse(truncated ? List.copyOf(found.subList(0, LIST_LIMIT)) : found, truncated);
    }

    /** 사진 한 장, 없거나 숨긴 신고면 null */
    public NewReport.Photo photo(long id, int seq) {
        return repo.photo(id, seq);
    }

    /**
     * 파일 앞부분(매직 넘버)으로 그림 종류를 정한다. 앱이 보낸 종류를 그대로 믿으면
     * 그림이 아닌 파일(예: HTML)이 올라와 사진 주소에서 열릴 수 있어서다.
     */
    static String imageType(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            String brand = new String(d, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (Set.of("heic", "heix", "mif1", "msf1", "hevc").contains(brand)) {
                return "image/heic";
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
