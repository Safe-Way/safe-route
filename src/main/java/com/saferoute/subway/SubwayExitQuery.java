package com.saferoute.subway;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "문화전당역에서 국립아시아문화전당 가려면 몇 번 출구로 나가요?" 같은 지하철 출구 질문 읽기 (11-23).
 * 말만 읽고, 찾기는 {@link SubwayRepository}가 한다.
 */
public final class SubwayExitQuery {

    /**
     * @param station 역 이름 ("문화전당", 끝의 '역' 뗌). 말하지 않았으면 null
     * @param place   가려는 곳 ("국립아시아문화전당"). 말하지 않았으면 null
     * @param exitNo  "5번 출구에 뭐 있어"처럼 출구 번호를 말했으면 그 번호, 아니면 null
     * @param bus     "28번 버스 타려면"처럼 버스 번호를 말했으면 그 번호, 아니면 null
     */
    public record Ask(String station, String place, String exitNo, String bus) {
    }

    private static final Pattern STATION = Pattern.compile("^([가-힣A-Za-z0-9·]+?)역(에서|에|은|는|의|으로|이|)$");
    private static final Pattern EXIT_NO = Pattern.compile("(\\d{1,2})\\s*번?\\s*출구");
    private static final Pattern BUS = Pattern.compile("([가-힣]{0,3}\\d{1,3}(?:-\\d)?)\\s*번?\\s*버스|버스\\s*([가-힣]{0,3}\\d{1,3}(?:-\\d)?)");
    private static final Pattern ASK_WORD = Pattern.compile("몇\\s*번|어느|어디|무슨|뭐|무엇");

    /** 가려는 곳이 아닌 낱말 (통째로 같을 때만 뺌) */
    private static final Set<String> SKIP = Set.of("가려면", "갈려면", "가려고", "가는", "가는데", "가면", "가고", "가야",
            "갈때", "가요", "가", "나가요", "나가", "나가야", "나가면", "나가나요", "나가야해", "나가야돼", "나와요", "내려서",
            "내리면", "돼", "돼요", "되나요", "해", "해요", "하나요", "좋아", "좋아요", "좋을까", "있어", "있어요", "뭐야",
            "알려줘", "알려", "줘", "주세요", "지하철", "지하철로", "지하철역", "타고", "타려면", "타면", "번", "몇", "버스",
            "제일", "가장", "가까운", "나가서", "근처", "쪽", "역", "역에서", "타는", "곳", "거기", "에", "에서", "로", "으로",
            "은", "는", "이", "을", "를");

    private SubwayExitQuery() {
    }

    /** 지하철 출구 질문이 아니면 null */
    public static Ask read(String text) {
        if (text == null || !text.contains("출구")) {
            return null;
        }
        String s = text.strip().replaceAll("[?？!.,~]", " ");
        String exitNo = null;
        Matcher ex = EXIT_NO.matcher(s);
        boolean askWord = ASK_WORD.matcher(s).find();
        if (ex.find() && !s.substring(0, ex.start()).replace(" ", "").endsWith("몇")) {
            exitNo = ex.group(1);
            s = s.substring(0, ex.start()) + " " + s.substring(ex.end());
        }
        if (!askWord) {
            return null;
        }
        String bus = null;
        Matcher b = BUS.matcher(s);
        if (b.find()) {
            bus = b.group(1) != null ? b.group(1) : b.group(2);
            s = s.substring(0, b.start()) + " " + s.substring(b.end());
        }
        String station = null;
        List<String> rest = new ArrayList<>();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty() || w.contains("출구") || ASK_WORD.matcher(w).find() || SKIP.contains(w)) {
                continue;
            }
            Matcher st = STATION.matcher(w);
            if (station == null && st.matches() && !w.startsWith("역")) {
                station = st.group(1);
                continue;
            }
            String p = tail(w);
            if (!p.isEmpty() && !SKIP.contains(p)) {
                rest.add(p);
            }
        }
        String place = rest.isEmpty() ? null : String.join(" ", rest);
        if (station == null && place == null) {
            return null;
        }
        return new Ask(station, place, exitNo, bus);
    }

    /** 끝에 붙은 "가려면", "쪽으로", "으로", "에" 등을 뗀다. "충장로로"는 "충장로". "마을"처럼 이름 끝일 수 있는 을·는은 안 뗀다 */
    static String tail(String w) {
        for (String t : List.of("가려면", "갈려면", "가는데", "가려고", "가는", "가면", "쪽으로", "으로", "에서", "에")) {
            if (w.length() > t.length() + 1 && w.endsWith(t)) {
                return w.substring(0, w.length() - t.length());
            }
        }
        if (w.endsWith("로로")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }
}
