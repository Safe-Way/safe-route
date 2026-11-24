package com.saferoute.guest;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 필터가 요청을 한 번 읽고도 컨트롤러가 그대로 읽을 수 있게 감싼다 (2026-11-23, 공개 배포 준비 1-1).
 * <ul>
 *   <li>본문(JSON)을 미리 읽어 두고 컨트롤러에는 같은 바이트를 다시 준다</li>
 *   <li>게스트 번호가 X-Guest-Id 헤더로만 왔으면 guestId 파라미터로도 보이게 한다.
 *       그래서 {@code @RequestParam guestId} 창구를 고치지 않아도 번호를 주소(URL)에서 뺄 수 있다</li>
 * </ul>
 */
final class GuestRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final String extraGuestId;

    /**
     * @param body         미리 읽은 본문. null 이면 원래 본문을 그대로 쓴다
     * @param extraGuestId 파라미터로 더할 게스트 번호. null 이면 더하지 않는다
     */
    GuestRequest(HttpServletRequest request, byte[] body, String extraGuestId) {
        super(request);
        this.body = body;
        this.extraGuestId = extraGuestId;
    }

    @Override
    public ServletInputStream getInputStream() throws java.io.IOException {
        if (body == null) {
            return super.getInputStream();
        }
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("미리 읽은 본문은 비동기로 읽지 않아요");
            }
        };
    }

    @Override
    public BufferedReader getReader() throws java.io.IOException {
        if (body == null) {
            return super.getReader();
        }
        String enc = getCharacterEncoding();
        Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), cs));
    }

    @Override
    public String getParameter(String name) {
        String v = super.getParameter(name);
        if (v == null && extraGuestId != null && "guestId".equals(name)) {
            return extraGuestId;
        }
        return v;
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] v = super.getParameterValues(name);
        if (v == null && extraGuestId != null && "guestId".equals(name)) {
            return new String[] {extraGuestId};
        }
        return v;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> m = super.getParameterMap();
        if (extraGuestId == null || m.containsKey("guestId")) {
            return m;
        }
        Map<String, String[]> out = new LinkedHashMap<>(m);
        out.put("guestId", new String[] {extraGuestId});
        return Collections.unmodifiableMap(out);
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }
}
