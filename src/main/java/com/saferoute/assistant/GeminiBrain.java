package com.saferoute.assistant;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.saferoute.assistant.Conversation.Pending;
import com.saferoute.assistant.Conversation.Place;
import com.saferoute.route.api.RouteResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 제미나이가 도구를 고르는 비서 (함수 호출). 팀 설계 "AI 도구 설계"의 ③~⑤:
 * 사용자 말 + 도구 7개 목록을 보내면 제미나이가 부를 도구를 고르고, 서버가 우리 코드로 실행해 결과를 돌려주면
 * 제미나이가 문장으로 만든다. 저장하는 도구는 서버가 바로 하지 않고 확인 질문을 만든다 (네를 들으면 저장).
 * 한 번 묻는 데 도구 호출은 4번까지. 숫자는 도구 결과에 있는 것만 믿는다 (아니면 규칙 문장으로 바꿈).
 * 실패하면 null 을 돌려주고, 그러면 규칙 비서(RuleBrain)가 답한다.
 */
@Component
class GeminiBrain {

    static final int MAX_ROUNDS = 5;
    static final String AGAIN = "그때 다시 여쭤볼게요.";
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    /** 답에 새어 나온 [참고] 말 ("[참고] 내용에 따르면", "참고 자료를 보면" 등). 사용자에게는 이상하게 들려서 지운다 */
    private static final Pattern REFERENCE_WORDS = Pattern.compile(
            "\\[?참고\\]?\\s*(내용|자료|글|정보)?\\s*(에|을|를)?\\s*(따르면|의하면|보면)\\s*,?\\s*|\\[참고\\]\\s*");
    /** 도구가 성공했을 때만 쓸 수 있는 말 (재휘님 규칙 "했다고 말하지 않는다") */
    private static final List<String> DONE_WORDS = List.of("신고했", "저장했", "바꿨", "바꾸었", "기억했", "등록했",
            "보냈", "전화했", "알렸");
    private static final Set<String> SAVING = Set.of("update_my_info", "set_map_layers", "report_problem");

    // 지시문은 조각으로 나눠 필요한 것만 보낸다 (RulePieces, 조각 글은 resources/assistant/rules, 토큰 줄이기 R3)

    /** 앱에 대한 질문 말 (이런 말이면 지식 조각을 찾아 [참고]로 붙임, R4) */
    static final List<String> ASKING = List.of("왜", "어떻게", "뭐가달라", "뭐가다르", "차이", "뭐야", "무슨", "저장", "지우",
            "개인정보", "ai", "에이아이", "인공지능", "알아?", "알고있", "수있어", "어디서", "기준", "누가");

    private final GeminiClient gemini;
    private final AssistantTools tools;
    private final JsonMapper json;
    /** 지식 조각 찾기 (R4). 없으면 null (시험) */
    private final KnowledgeRepository knowledge;

    GeminiBrain(GeminiClient gemini, AssistantTools tools, JsonMapper json) {
        this(gemini, tools, json, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    GeminiBrain(GeminiClient gemini, AssistantTools tools, JsonMapper json, KnowledgeRepository knowledge) {
        this.gemini = gemini;
        this.tools = tools;
        this.json = json;
        this.knowledge = knowledge;
    }

    boolean available() {
        return gemini.available();
    }

    /**
     * 제미나이로 답한다. 실패하면 null (규칙 비서가 대신함).
     * 말에 맞는 도구만 보내고(R2), 제미나이가 보내지 않은 도구를 부르거나 첫 답에서 못 한다고 하면 7개로 한 번 다시 묻는다(R5).
     */
    Outcome run(Turn t) {
        // 시험: test.ai = "all_tools" 면 고르지 않고 7개 다 (점검 스크립트 --all-tools, 줄이기 전과 비교용)
        boolean allTools = t.req.test() != null && "all_tools".equals(t.req.test().ai());
        List<ToolPicker.Group> groups = allTools ? List.of() : ToolPicker.groups(t);
        List<String> picked = allTools ? ToolPicker.ALL : ToolPicker.pick(t);
        t.toolsSent = picked;
        String reference = reference(t, groups);
        if (picked.size() < ToolPicker.ALL.size()) {
            Conversation before = t.convo;
            Outcome o = run(t, RulePieces.system(groups), reference, ToolPicker.declarations(DECLARATIONS, picked),
                    picked);
            if (o != RETRY) {
                return o;
            }
            // 잘못 고름: 대화·부른 도구를 처음으로 되돌리고 7개로 다시
            t.convo = before;
            t.tools.clear();
            t.confirm = null;
            t.plan = null;
            t.planDest = null;
            t.stopPlan = null;
            t.spot = null;
            t.spotKindName = null;
            t.spotMessage = null;
            t.done.clear();
            t.toolsRetried = true;
            t.toolsSent = ToolPicker.ALL;
        }
        Outcome o = run(t, RulePieces.all(), reference, DECLARATIONS, ToolPicker.ALL);
        return o == RETRY ? null : o;
    }

    /**
     * 앱에 대한 질문이면 지식 조각을 찾아 "[참고]" 글로 (R4). 아니면 빈 글.
     * 찾는 때: 아무 묶음도 안 걸렸거나, 묻는 말(왜·어떻게·뭐가 달라·저장·AI…)이 있고 길·가까운 곳·가게 묶음이 아닐 때.
     * 길 찾기·가까운 곳처럼 도구로 끝나는 말에는 붙이지 않는다.
     */
    String reference(Turn t, List<ToolPicker.Group> groups) {
        if (knowledge == null || !wantsKnowledge(t.text, groups)) {
            return "";
        }
        List<KnowledgeRepository.Piece> found = knowledge.find(t.text);
        if (found.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder("[참고]\n");
        for (KnowledgeRepository.Piece p : found) {
            b.append("- ").append(p.topic()).append(": ").append(p.body()).append('\n');
        }
        t.knowledgeTopics = found.stream().map(KnowledgeRepository.Piece::topic).toList();
        return b.toString();
    }

    static boolean wantsKnowledge(String text, List<ToolPicker.Group> groups) {
        String f = text == null ? "" : text.replace(" ", "").toLowerCase(Locale.ROOT);
        if (f.isEmpty()) {
            return false;
        }
        if (groups.isEmpty()) {
            return true;
        }
        boolean toolGroup = groups.stream().anyMatch(g -> List.of("길", "가까운 곳", "가게").contains(g.group()));
        return !toolGroup && ASKING.stream().anyMatch(f::contains);
    }

    /** 도구를 잘못 골랐다는 표시 (밖으로 나가지 않음) */
    static final Outcome RETRY = new Outcome("", null, List.of(), true);

    /** 할 수 없다는 말 (첫 답에서 도구를 안 부르고 이렇게 말하면 도구가 모자란 것으로 봄) */
    /** 하겠다고만 하는 말 ("화장실 찾아 드릴게요"). 도구를 안 부르고 이렇게 끝내면 말만 하고 행동은 안 한 것 */
    private static final Pattern PROMISE = Pattern.compile(
            "(찾아|알려|보여|안내해|검색해|확인해|살펴)\\s*(드릴게요|볼게요|드리겠|보겠)");
    static final String DO_IT = "방금 하겠다고만 말하고 도구를 부르지 않았어요. 알맞은 도구를 지금 불러서 실제로 하고 결과로 답하세요."
            + " 도구로 할 수 없는 일이면 못 한다고 말하세요.";

    /** 도구를 안 부르고 하겠다고만 말했으면 true (질문으로 끝나면 사용자 답을 기다리는 것이라 아님) */
    static boolean promiseOnly(String said) {
        String s = said.strip();
        return !s.endsWith("?") && PROMISE.matcher(s).find();
    }

    private static final List<String> CANT = List.of("할 수 없", "못 해요", "못해요", "기능이 없", "도와드릴 수 없", "지원하지 않");

    private Outcome run(Turn t, String system, String reference, List<Map<String, Object>> declarations,
            List<String> sent) {
        boolean narrowed = sent.size() < ToolPicker.ALL.size();
        tools.loadProfile(t);
        List<Object> contents = new ArrayList<>();
        for (Conversation.Turn c : t.convo.turns()) {
            contents.add(Map.of("role", c.role(), "parts", List.of(Map.of("text", c.text()))));
        }
        // [참고]는 이번 말 앞에만 붙이고 대화(conversationId)에는 남기지 않는다
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", context(t) + "\n" + reference + t.text))));
        StringBuilder facts = new StringBuilder(t.text).append(' ').append(context(t)).append(' ').append(reference);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            JsonNode content = gemini.chat(system, contents, declarations, t.usage);
            if (content == null) {
                return null;
            }
            List<JsonNode> calls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (JsonNode p : content.path("parts")) {
                if (p.has("functionCall")) {
                    calls.add(p.get("functionCall"));
                } else if (p.has("text") && !p.path("thought").asBoolean(false)) {
                    text.append(p.get("text").asString());
                }
            }
            if (calls.isEmpty()) {
                String said = text.toString().strip();
                if (narrowed && round == 0 && CANT.stream().anyMatch(said::contains)) {
                    return RETRY;
                }
                if (round == 0 && t.tools.isEmpty() && promiseOnly(said)) {
                    // 말만 하고 행동 안 함 (사용성 점검): 좁혀 보냈으면 7개로 다시, 아니면 한 번 더 시킨다
                    if (narrowed) {
                        return RETRY;
                    }
                    contents.add(json.convertValue(content, Map.class));
                    contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", DO_IT))));
                    continue;
                }
                return withPlaceButtons(t, finish(t, said, facts.toString()));
            }
            if (narrowed && calls.stream().anyMatch(c -> !sent.contains(c.path("name").asString("")))) {
                return RETRY; // 보내지 않은 도구를 부름
            }
            contents.add(json.convertValue(content, Map.class));
            List<Object> responses = new ArrayList<>();
            for (JsonNode call : calls) {
                String name = call.path("name").asString("");
                Map<String, Object> result;
                if (!t.canCall()) {
                    result = Map.of("error", "도구는 한 번 묻는 데 4번까지예요. 지금까지 결과로 답하세요.");
                } else {
                    result = call(t, name, call.path("args"));
                }
                if (t.confirm != null) {
                    // 저장 도구: 서버가 정한 확인 질문으로 바로 답한다 (AI가 저장했다고 말하지 못하게)
                    return Outcome.ask(t.confirm, RuleBrain.YES_NO);
                }
                facts.append(' ').append(json.writeValueAsString(result));
                responses.add(Map.of("functionResponse", Map.of("name", name, "response", result)));
            }
            contents.add(Map.of("role", "user", "parts", responses));
        }
        return null;
    }

    /**
     * 고를 장소가 남아 있으면(search_place 결과가 여럿) 버튼을 붙인다 (앱 요청 11/06: 찾은 곳은 늘 버튼으로).
     * 제미나이 말이 검사에서 떨어졌으면 규칙 문장으로 묻는다.
     */
    static Outcome withPlaceButtons(Turn t, Outcome o) {
        List<Place> cand = t.convo.candidates();
        if (!"place".equals(t.convo.asked()) || cand.isEmpty()) {
            return o;
        }
        List<AssistantReply.Choice> buttons = AssistantTools.placeChoices(t, cand);
        if (o == null) {
            return Outcome.ask(cand.size() + "곳을 찾았어요. 어디로 갈까요?", buttons);
        }
        String q = o.ask() != null ? o.ask() : o.say();
        String all = o.say() + " " + q;
        if (all.contains("번호") || namesListed(all, cand) >= 2) {
            // 버튼에는 번호가 없는데 "번호를 말씀해 주세요"라고 하면 헷갈려서 서버 문장으로 (사용성 점검)
            // 버튼에 이미 있는 이름을 늘어놓아도 길고 헷갈려서 서버 문장으로 (비서 점검 5번)
            return new Outcome(cand.size() + "곳을 찾았어요. 어디로 갈까요? 아래에서 골라 주세요.", null, buttons, o.ai());
        }
        return new Outcome(o.say(), q, buttons, o.ai());
    }

    /** 답에 후보 이름이 몇 개 들어 있는지 */
    static int namesListed(String text, List<Place> cand) {
        int n = 0;
        for (Place p : cand) {
            if (p.name() != null && !p.name().isBlank() && text.contains(p.name().strip())) {
                n++;
            }
        }
        return n;
    }

    /** 답에서 [참고] 말을 지운다. 지시문에도 쓰지 말라고 했지만 제미나이가 가끔 그대로 말해서 서버가 한 번 더 지운다 */
    static String dropReferenceWords(String text) {
        return REFERENCE_WORDS.matcher(text).replaceAll("").strip();
    }

    /** 제미나이 마지막 말을 검사한다. 숫자가 도구 결과에 없거나 하지 않은 일을 했다고 하면 규칙 문장으로 */
    Outcome finish(Turn t, String text, String facts) {
        text = dropReferenceWords(text);
        if (text.isEmpty()) {
            return null;
        }
        boolean ok = true;
        Set<String> known = new java.util.HashSet<>();
        Matcher k = NUMBER.matcher(facts.replace(",", ""));
        while (k.find()) {
            known.add(k.group());
        }
        Matcher m = NUMBER.matcher(text.replace(",", ""));
        while (m.find()) {
            if (!known.contains(m.group())) {
                ok = false; // 도구 결과에 없는 숫자 = 지어낸 숫자
            }
        }
        for (String w : DONE_WORDS) {
            if (text.contains(w)) {
                ok = false;
            }
        }
        if (!ok) {
            if (t.plan != null) {
                return Outcome.say(Sentences.trip(t));
            }
            if (t.spot != null || t.spotMessage != null) {
                return Outcome.say(Sentences.spot(t));
            }
            return null;
        }
        boolean question = text.endsWith("?") || text.endsWith("요?") || text.endsWith("까요?");
        return new Outcome(text, question ? text : null, List.of(), true);
    }

    /** 제미나이에게 주는 지금 상황 (좌표는 보내지 않음) */
    String context(Turn t) {
        StringBuilder sb = new StringBuilder("[상황] 지금 ");
        sb.append(t.now.toLocalDate()).append('(')
                .append(t.now.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN)).append(") ")
                .append(t.now.toLocalTime().withSecond(0).withNano(0));
        Profile p = t.profile;
        sb.append(" / 지금 모드: ").append(t.mode() == null ? "모름" : t.mode());
        if (p != null && p.tempActive()) {
            sb.append("(잠깐, ").append(p.tempUntil()).append("까지, 평소 ").append(p.mode()).append(')');
        }
        if (p == null && t.appMode != null) {
            sb.append(" / 처음 설정: 앱에서 모드를 골라 둠 (설정 질문 하지 말 것)");
        } else {
            sb.append(" / 처음 설정: ").append(p == null || !p.setupDone() ? "안 함" : "끝남");
        }
        if (t.convo.destination() != null) {
            sb.append(" / 정해진 목적지: ").append(t.convo.destination().name());
        }
        if (!t.convo.candidates().isEmpty()) {
            sb.append(" / 고를 후보: ").append(Sentences.names(t.convo.candidates()));
        }
        sb.append(" / 위치: ").append(t.located() ? "있음" : "모름 (위치 켜기를 부탁해야 함)");
        return sb.toString();
    }

    /** 도구 하나 실행. 결과는 제미나이에게 돌려줄 값 */
    Map<String, Object> call(Turn t, String name, JsonNode a) {
        Map<String, Object> r = new LinkedHashMap<>();
        switch (name) {
            case "search_place" -> {
                Integer idx = a.has("pick_index") ? a.get("pick_index").asInt() : null;
                if (idx != null && idx >= 0 && idx < t.convo.candidates().size()) {
                    Place p = t.convo.candidates().get(idx);
                    t.convo = t.convo.withDestination(p).asked(null);
                    t.tools.add("search_place");
                    r.put("status", "found");
                    r.put("places", List.of(p.name()));
                    return r;
                }
                AssistantTools.Found f = tools.searchPlace(t, a.path("query").asString(""));
                r.put("status", f.status());
                List<String> names = new ArrayList<>();
                for (Place p : f.places()) {
                    names.add(t.located() ? AssistantTools.label(t, p).replace(" · ", " (") + ")"
                            : p.address() == null ? p.name() : p.name() + " (" + p.address() + ")");
                }
                r.put("places", names);
                if ("outside".equals(f.status())) {
                    r.put("note", "광주 밖이에요. 광주 안에서만 안내한다고 말하세요.");
                } else if ("choose".equals(f.status())) {
                    r.put("note", "여러 곳이에요. 어디인지 물으세요. 사용자가 고르면 search_place(pick_index=0부터)를 부르세요.");
                }
            }
            case "recommend_trip" -> {
                if (t.convo.destination() == null) {
                    r.put("weather", weather(tools.weather(t)));
                    r.put("note", "목적지가 아직 없어요. 날씨만 알려 주고 어디 가실지 물으세요.");
                    t.tools.add("recommend_trip");
                    return r;
                }
                if (t.mode() == null) {
                    r.put("error", "어떻게 다니는지 몰라요. get_my_info 를 보고, 모르면 사용자에게 물으세요.");
                    return r;
                }
                if (!t.located()) {
                    r.put("error", "지금 위치를 몰라요. 위치 켜기를 부탁하세요.");
                    return r;
                }
                TripRecommender.Plan plan = tools.recommendTrip(t, t.convo.destination());
                r.put("sentence", Sentences.trip(t));
                if (plan.scored() != null) {
                    Trip b = plan.scored().best();
                    r.put("type", b.type());
                    r.put("summary", b.summary());
                    r.put("minutes", b.minutes());
                    r.put("because", plan.scored().because());
                    List<String> others = new ArrayList<>();
                    for (Trip o : plan.scored().others()) {
                        others.add(o.summary());
                    }
                    r.put("others", others);
                    List<String> ev = new ArrayList<>();
                    for (RouteResult.Event e : b.events()) {
                        ev.add(e.title() + " (" + e.kind() + ")");
                    }
                    r.put("events_on_way", ev);
                    if (b.firstBus() != null) {
                        r.put("first_bus", b.firstBus());
                    }
                    r.put("low_floor_bus", b.lowFloor());
                }
                r.put("night", plan.night());
                r.put("weather", weather(plan.weather()));
                if (t.convo.bodyNote() != null) {
                    r.put("body_note", t.convo.bodyNote());
                }
            }
            case "find_nearby" -> {
                if (!t.located()) {
                    r.put("error", "지금 위치를 몰라요. 위치 켜기를 부탁하세요.");
                    return r;
                }
                AssistantReply.Spot s = tools.findNearby(t, a.path("kind").asString("toilet"));
                r.put("sentence", Sentences.spot(t));
                r.put("kind_name", t.spotKindName);
                if (s != null) {
                    r.put("name", s.name());
                    r.put("distance_m", s.distanceM());
                    r.put("status", s.status());
                }
                if (t.spotMessage != null) {
                    r.put("message", t.spotMessage);
                }
            }
            case "get_my_info" -> {
                Profile p = tools.getMyInfo(t);
                if (p == null && t.appMode != null) {
                    r.put("known", false);
                    r.put("mode_now", t.appMode);
                    r.put("note", "앱에서 고른 모드예요. 처음 설정 질문은 하지 말고 이 모드로 도와주세요.");
                } else if (p == null) {
                    r.put("known", false);
                    r.put("note", "처음 쓰는 사람이에요. 어떻게 다니는지(걸어서·지팡이·휠체어·유모차) 물으세요.");
                } else {
                    r.put("known", true);
                    r.put("mode_now", p.modeNow());
                    r.put("aid_now", p.aidNow());
                    r.put("temp_active", p.tempActive());
                    r.put("temp_until", p.tempUntil() == null ? null : p.tempUntil().toString());
                    r.put("temp_reason", p.tempReason());
                    r.put("usual_mode", p.mode());
                    r.put("stairs_ok", p.stairsOk());
                    r.put("setup_done", p.setupDone());
                }
            }
            case "update_my_info" -> propose(t, myInfo(t, a), r);
            case "set_map_layers" -> propose(t, layers(t, a, r), r);
            case "report_problem" -> {
                if (!t.located()) {
                    r.put("error", "지금 위치를 몰라서 신고할 수 없어요. 위치 켜기를 부탁하세요.");
                    return r;
                }
                propose(t, tools.proposeReport(t, a.path("type").asString("other"), t.text), r);
            }
            default -> r.put("error", "모르는 도구예요: " + name);
        }
        return r;
    }

    private void propose(Turn t, Pending p, Map<String, Object> r) {
        if (p == null) {
            if (!r.containsKey("error")) {
                r.put("error", "바꿀 값을 알아듣지 못했어요. 사용자에게 다시 물으세요.");
            }
            return;
        }
        t.tools.add(p.tool() + ":confirm");
        t.convo = t.convo.withPending(p);
        t.confirm = p.question();
    }

    /** update_my_info 값 검사 → 확인 질문 */
    private Pending myInfo(Turn t, JsonNode a) {
        Map<String, Object> f = new LinkedHashMap<>();
        List<String> said = new ArrayList<>();
        if (a.path("clear_temp").asBoolean(false)) {
            f.put("clear_temp", true);
            said.add("이제 평소처럼 " + RuleBrain.modeWord(t.profile == null ? null : t.profile.mode()) + " 다니시는 걸로");
        }
        String mode = a.path("mode").asString(null);
        if (mode != null && AssistantTools.MODES.contains(mode)) {
            f.put("mode", mode);
            said.add("평소 " + RuleBrain.modeWord(mode) + " 다니시는 걸로");
        }
        String aid = a.path("mobility_aid").asString(null);
        if (aid != null && AssistantTools.AIDS.contains(aid)) {
            f.put("mobility_aid", aid);
            said.add(RuleBrain.aidWord(aid) + "을(를) 쓰시는 걸로");
        }
        if (a.has("stairs_ok") && a.get("stairs_ok").isNumber()) {
            int s = Math.max(0, Math.min(2, a.get("stairs_ok").asInt()));
            f.put("stairs_ok", s);
            said.add(s == 0 ? "계단은 못 가시는 걸로" : s == 1 ? "계단은 몇 칸 괜찮은 걸로" : "계단은 괜찮은 걸로");
        }
        String temp = a.path("temp_mode").asString(null);
        if (temp != null && AssistantTools.MODES.contains(temp)) {
            LocalDate until;
            try {
                until = a.has("temp_until") ? LocalDate.parse(a.get("temp_until").asString()) : null;
            } catch (RuntimeException e) {
                until = null;
            }
            if (until == null || until.isBefore(t.now.toLocalDate())) {
                until = t.now.toLocalDate().plusDays(7);
            }
            f.put("temp_mode", temp);
            f.put("temp_until", until.toString());
            f.put("next_ask_on", until.toString());
            f.put("temp_reason", a.path("temp_reason").asString("몸이 불편함"));
            if ("wheelchair".equals(temp)) {
                f.put("temp_aid", "manual_wheelchair");
            }
            boolean week = until.equals(t.now.toLocalDate().plusDays(7));
            said.add((week ? "일주일 동안(" + KoreanTime.day(until) + "까지) " : KoreanTime.day(until) + "까지 ")
                    + RuleBrain.modeWord(temp) + " 안내하는 걸로");
        }
        if (a.path("setup_done").asBoolean(false)) {
            f.put("setup_done", true);
        }
        if (said.isEmpty()) {
            return null;
        }
        String q = String.join(", ", said) + " 기억할까요? 맞으세요?";
        if (f.containsKey("temp_mode")) {
            // 잠깐 바뀐 모드는 끝나는 날 다시 묻는다는 말을 서버가 꼭 붙인다 (비서 점검 c10)
            q += " " + AGAIN;
        }
        return tools.proposeMyInfo(f, q, null);
    }

    /** set_map_layers 값 검사 → 확인 질문 */
    private Pending layers(Turn t, JsonNode a, Map<String, Object> r) {
        List<String> add = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (JsonNode n : a.path("add")) {
            String l = tools.layerOf(n.asString(""));
            (l == null ? unknown : add).add(l == null ? n.asString("") : l);
        }
        for (JsonNode n : a.path("remove")) {
            String l = tools.layerOf(n.asString(""));
            (l == null ? unknown : remove).add(l == null ? n.asString("") : l);
        }
        if (!unknown.isEmpty()) {
            r.put("error", "지도에 없는 시설이에요: " + String.join(", ", unknown) + ". 다른 이름으로 물어보세요.");
            return null;
        }
        if (add.isEmpty() && remove.isEmpty()) {
            return null;
        }
        LocalDate until = null;
        try {
            until = a.has("until") ? LocalDate.parse(a.get("until").asString()) : null;
        } catch (RuntimeException e) {
            until = null;
        }
        StringBuilder q = new StringBuilder();
        if (!add.isEmpty()) {
            q.append(names(add)).append("을(를) ");
            if (until != null) {
                q.append(KoreanTime.day(until)).append("까지 ");
            }
            q.append("지도에 보여 드리").append(remove.isEmpty() ? "릴게요. " : "고 ");
        }
        if (!remove.isEmpty()) {
            q.append(Josa.eun(names(remove))).append(" 지도에서 뺄게요. ");
        }
        q.append("맞으세요?");
        return tools.proposeLayers(add, remove, until, a.path("reason").asString(null), q.toString());
    }

    private String names(List<String> layers) {
        List<String> n = new ArrayList<>();
        for (String l : layers) {
            n.add(tools.layerName(l));
        }
        return String.join(", ", n);
    }

    private static Map<String, Object> weather(com.saferoute.situation.WeatherDust w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("known", w.weatherAvailable());
        m.put("rain", w.rain());
        m.put("heat", w.heat());
        m.put("cold", w.cold());
        m.put("temp_c", w.tempC() == null ? null : Math.round(w.tempC()));
        m.put("dust_bad", w.dustBad());
        return m;
    }

    // ───────────────────────── 도구 7개 설명 (제미나이에게 미리 알려 주는 목록) ─────────────────────────

    static final List<Map<String, Object>> DECLARATIONS = List.of(
            tool("search_place", "사용자가 말한 목적지를 찾는다 (자주 가는 곳, 다녀온 곳, 카카오 장소 검색 순). "
                            + "하나로 정해지면 그곳이 목적지가 된다. 후보 중 고르면 pick_index 로 부른다.",
                    Map.of("query", str("장소 이름 (예: 문화전당, 집, 남광주시장)"),
                            "pick_index", Map.of("type", "integer", "description", "앞서 보여 준 후보 중 고른 번호 (0부터)")),
                    List.of()),
            tool("recommend_trip", "정해진 목적지까지 걷기·대중교통 후보를 모아 규칙으로 1등과 이유를 정한다. "
                            + "날씨·밤·행사·통제·불편 신고·내 습관·걸음 속도가 다 들어 있다. 목적지가 정해진 뒤 한 번만 부른다. "
                            + "목적지 없이 부르면 날씨만 준다.",
                    Map.of(), List.of()),
            tool("find_nearby", "지금 위치에서 가까운 시설을 찾는다 (지금 열린 곳 먼저). 모드는 서버가 지금 모드로 넣는다.",
                    Map.of("kind", Map.of("type", "string", "enum",
                            List.of("toilet", "shelter", "police", "store", "bell", "subway", "guard_house"),
                            "description", "toilet 화장실, shelter 쉴 곳·쉼터, police 경찰, store 편의점, bell 비상벨, "
                                    + "subway 지하철역, guard_house 아동안전지킴이집")),
                    List.of("kind")),
            tool("get_my_info", "내 정보(평소 모드, 지금 모드, 이동 보조, 계단, 잠깐 바뀐 모드와 기간, 처음 설정 여부)를 본다.",
                    Map.of(), List.of()),
            tool("update_my_info", "내 정보를 바꾼다. 서버가 사용자에게 확인을 받은 뒤 저장한다.",
                    Map.of("mode", Map.of("type", "string", "enum", List.of("adult", "child", "senior", "wheelchair"),
                                    "description", "평소 모드 (앞으로 계속)"),
                            "mobility_aid", Map.of("type", "string", "enum",
                                    List.of("manual_wheelchair", "power_wheelchair", "walker", "cane", "stroller")),
                            "stairs_ok", Map.of("type", "integer", "description", "계단 0 못 감, 1 몇 칸 괜찮음, 2 괜찮음"),
                            "temp_mode", Map.of("type", "string", "enum", List.of("adult", "child", "senior", "wheelchair"),
                                    "description", "잠깐 쓰는 모드 (다쳐서 휠체어, 오늘만 걸어서 등)"),
                            "temp_until", str("잠깐 쓰는 모드가 끝나는 날 YYYY-MM-DD (그날 포함). 모르면 오늘부터 7일 뒤"),
                            "temp_reason", str("이유 한마디 (다리 다침 등)"),
                            "clear_temp", Map.of("type", "boolean", "description", "다 나았으면 true (잠깐 쓰던 모드 끝)"),
                            "setup_done", Map.of("type", "boolean", "description", "처음 설정 질문을 다 마쳤으면 true")),
                    List.of()),
            tool("set_map_layers", "지도에 보일 시설을 더하거나 뺀다. 서버가 확인을 받은 뒤 저장한다.",
                    Map.of("add", Map.of("type", "array", "items", Map.of("type", "string"),
                                    "description", "보여 줄 시설 이름 (예: 장애인 화장실, 보도턱 낮춘 횡단보도, 엘리베이터 육교, "
                                            + "엘리베이터 있는 지하철 출구, 쉼터, CCTV)"),
                            "remove", Map.of("type", "array", "items", Map.of("type", "string"), "description", "뺄 시설 이름"),
                            "until", str("이 날짜까지 YYYY-MM-DD. 없으면 바꿀 때까지"),
                            "reason", str("이유 한마디")),
                    List.of()),
            tool("report_problem", "지금 위치의 길 문제를 신고한다 (사진 없이). 서버가 확인을 받은 뒤 올린다.",
                    Map.of("type", Map.of("type", "string", "enum",
                            List.of("construction", "accident", "curb", "steep", "broken", "obstacle", "other"),
                            "description", "construction 공사, accident 사고, curb 턱, steep 급경사, broken 보도 파손, "
                                    + "obstacle 막는 물건, other 기타")),
                    List.of("type")));

    private static Map<String, Object> tool(String name, String desc, Map<String, Object> props, List<String> req) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        if (!req.isEmpty()) {
            params.put("required", req);
        }
        if (props.isEmpty()) {
            return Map.of("name", name, "description", desc);
        }
        return Map.of("name", name, "description", desc, "parameters", params);
    }

    private static Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }
}
