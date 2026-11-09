package com.saferoute.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 제미나이 지시문을 조각으로 나눠 필요한 것만 보낸다 (비서 토큰 줄이기 R3, 조각 글은 A파트 재휘님 초안).
 * 늘 「공통」 조각을 보내고, 이번 말에 걸린 묶음(ToolPicker)의 rules 조각을 차례대로 붙인다 (같은 조각은 한 번만).
 * 아무 묶음도 안 걸렸거나 7개로 다시 물을 때는 조각 전부 (옛 SYSTEM 과 같은 내용 + [참고] 한 줄).
 * 조각 글은 src/main/resources/assistant/rules/*.txt (FILES 이름), 서버가 켤 때 한 번 읽는다.
 */
final class RulePieces {

    static final String COMMON = "공통";
    /** 조각 전부를 붙이는 차례 */
    static final List<String> ORDER = List.of(COMMON, "길", "가게", "가까운 곳", "내 정보", "저장");

    /**
     * 조각 이름 → 파일 이름. 파일 이름은 영어로 둔다: 한글 파일 이름은 언어 설정이 없는 리눅스(도커 빌드)에서
     * 깨져서 못 읽을 수 있다 (실제로 LANG 이 없는 곳에서 빌드하니 한글 이름 파일이 빠졌음).
     */
    static final Map<String, String> FILES = Map.of(COMMON, "common", "길", "route", "가게", "store", "가까운 곳", "nearby",
            "내 정보", "me", "저장", "save");

    static final Map<String, String> TEXT = load();

    private RulePieces() {
    }

    private static Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : ORDER) {
            String path = "/assistant/rules/" + FILES.get(name) + ".txt";
            try (InputStream in = RulePieces.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException(path + " 이 없어요");
                }
                out.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (ToolPicker.Group g : ToolPicker.GROUPS) {
            for (String r : g.rules()) {
                if (!out.containsKey(r)) {
                    throw new IllegalStateException("tool_groups.json '" + g.group() + "'의 규칙 조각 '" + r + "'이 없어요");
                }
            }
        }
        return Map.copyOf(out);
    }

    /** 조각 전부 */
    static String all() {
        return join(ORDER);
    }

    /** 공통 + 걸린 묶음의 조각. 묶음이 없으면 전부 */
    static String system(List<ToolPicker.Group> groups) {
        if (groups == null || groups.isEmpty()) {
            return all();
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(COMMON);
        for (ToolPicker.Group g : groups) {
            names.addAll(g.rules());
        }
        return join(List.copyOf(names));
    }

    private static String join(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            if (b.length() > 0) {
                b.append('\n');
            }
            b.append(TEXT.get(n));
        }
        return b.toString();
    }
}
