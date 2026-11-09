package com.saferoute.appchat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.saferoute.assistant.GeminiClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 앱 화면 AI 두 가지를 서버에서 부른다. 지시문과 답 모양(responseSchema)은 앱(민수님 SetupChat.kt·Assistant.kt)에
 * 있던 것을 그대로 옮겼다. 서버가 지시문을 쥐고 있어서, 이 창구로 다른 일을 시킬 수 없다.
 * 제미나이 답은 정해진 값만 남기고 걸러서 돌려준다.
 */
@Service
public class AppChatService {

    private static final Logger log = LoggerFactory.getLogger(AppChatService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final int MAX_LINES = 12;
    static final int MAX_LINE_CHARS = 300;

    static final List<String> MODES = List.of("CHILD", "SENIOR", "DISABLED", "GENERAL");
    static final List<String> TEXT_SIZES = List.of("SMALL", "NORMAL", "LARGE");
    /** 지도에 더 볼 수 있는 것 (앱 SETUP_MAP_ITEMS) */
    static final Map<String, String> MAP_ITEMS = ordered(
            "HEAT_SHELTER", "무더위쉼터", "COLD_SHELTER", "한파쉼터", "TOILET", "공중화장실",
            "ACCESSIBLE_TOILET", "장애인 화장실", "STAIRS", "계단", "CURB_RAMP", "턱 낮춘 횡단보도",
            "SUBWAY", "지하철역", "EMERGENCY_BELL", "비상벨", "CHILD_ZONE", "어린이 보호구역");
    /** 가까운 곳 종류 (앱 NEARBY_KINDS) */
    static final Map<String, String> NEARBY_KINDS = ordered(
            "toilet", "화장실", "store", "편의점", "police", "파출소", "shelter", "쉼터", "subway", "지하철역");
    static final List<String> ACTIONS = List.of("emergency", "settings", "nearby", "goto", "buses", "report", "ask",
            "answer");

    static final String SETUP_INSTRUCTION = """
            너는 '세잎지도' 걷기 길안내 앱의 처음 설정 도우미야. 사용자가 누가 어떻게 걷는지 한국어로 말하면 앱 설정을 골라.
            모드: CHILD(어린이), SENIOR(어르신·다리가 불편하거나 천천히 걷는 사람), DISABLED(휠체어·보행기), GENERAL(그 밖의 사람).
            누가 걷는지 모르면 한 번만 짧게 되물어(type=ask, 고를 답 2~4개). 알면 바로 설정을 골라(type=pick).
            이미 고른 설정이 있고 사용자가 더 바꾸거나 더하고 싶다고 하면, 그 설정을 바탕으로 바꿔서 다시 pick 해.
            무엇을 더할지 말하지 않았으면 "무엇을 더 볼까요?"처럼 되묻고 고를 답에 지도 항목 이름을 줘.
            모든 칸을 채워. pick이면 reason에 왜 그렇게 골랐는지, question은 빈 글자, choices는 빈 목록. ask이면 question에 되물을 말, choices에 고를 답, 설정 칸은 지금 짐작한 값, reason은 빈 글자.
            question과 reason은 존댓말 한국어 한두 문장(80자 안쪽)으로만 써. 한자나 다른 언어는 쓰지 마. 이름·전화번호는 묻지 마.
            """ + "extraMapItems에는 모드에 더하면 좋은 것만: " + names(MAP_ITEMS) + ".";

    static final String MAP_INSTRUCTION = """
            너는 '세잎지도' 걷기 길안내 앱의 지도 화면 비서야. 사용자의 한국어 말을 보고 앱이 할 일 하나를 골라.
            action:
            - emergency: 넘어짐, 다침, 아픔, 위험, 누가 따라옴, 길을 잃어 무서움 같은 급한 말. 다른 일보다 먼저 이것을 골라. say는 "괜찮으세요? 112에 전화하거나 보호자에게 알릴까요?"처럼 묻기만 해.
            - settings: 설정 바꾸기. 글씨가 안 보이거나 작다 → textSize를 한 단계 크게(SMALL→NORMAL→LARGE). 소리를 끄거나 켜기 → voiceGuide. 모드 바꾸기 → mode. 지도에 더 보기/빼기 → addMapItems/removeMapItems. SOS 버튼이 안 보이거나 켜 달라 → sosButton=true.
            """ + "- nearby: 가까운 곳으로 길찾기. nearbyKind는 " + names(NEARBY_KINDS) + " 중 하나.\n" + """
            - goto: 이름이 있는 장소로 길찾기. placeQuery에 검색할 장소 이름만.
            - buses: 가까운 정류장의 버스 보기. report: 길이 망가졌거나 불편한 곳 신고하기.
            - ask: 무엇을 원하는지 모를 때 짧게 되묻기, choices에 고를 답 2~4개.
            - answer: 앱 사용법을 짧게 답하거나, 할 수 없는 일을 알릴 때.
            지킬 규칙:
            1. 앱이 아직 하지 않은 일을 했다고 말하지 마. say는 "~할게요", "~찾아볼게요"처럼 앞으로 할 일로 말해. "~했어요"는 쓰지 마. 결과는 앱이 따로 알려.
            2. 위 action으로 할 수 없는 일(전화 걸기, 택시 부르기, 예약, 날씨 알려 주기 등)은 answer로 못 한다고 말하고, 앱으로 할 수 있는 다른 방법을 하나만 제안해.
            3. 길 안내와 상관없는 질문에는 answer로 한 문장만 짧게 답하고 길 안내로 돌려.
            바꾸지 않을 설정 칸은 지금 값 그대로 채워. nearbyKind를 안 쓰면 none, 쓰지 않는 글자 칸은 빈 글자, 목록은 빈 목록.
            say에는 사용자에게 할 말을 존댓말 한국어 한두 문장(80자 안쪽)으로. 한자나 다른 언어는 쓰지 마.
            """ + "지도 항목: " + names(MAP_ITEMS) + ".";

    private final GeminiClient gemini;

    public AppChatService(GeminiClient gemini) {
        this.gemini = gemini;
    }

    // ---------- 말로 맞추기 ----------

    public Map<String, Object> setup(AppChatRequest req) {
        List<Object> contents = contents(req, MAX_LINES);
        AppChatRequest.Settings now = req.settings();
        String system = SETUP_INSTRUCTION + (now == null ? "" : "\n지금까지 고른 설정: " + settingsLine(now));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", str("ask", "pick"));
        props.put("question", str());
        props.put("choices", list());
        props.put("mode", str(MODES));
        props.put("textSize", str(TEXT_SIZES));
        props.put("voiceGuide", Map.of("type", "BOOLEAN"));
        props.put("sosButton", Map.of("type", "BOOLEAN"));
        props.put("extraMapItems", list(List.copyOf(MAP_ITEMS.keySet())));
        props.put("reason", str());
        JsonNode j = ask(system, contents, schema(props, List.of("type", "mode", "textSize", "voiceGuide", "sosButton",
                "extraMapItems", "reason", "question", "choices")));
        return checkSetup(j);
    }

    /** 제미나이 답 → 앱이 쓸 수 있는 값만. 못 쓰면 503 */
    static Map<String, Object> checkSetup(JsonNode j) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = j.path("type").asString("");
        if ("ask".equals(type)) {
            String question = j.path("question").asString("");
            if (!looksLikeKorean(question)) {
                throw AppChatException.unavailable();
            }
            out.put("type", "ask");
            out.put("question", question.strip());
            out.put("choices", koreanChoices(j.path("choices")));
        } else if ("pick".equals(type)) {
            String mode = j.path("mode").asString("");
            if (!MODES.contains(mode)) {
                throw AppChatException.unavailable();
            }
            String reason = j.path("reason").asString("");
            out.put("type", "pick");
            out.put("mode", mode);
            out.put("textSize", TEXT_SIZES.contains(j.path("textSize").asString("")) ? j.path("textSize").asString()
                    : "NORMAL");
            out.put("voiceGuide", j.path("voiceGuide").isBoolean() ? j.path("voiceGuide").asBoolean() : true);
            out.put("sosButton", j.path("sosButton").isBoolean() && j.path("sosButton").asBoolean());
            out.put("extraMapItems", known(j.path("extraMapItems"), MAP_ITEMS.keySet()));
            out.put("reason", looksLikeKorean(reason) ? reason.strip() : "말씀하신 내용에 맞춰 설정했어요.");
        } else {
            throw AppChatException.unavailable();
        }
        return out;
    }

    // ---------- 지도 비서 ----------

    public Map<String, Object> map(AppChatRequest req) {
        List<Object> contents = contents(req, 8);
        AppChatRequest.Settings now = req.settings();
        String system = MAP_INSTRUCTION + (now == null ? "" : "\n지금 설정: " + settingsLine(now));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", str(ACTIONS));
        props.put("say", str());
        props.put("mode", str(MODES));
        props.put("textSize", str(TEXT_SIZES));
        props.put("voiceGuide", Map.of("type", "BOOLEAN"));
        props.put("sosButton", Map.of("type", "BOOLEAN"));
        props.put("addMapItems", list(List.copyOf(MAP_ITEMS.keySet())));
        props.put("removeMapItems", list(List.copyOf(MAP_ITEMS.keySet())));
        List<String> kinds = new ArrayList<>();
        kinds.add("none");
        kinds.addAll(NEARBY_KINDS.keySet());
        props.put("nearbyKind", str(kinds));
        props.put("placeQuery", str());
        props.put("choices", list());
        JsonNode j = ask(system, contents, schema(props, List.copyOf(props.keySet())));
        return checkMap(j);
    }

    static Map<String, Object> checkMap(JsonNode j) {
        String action = j.path("action").asString("");
        String say = j.path("say").asString("");
        if (!ACTIONS.contains(action) || !looksLikeKorean(say)) {
            throw AppChatException.unavailable();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("say", say.strip());
        switch (action) {
            case "settings" -> {
                String mode = j.path("mode").asString("");
                String size = j.path("textSize").asString("");
                out.put("mode", MODES.contains(mode) ? mode : null);
                out.put("textSize", TEXT_SIZES.contains(size) ? size : null);
                out.put("voiceGuide", j.path("voiceGuide").isBoolean() ? j.path("voiceGuide").asBoolean() : null);
                out.put("sosButton", j.path("sosButton").isBoolean() ? j.path("sosButton").asBoolean() : null);
                out.put("addMapItems", known(j.path("addMapItems"), MAP_ITEMS.keySet()));
                out.put("removeMapItems", known(j.path("removeMapItems"), MAP_ITEMS.keySet()));
            }
            case "nearby" -> {
                String kind = j.path("nearbyKind").asString("");
                if (!NEARBY_KINDS.containsKey(kind)) {
                    throw AppChatException.unavailable();
                }
                out.put("nearbyKind", kind);
            }
            case "goto" -> {
                String q = j.path("placeQuery").asString("").strip();
                if (q.length() < 2 || q.length() > 50) {
                    throw AppChatException.unavailable();
                }
                out.put("placeQuery", q);
            }
            case "ask" -> out.put("choices", koreanChoices(j.path("choices")));
            default -> {
                // emergency, buses, report, answer: 말만
            }
        }
        return out;
    }

    // ---------- 같이 쓰는 것 ----------

    /** 대화 검사 → 제미나이 contents. 마지막 n줄만 보낸다 */
    static List<Object> contents(AppChatRequest req, int n) {
        if (req == null || req.history() == null || req.history().isEmpty()) {
            throw AppChatException.bad("history 가 비어 있어요");
        }
        List<AppChatRequest.Line> lines = req.history();
        if (lines.size() > 40) {
            throw AppChatException.bad("history 가 너무 길어요");
        }
        AppChatRequest.Line last = lines.get(lines.size() - 1);
        if (last == null || !last.fromUser()) {
            throw AppChatException.bad("마지막 줄은 사용자의 말이어야 해요");
        }
        List<Object> out = new ArrayList<>();
        for (AppChatRequest.Line l : lines.subList(Math.max(0, lines.size() - n), lines.size())) {
            if (l == null || l.text() == null || l.text().isBlank()) {
                throw AppChatException.bad("빈 줄이 있어요");
            }
            if (l.text().length() > MAX_LINE_CHARS) {
                throw AppChatException.bad("한 줄은 " + MAX_LINE_CHARS + "자까지예요");
            }
            out.add(Map.of("role", l.fromUser() ? "user" : "model", "parts", List.of(Map.of("text", l.text().strip()))));
        }
        return out;
    }

    private JsonNode ask(String system, List<Object> contents, Map<String, Object> schema) {
        if (!gemini.available()) {
            throw AppChatException.unavailable();
        }
        String text = gemini.structured(system, contents, schema);
        if (text == null) {
            throw AppChatException.unavailable();
        }
        try {
            JsonNode j = JSON.readTree(text);
            if (j == null || !j.isObject()) {
                throw AppChatException.unavailable();
            }
            return j;
        } catch (RuntimeException e) {
            if (e instanceof AppChatException a) {
                throw a;
            }
            log.warn("앱 화면 AI 답을 읽지 못함: {}", e.getClass().getSimpleName());
            throw AppChatException.unavailable();
        }
    }

    /** "mode=SENIOR, textSize=LARGE, ..." 정해진 값만 (다른 글자는 지시문에 넣지 않는다) */
    static String settingsLine(AppChatRequest.Settings s) {
        String extras = s.extraMapItems() == null ? "" : s.extraMapItems().stream()
                .filter(MAP_ITEMS::containsKey).distinct().collect(Collectors.joining(","));
        return "mode=" + (MODES.contains(s.mode()) ? s.mode() : "GENERAL")
                + ", textSize=" + (TEXT_SIZES.contains(s.textSize()) ? s.textSize() : "NORMAL")
                + ", voiceGuide=" + (s.voiceGuide() == null || s.voiceGuide())
                + ", sosButton=" + Boolean.TRUE.equals(s.sosButton())
                + ", extraMapItems=" + (extras.isEmpty() ? "없음" : extras);
    }

    private static List<String> koreanChoices(JsonNode a) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : a) {
            String t = c.asString("").strip();
            if (!t.isEmpty() && looksLikeKorean(t) && out.size() < 4) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> known(JsonNode a, java.util.Collection<String> allowed) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : a) {
            String t = c.asString("");
            if (allowed.contains(t) && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 화면에 보여도 되는 한국어 문장인지 (앱 looksLikeKorean 과 같은 규칙): 한자·다른 글자가 섞이지 않고,
     * 같은 낱말만 되풀이하지 않음 (작은 모델이 가끔 그렇게 됨).
     */
    static boolean looksLikeKorean(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        if (t.isEmpty() || t.length() > 200) {
            return false;
        }
        int letters = 0;
        int hangul = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if ((c >= '가' && c <= '힣') || (c >= 'ㄱ' && c <= 'ㆎ')) {
                    hangul++;
                }
            }
        }
        if (letters == 0 || hangul * 10 < letters * 7) {
            return false;
        }
        String[] words = t.split("\\s+");
        if (words.length < 4) {
            return true;
        }
        Map<String, Integer> count = new java.util.HashMap<>();
        int max = 0;
        for (String w : words) {
            max = Math.max(max, count.merge(w, 1, Integer::sum));
        }
        return max * 2 <= words.length;
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> order) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "OBJECT");
        s.put("properties", props);
        s.put("required", List.copyOf(props.keySet()));
        s.put("propertyOrdering", order);
        return s;
    }

    private static Map<String, Object> str(String... values) {
        return str(List.of(values));
    }

    private static Map<String, Object> str(List<String> values) {
        return values.isEmpty() ? Map.of("type", "STRING") : Map.of("type", "STRING", "enum", values);
    }

    private static Map<String, Object> list() {
        return Map.of("type", "ARRAY", "items", str());
    }

    private static Map<String, Object> list(List<String> values) {
        return Map.of("type", "ARRAY", "items", str(values));
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    private static String names(Map<String, String> m) {
        return m.entrySet().stream().map(e -> e.getKey() + "(" + e.getValue() + ")").collect(Collectors.joining(", "));
    }
}
