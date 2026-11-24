package com.saferoute.report;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 같은 사람이 여러 번 신고했는지 알기 위한 신고자 표시 (033 reporter_hash, 앱 #108).
 * 앱 번호(guestId)에 서버 비밀값(REPORT_HASH_SECRET)을 섞어 HMAC-SHA256 16진수 64자로 바꾼다.
 * 원래 번호는 저장하지 않고, 비밀값이 없으면 해시에서 번호를 되찾거나 다른 표(user_profile 등)와 이을 수 없다.
 * 비밀값이나 앱 번호가 없으면 null (그 신고는 따로 한 사람으로 셈).
 */
@Component
public class ReporterHash {

    static final int MAX_GUEST_ID = 100;

    private final byte[] secret;

    public ReporterHash(@Value("${report.hash-secret:}") String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 비밀값이 들어 있는지 (없으면 모든 신고의 reporter_hash 가 비어 있음) */
    public boolean ready() {
        return secret.length > 0;
    }

    /** 앱 번호 → 해시. 비밀값이 없거나 번호가 비었거나 너무 길면 null */
    public String of(String guestId) {
        if (!ready() || guestId == null) {
            return null;
        }
        // 다른 창구와 같은 사람으로 세게 소문자로 맞춘다 (게스트 칸은 대소문자 구별, V38)
        String g = guestId.trim().toLowerCase(java.util.Locale.ROOT);
        if (g.isEmpty() || g.length() > MAX_GUEST_ID) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(g.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 을 쓸 수 없어요", e);
        }
    }
}
