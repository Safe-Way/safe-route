package com.saferoute.guest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 게스트 번호마다 등록된 토큰을 확인한다 (guest_token 표, 037).
 * 그 번호로 처음 온 토큰을 등록하고(처음 쓰는 사람이 주인), 다음부터는 같은 토큰인지만 본다.
 * 토큰 원문은 저장하지 않고 SHA-256 만 둔다. 한 번 읽은 번호는 메모리에 기억해 요청마다 DB를 읽지 않는다.
 */
@Repository
public class GuestTokenStore {

    private static final Logger log = LoggerFactory.getLogger(GuestTokenStore.class);

    /** 토큰 길이 (앱이 32바이트 무작위를 16진수·base64로 보내면 43~64자) */
    static final int MIN_TOKEN = 32;
    static final int MAX_TOKEN = 256;
    /** 메모리에 기억할 게스트 수. 넘으면 오래 안 쓴 것부터 잊는다 (다시 DB에서 읽음) */
    static final int CACHE_SIZE = 20_000;

    public enum Check {
        /** 등록된 토큰과 같음 (또는 방금 처음 등록함) */
        OK,
        /** 토큰을 안 보냄 */
        MISSING,
        /** 토큰이 틀리거나 모양이 맞지 않음 */
        WRONG,
        /** 표가 없거나 DB를 못 읽어 확인을 못 함 → 막지 않음 */
        UNAVAILABLE
    }

    private record Known(String hash, LocalDate touched) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final Map<String, Known> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Known> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    @org.springframework.beans.factory.annotation.Autowired
    public GuestTokenStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    GuestTokenStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Check check(String guestId, String token) {
        if (token == null || token.isBlank()) {
            return Check.MISSING;
        }
        String t = token.strip();
        if (t.length() < MIN_TOKEN || t.length() > MAX_TOKEN) {
            return Check.WRONG;
        }
        String hash = sha256(t);
        LocalDate today = LocalDate.now(clock);
        Known known = cache.get(guestId);
        try {
            if (known == null) {
                String stored = find(guestId);
                if (stored == null) {
                    // 처음 온 토큰을 등록. 두 서버가 동시에 넣으면 먼저 넣은 쪽이 남고, 다시 읽어 그것과 비교한다
                    jdbc.update("INSERT IGNORE INTO guest_token (guest_id, token_hash, last_used_on) VALUES (?, ?, ?)",
                            guestId, hash, today);
                    stored = find(guestId);
                    if (stored == null) {
                        return Check.UNAVAILABLE;
                    }
                }
                known = new Known(stored, today);
                cache.put(guestId, known);
            } else if (!today.equals(known.touched())) {
                jdbc.update("UPDATE guest_token SET last_used_on = ? WHERE guest_id = ?", today, guestId);
                known = new Known(known.hash(), today);
                cache.put(guestId, known);
            }
        } catch (DataAccessException e) {
            if (warned.compareAndSet(false, true)) {
                log.warn("게스트 토큰을 확인하지 못해 건너뛰어요 (guest_token 표가 없거나 DB 오류, 037 적용 확인): {}", e.getMessage());
            }
            return Check.UNAVAILABLE;
        }
        return MessageDigest.isEqual(known.hash().getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII)) ? Check.OK : Check.WRONG;
    }

    /** 내 기록 지우기 등으로 토큰 줄이 지워졌을 때 메모리도 비운다 */
    public void forget(String guestId) {
        cache.remove(guestId);
    }

    private String find(String guestId) {
        return jdbc.query("SELECT token_hash FROM guest_token WHERE guest_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, guestId);
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
