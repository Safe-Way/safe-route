package com.saferoute.guest;

import com.saferoute.common.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 게스트 본인 확인 (2026-11-23, 공개 배포 준비 1-1).
 *
 * <p>지금은 게스트 번호만 알면 그 사람의 내 정보·기록을 보거나 지울 수 있다. 앱이 게스트 번호와 함께
 * 비밀 토큰을 {@value #HEADER_TOKEN} 헤더로 보내면, 그 번호로 처음 온 토큰을 등록하고 다음부터 같은지 본다.
 *
 * <p>게스트 번호는 어디로 와도 찾는다: {@value #HEADER_ID} 헤더, 주소·폼의 guestId, JSON 본문의 guestId.
 * 둘 이상이 서로 다르면 막는다. 헤더로만 오면 guestId 파라미터로도 보이게 해서({@link GuestRequest}),
 * 앱이 번호를 주소(URL)에서 빼도 창구 코드를 고치지 않아도 된다(주소에 있으면 서버 접속 기록에 남는다).
 *
 * <p>{@code guest-auth.enforce=false}(지금)인 동안은 토큰 없는 요청도 받는다. 앱이 토큰을 보내기 시작한 뒤
 * true로 바꾸면 토큰 없는 요청을 막는다. 틀린 토큰은 언제나 막는다.
 * guest_token 표가 없으면(037 적용 전) 확인을 건너뛴다.
 */
@Component
@Order(GuestAuthFilter.ORDER)
public class GuestAuthFilter extends OncePerRequestFilter {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;
    public static final String HEADER_ID = "X-Guest-Id";
    public static final String HEADER_TOKEN = "X-Guest-Token";
    /** 다음 필터(요청 제한)가 쓰는 게스트 번호 */
    public static final String ATTR_GUEST = GuestAuthFilter.class.getName() + ".guestId";
    /** JSON 본문 상한. 넘으면 413 (비서·길찾기 요청은 수 KB) */
    public static final int MAX_JSON_BYTES = 256 * 1024;
    static final int MAX_GUEST_ID = 64;

    private static final Logger log = LoggerFactory.getLogger(GuestAuthFilter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GuestTokenStore tokens;
    private final boolean enforce;

    public GuestAuthFilter(GuestTokenStore tokens, @Value("${guest-auth.enforce:false}") boolean enforce) {
        this.tokens = tokens;
        this.enforce = enforce;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String headerId = clean(req.getHeader(HEADER_ID));
        byte[] body = null;
        String bodyId = null;
        if (isJsonWithBody(req)) {
            // 큰 본문을 미리 안 읽고 넘기면 본문 속 guestId 를 확인 없이 쓰게 되므로, 상한을 넘으면 막는다
            if (req.getContentLengthLong() > MAX_JSON_BYTES) {
                write(res, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "TOO_LARGE", "요청이 너무 커요");
                return;
            }
            body = req.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
            if (body.length > MAX_JSON_BYTES) {
                write(res, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "TOO_LARGE", "요청이 너무 커요");
                return;
            }
            bodyId = guestIdFromJson(body);
        }
        String paramId = clean(req.getParameter("guestId"));

        String[] given = Stream.of(headerId, paramId, bodyId).filter(Objects::nonNull).distinct().toArray(String[]::new);
        if (given.length > 1) {
            write(res, HttpServletResponse.SC_BAD_REQUEST, "GUEST_MISMATCH", "게스트 번호가 서로 달라요");
            return;
        }
        String guestId = given.length == 1 ? given[0] : null;
        if (guestId != null && guestId.length() > MAX_GUEST_ID) {
            write(res, HttpServletResponse.SC_BAD_REQUEST, "BAD_PARAM", "guestId 값의 형식이 맞지 않아요");
            return;
        }
        HttpServletRequest next = body != null || (headerId != null && paramId == null)
                ? new GuestRequest(req, body, headerId != null && paramId == null ? headerId : null)
                : req;
        if (guestId == null) {
            chain.doFilter(next, res);
            return;
        }

        switch (tokens.check(guestId, req.getHeader(HEADER_TOKEN))) {
            case OK, UNAVAILABLE -> { }
            case MISSING -> {
                if (enforce) {
                    write(res, HttpServletResponse.SC_UNAUTHORIZED, "GUEST_TOKEN_REQUIRED", "앱을 최신 버전으로 업데이트해 주세요");
                    return;
                }
            }
            case WRONG -> {
                log.info("게스트 토큰이 맞지 않아 막음: {}… {} {}", guestId.substring(0, Math.min(8, guestId.length())),
                        req.getMethod(), req.getRequestURI());
                write(res, HttpServletResponse.SC_UNAUTHORIZED, "GUEST_TOKEN_WRONG", "이 기기의 정보가 아니에요");
                return;
            }
        }
        next.setAttribute(ATTR_GUEST, guestId);
        chain.doFilter(next, res);
    }

    private static boolean isJsonWithBody(HttpServletRequest req) {
        String m = req.getMethod();
        if (!("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m) || "DELETE".equals(m))) {
            return false;
        }
        String ct = req.getContentType();
        if (ct == null || !ct.toLowerCase().contains("json")) {
            return false;
        }
        return req.getContentLengthLong() != 0;   // -1(길이 모름)도 읽는다
    }

    /** JSON 맨 위의 guestId. 깨진 JSON 이면 null (컨트롤러가 알아서 400을 낸다) */
    static String guestIdFromJson(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(body);
            return n != null && n.isObject() && n.path("guestId").isString() ? clean(n.path("guestId").asString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static void write(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(JSON.writeValueAsString(new ErrorResponse(code, message)));
    }
}
