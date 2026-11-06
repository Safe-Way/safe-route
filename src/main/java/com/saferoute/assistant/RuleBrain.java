package com.saferoute.assistant;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.saferoute.assistant.AssistantReply.Choice;
import com.saferoute.assistant.Conversation.Pending;
import com.saferoute.assistant.Conversation.Place;

/**
 * AI 없이 낱말 규칙으로 답하는 비서. AI 키가 없거나 AI가 실패했을 때, 그리고 정해진 흐름
 * (저장 전 확인의 "네", 처음 설정, "언제까지?" 답, 오늘 제안)에서 쓴다. 부르는 도구는 AI와 같다.
 */
@Component
class RuleBrain {

    static final List<Choice> AID_CHOICES = List.of(new Choice("walk", "걸어서"), new Choice("cane", "지팡이"),
            new Choice("wheelchair", "휠체어"), new Choice("stroller", "유모차"));
    static final List<Choice> STAIRS_CHOICES = List.of(new Choice("0", "못 가요"), new Choice("1", "몇 칸은 괜찮아요"),
            new Choice("2", "괜찮아요"));
    static final List<Choice> YES_NO = List.of(new Choice("yes", "네"), new Choice("no", "아니요"));
    static final List<Choice> YES_NO_GO = List.of(new Choice("yes", "네, 안내해 줘"), new Choice("no", "아니요"));

    private final AssistantTools tools;
    private final MemoryRepository memory;
    private final UnmetRepository unmet;

    RuleBrain(AssistantTools tools, MemoryRepository memory, UnmetRepository unmet) {
        this.tools = tools;
        this.memory = memory;
        this.unmet = unmet;
    }

    // ───────────────────────── 도구가 없는 요청 (docs/assistant/no_tool_policy.md) ─────────────────────────

    /** ③ 우리가 못 하는 일: 낱말 → {주제, 대신 제안할 것} */
    static final Map<String, String[]> NO_TOOL = new LinkedHashMap<>();
    static {
        NO_TOOL.put("택시", new String[] {"택시", "가까운 버스나 걸어가는 길을"});
        NO_TOOL.put("예약", new String[] {"예약", "그곳까지 가는 길을"});
        NO_TOOL.put("문자", new String[] {"문자 보내기", "보호자에게 위치 보내기 버튼을"});
        NO_TOOL.put("배달", new String[] {"배달 주문", "가까운 가게까지 가는 길을"});
        NO_TOOL.put("주문", new String[] {"주문", "가까운 가게까지 가는 길을"});
        NO_TOOL.put("결제", new String[] {"결제", "가까운 가게까지 가는 길을"});
        NO_TOOL.put("알람", new String[] {"알람", "자주 가는 곳 알림을"});
        NO_TOOL.put("대리", new String[] {"대리운전", "가까운 버스나 걸어가는 길을"});
    }
    /** ⑤ 길찾기와 상관없는 것 */
    static final List<String> OFF_TOPIC = List.of("주식", "코인", "노래", "뉴스", "게임", "로또", "농담", "영화", "요리",
            "레시피", "숙제", "번역", "운세", "야구", "축구", "드라마");
    /** ② 말로만 답하면 되는 것 */
    static final List<String> THANKS = List.of("고마워", "고맙", "감사", "땡큐", "수고");

    /** 도구가 없는 요청이면 답(못 한 요청은 030 표에 남김). 아니면 null */
    Outcome noTool(Turn t) {
        String f = t.text.replace(" ", "");
        String mode = t.mode();
        for (Map.Entry<String, String[]> e : NO_TOOL.entrySet()) {
            if (f.contains(e.getKey()) && !f.contains("112")) {
                String[] v = e.getValue();
                unmet.add(t.now.toLocalDate(), mode, t.text, "no_tool", v[0], v[1].replace("을", "").strip());
                return Outcome.ask(v[0] + "은(는) 아직 못 해요. 대신 " + v[1] + " 알려 드릴까요?");
            }
        }
        for (String w : OFF_TOPIC) {
            if (f.contains(w)) {
                unmet.add(t.now.toLocalDate(), mode, t.text, "off_topic", w, "길 안내로 돌림");
                t.convo = t.convo.asked("destination");
                return Outcome.ask("저는 길 안내 비서라 그건 못 도와드려요. 어디 가실 곳이 있으면 말씀해 주세요.");
            }
        }
        for (String w : THANKS) {
            if (f.startsWith(w) || f.equals(w)) {
                return Outcome.say("천만에요. 또 가실 곳이 있으면 언제든 말씀해 주세요.");
            }
        }
        if (f.contains("뭐할수있") || f.contains("뭘할수있") || f.contains("무슨기능") || f.equals("도움말")) {
            return Outcome.ask("길 찾기, 가까운 화장실·쉴 곳 찾기, 길 문제 신고, 지도에 보일 것 바꾸기를 할 수 있어요. 무엇을 할까요?",
                    List.of(new Choice("문화전당 가자", "문화전당 가자"), new Choice("화장실 어디야", "화장실 어디야"),
                            new Choice("여기 공사 중이야", "여기 공사 중이야")));
        }
        return null;
    }

    // ───────────────────────── 정해진 흐름 (AI가 있어도 규칙으로) ─────────────────────────

    /** "네"·"아니요" (버튼 yes·no 포함) */
    static Boolean yesNo(String text, String choice) {
        if ("yes".equals(choice)) {
            return true;
        }
        if ("no".equals(choice)) {
            return false;
        }
        String t = text.replace(" ", "");
        if (t.isEmpty()) {
            return null;
        }
        for (String n : List.of("아니", "싫어", "안해", "하지마", "취소", "됐어", "말아")) {
            if (t.startsWith(n) || t.equals(n)) {
                return false;
            }
        }
        for (String y : List.of("네", "예", "응", "어", "그래", "좋아", "맞아", "그렇게", "해줘", "부탁", "ㅇㅇ", "오케이", "ok")) {
            if (t.startsWith(y)) {
                return true;
            }
        }
        return null;
    }

    /** 저장 전 확인에 대한 답. 네·아니요가 아니면 null (확인을 버리고 새 말로 본다) */
    Outcome confirm(Turn t, Pending p) {
        Boolean yes = yesNo(t.text, t.req.choice());
        if (yes == null) {
            t.convo = t.convo.withPending(null);
            return null;
        }
        t.convo = t.convo.withPending(null).asked(null);
        if (!yes) {
            return Outcome.say("알겠어요, 바꾸지 않을게요. 더 필요한 게 있으면 말씀해 주세요.");
        }
        List<String> said = new ArrayList<>();
        for (Pending q = p; q != null; q = q.more()) {
            String r = tools.save(t, q);
            if (r != null && !said.contains(r)) {
                said.add(r);
            }
        }
        String msg = String.join(" ", said);
        if ("report_problem".equals(p.tool())) {
            if (t.convo.destination() != null && t.located() && t.canCall()) {
                tools.recommendTrip(t, t.convo.destination());
                return Outcome.say(msg + " 신고한 곳을 피해서 다시 안내할게요. " + Sentences.trip(t));
            }
            return Outcome.say(msg + " 가시던 곳을 말씀해 주시면 그곳을 피해서 다시 안내할게요.");
        }
        if (Boolean.TRUE.equals(p.args().get("setup_done"))) {
            if (t.convo.destination() != null && t.located()) {
                tools.recommendTrip(t, t.convo.destination());
                return Outcome.say(msg + " " + Sentences.trip(t));
            }
            return Outcome.ask(msg + " 이제 어디로 가세요?");
        }
        return Outcome.say(msg.replace("기억해 둘게요.", "바꿨어요.").replace("지도를 바꿨어요.", "바꿨어요."));
    }

    /** 비서가 방금 되물은 것에 대한 답. 해당 없으면 null */
    Outcome answerAsked(Turn t) {
        String asked = t.convo.asked();
        if (asked == null) {
            return null;
        }
        return switch (asked) {
            case "temp_until" -> tempUntil(t);
            case "setup_aid" -> setupAid(t);
            case "setup_stairs" -> setupStairs(t);
            case "elevator" -> elevator(t);
            case "place" -> place(t);
            case "go_near" -> goNear(t);
            default -> null;
        };
    }

    /** 오늘 제안 (사용자가 말하지 않고 앱이 먼저 물어봄) */
    Outcome suggest(Turn t) {
        Profile p = tools.getMyInfo(t);
        if (p == null || !p.setupDone()) {
            return startSetup(t);
        }
        LocalDate today = t.now.toLocalDate();
        if (p.tempActive() && p.tempUntil() != null && !p.tempUntil().isAfter(today)) {
            String body = p.tempReason() != null && p.tempReason().contains("다리") ? "다리는" : "몸은";
            t.convo = t.convo.withPending(tools.proposeMyInfo(Map.of("clear_temp", true), "", null));
            return Outcome.ask(body + " 좀 괜찮아지셨어요? 괜찮아지셨으면 내일부터 평소처럼 "
                    + modeWord(p.mode()) + " 안내할까요?", YES_NO);
        }
        if (t.guestId != null && t.located()) {
            LocalTime now = t.now.toLocalTime();
            try {
                List<MemoryRepository.Habit> h = memory.habits(t.guestId, t.now.getDayOfWeek().getValue(),
                        now.minusMinutes(10), now.plusMinutes(40));
                if (!h.isEmpty()) {
                    MemoryRepository.Habit first = h.get(0);
                    Place dest = new Place(first.toName(), null, first.lat(), first.lon());
                    t.convo = t.convo.withDestination(dest);
                    tools.recommendTrip(t, dest);
                    return Outcome.say(KoreanTime.clock(first.slot()) + "에 " + first.toName() + " 가시죠? "
                            + Sentences.trip(t));
                }
            } catch (DataAccessException e) {
                // 습관을 못 읽으면 그냥 묻는다
            }
        }
        return Outcome.ask("어디 가실 곳이 있으세요? 말씀해 주시면 길을 찾아 드릴게요.");
    }

    // ───────────────────────── 급한 말 (다른 일보다 먼저) ─────────────────────────

    private static final List<String> URGENT = List.of("넘어졌", "넘어짐", "넘어져서", "쓰러", "피가", "피나", "살려",
            "도와줘", "도와주세요", "위험해", "따라와", "따라오", "숨이", "너무아파", "많이아파", "못일어나", "다쳤어", "다쳤어요");

    /** 넘어짐·위험·많이 아픔 같은 급한 말이면 true ("다쳐서 휠체어 타고 다녀"처럼 모드를 바꾸는 말은 아님) */
    static boolean urgent(String text) {
        String f = text.replace(" ", "");
        for (String w : URGENT) {
            if (f.contains(w)) {
                return !(f.contains("다쳐서") && RuleReader.mode(text) != null);
            }
        }
        return false;
    }

    /** 급한 말에는 112·보호자부터 묻는다. 전화는 앱이 휴대폰에서 건다 (SOS는 휴대폰 안에서만) */
    static Outcome emergency() {
        return Outcome.ask("괜찮으세요? 위험하면 바로 112에 전화하세요. 112에 전화할까요, 보호자에게 알릴까요?",
                List.of(new Choice("call_112", "112에 전화"), new Choice("call_guardian", "보호자에게 알리기"),
                        new Choice("no", "괜찮아요")));
    }

    // ───────────────────────── 보통 말 ─────────────────────────

    Outcome run(Turn t) {
        String text = t.text;
        String flat = text.replace(" ", "");

        // 몸 상태가 잠깐 바뀜 ("다리를 다쳐서 휠체어 타고 다녀")
        if ((flat.contains("다쳐") || flat.contains("다쳤") || flat.contains("깁스") || flat.contains("수술"))
                && RuleReader.mode(text) != null) {
            String m = RuleReader.mode(text);
            t.convo = t.convo.withMode(m).asked("temp_until");
            Optional<LocalDate> until = KoreanTime.until(text, t.now.toLocalDate());
            if (until.isPresent()) {
                return tempUntil(t);
            }
            return Outcome.ask("많이 불편하시겠어요. 언제까지 " + modeWord(m).replace("로", "를").replace("으를", "를")
                    + " 타세요? 날짜나 '이번 달까지'처럼 말씀해 주세요.");
        }
        // 다 나음
        if (flat.contains("다나았") || flat.contains("나았어") || flat.contains("이제괜찮") || flat.contains("다시걸")) {
            Profile p = tools.getMyInfo(t);
            String usual = p == null ? null : p.mode();
            t.convo = t.convo.withMode(null).withPending(tools.proposeMyInfo(Map.of("clear_temp", true), "", null));
            return Outcome.ask("다행이에요! 이제 평소처럼 " + modeWord(usual == null ? "adult" : usual)
                    + " 다니시는 걸로 바꿀까요? 맞으세요?", YES_NO);
        }
        // 앞으로 쓰는 이동 보조 ("앞으로 지팡이 써")
        if (flat.startsWith("앞으로") || flat.contains("이제부터")) {
            String aid = aid(text);
            if (aid != null) {
                Map<String, Object> f = aidFields(aid);
                t.convo = t.convo.withPending(tools.proposeMyInfo(f, "", null));
                return Outcome.ask("앞으로 " + aidWord(aid) + "(으)로 다니시는 걸로 기억할까요? 맞으세요?", YES_NO);
            }
        }
        // 오늘만 바뀜 ("오늘은 휠체어 안 타")
        if (flat.startsWith("오늘은") || flat.startsWith("오늘만")) {
            String m = RuleReader.mode(text);
            if (m != null && (flat.contains("안타") || flat.contains("안써"))) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("temp_mode", "adult");
                f.put("temp_until", t.now.toLocalDate().toString());
                f.put("temp_reason", "오늘만");
                t.convo = t.convo.withMode("adult").withPending(tools.proposeMyInfo(f, "", null));
                return Outcome.ask("오늘은 걸어서 다니시는 걸로 안내할까요? 내일은 다시 평소대로 할게요. 맞으세요?", YES_NO);
            }
        }
        // 지도에 보일 것 바꾸기
        Outcome layers = layers(t);
        if (layers != null) {
            return layers;
        }
        // 말로 신고
        String report = reportType(flat);
        if (report != null) {
            t.convo = t.convo.withPending(tools.proposeReport(t, report, text));
            return Outcome.ask(t.convo.pending().question(), YES_NO);
        }
        // 엘리베이터 (뜻이 둘)
        if ((flat.contains("엘베") || flat.contains("엘리베이터")) && (flat.contains("가자") || flat.contains("가줘")
                || flat.contains("데로") || flat.contains("곳으로"))) {
            t.convo = t.convo.asked("elevator");
            return Outcome.ask("지하철역 엘리베이터 출구 말씀이세요, 아니면 엘리베이터 있는 육교 말씀이세요?",
                    List.of(new Choice("subway", "지하철역 엘리베이터 출구"), new Choice("footbridge", "엘리베이터 육교")));
        }
        // 가까운 시설
        String near = nearbyKind(flat);
        if (near != null) {
            if (!t.located()) {
                return Outcome.ask("지금 계신 곳을 알아야 찾을 수 있어요. 위치 켜기를 눌러 주세요.");
            }
            tools.loadProfile(t);
            tools.findNearby(t, near);
            return Outcome.say(Sentences.spot(t));
        }
        // ① 우리 시설 목록에 없는 곳 ("약국 어디야") → 장소 검색으로 가까운 곳
        String other = whereIs(text);
        if (other != null) {
            if (!t.located()) {
                return Outcome.ask("지금 계신 곳을 알아야 찾을 수 있어요. 위치 켜기를 눌러 주세요.");
            }
            AssistantTools.Found f = tools.searchNear(t, other);
            if (f.places().isEmpty()) {
                return Outcome.ask("가까운 " + other + "을(를) 찾지 못했어요. 다른 이름으로 말씀해 주시겠어요?");
            }
            Place p = f.places().get(0);
            return Outcome.ask("가까운 " + other + "은(는) " + p.name()
                    + (p.address() == null ? "" : "(" + p.address() + ")") + "이에요. 그리로 안내할까요?", YES_NO_GO);
        }
        // 날씨
        if (flat.contains("날씨") || flat.contains("비와") || flat.contains("비오") || flat.contains("미세먼지")) {
            return Outcome.say(Sentences.weather(tools.weather(t)));
        }
        // 오늘 몸 상태
        if (flat.contains("아파") || flat.contains("아프") || flat.contains("힘들") || flat.contains("피곤")) {
            t.convo = t.convo.withBodyNote(text.length() > 30 ? text.substring(0, 30) : text);
        }
        // 이번 대화의 모드 ("휠체어야")
        String m = RuleReader.mode(text);
        if (m != null) {
            t.convo = t.convo.withMode(m);
        }
        // 목적지
        String dest = destination(text);
        if (dest != null) {
            AssistantTools.Found f = tools.searchPlace(t, dest);
            switch (f.status()) {
                case "none":
                    return Outcome.ask("'" + dest + "'을(를) 찾지 못했어요. 다른 이름으로 말씀해 주시겠어요?");
                case "error":
                    return Outcome.ask("지금은 장소 검색이 잘 안 돼요. 잠시 뒤에 다시 말씀해 주세요.");
                case "outside":
                    return Outcome.say("'" + f.places().get(0).name() + "'은(는) 광주 밖이에요. 지금은 광주 안에서만 길을 안내해요.");
                case "choose":
                    return choosePlace(dest, f.places());
                default:
                    break;
            }
        }
        return goOrAsk(t);
    }

    /** 목적지가 정해졌으면 길을 추천하고, 모르는 것이 있으면 하나씩 묻는다 */
    Outcome goOrAsk(Turn t) {
        tools.loadProfile(t);
        if (t.mode() == null) {
            Profile p = tools.getMyInfo(t);
            if (p == null || p.modeNow() == null) {
                return startSetup(t);
            }
        }
        if (t.convo.destination() == null) {
            String f = t.text.replace(" ", "");
            if (!f.isEmpty() && t.convo.bodyNote() == null && RuleReader.mode(t.text) == null
                    && !(f.contains("길") || f.contains("가") || f.contains("찾") || f.contains("안내"))) {
                unmet.add(t.now.toLocalDate(), t.mode(), t.text, "not_understood", null, "어디 가실지 다시 물음");
                t.convo = t.convo.asked("destination");
                return Outcome.ask("잘 못 알아들었어요. 어디로 가실지 다시 말씀해 주시겠어요? (예: 문화전당 가자, 화장실 어디야)");
            }
            if (t.convo.bodyNote() != null) {
                return Outcome.ask("많이 힘드시겠어요. 어디로 가세요? 덜 걷고 쉴 곳이 있는 길로 찾아 드릴게요.");
            }
            t.convo = t.convo.asked("destination");
            return Outcome.ask("어디로 가세요? 장소 이름을 말씀해 주세요. (예: 문화전당)");
        }
        if (!t.located()) {
            t.convo = t.convo.asked("location");
            return Outcome.ask("지금 계신 곳을 알아야 길을 찾을 수 있어요. 위치 켜기를 눌러 주세요.");
        }
        tools.recommendTrip(t, t.convo.destination());
        t.convo = t.convo.asked(null);
        return Outcome.say(Sentences.trip(t));
    }

    // ───────────────────────── 흐름 조각 ─────────────────────────

    Outcome startSetup(Turn t) {
        tools.getMyInfo(t);
        t.convo = t.convo.asked("setup_aid");
        return Outcome.ask("처음 오셨네요. 어떻게 다니세요? 걸어서, 지팡이, 휠체어, 유모차 중에 말씀해 주세요.", AID_CHOICES);
    }

    private Outcome setupAid(Turn t) {
        String c = t.req.choice();
        String a = c != null && List.of("walk", "cane", "wheelchair", "stroller").contains(c) ? c : aid(t.text);
        if (a == null) {
            String m = RuleReader.mode(t.text);
            a = m == null ? null : "wheelchair".equals(m) ? "wheelchair" : "walk";
        }
        if (a == null && t.text.contains("걸어")) {
            a = "walk";
        }
        if (a == null) {
            return Outcome.ask("걸어서, 지팡이, 휠체어, 유모차 중에 어떻게 다니세요?", AID_CHOICES);
        }
        Map<String, String> s = new LinkedHashMap<>(t.convo.setup());
        s.put("aid", a);
        t.convo = t.convo.withSetup(s);
        if (a.equals("wheelchair") || a.equals("manual_wheelchair") || a.equals("power_wheelchair")) {
            s.put("stairs", "0");
            t.convo = t.convo.withSetup(s);
            return setupConfirm(t);
        }
        t.convo = t.convo.asked("setup_stairs");
        return Outcome.ask(aidWord(a) + "(으)로 다니시는군요. 계단은 몇 칸 괜찮으세요?", STAIRS_CHOICES);
    }

    private Outcome setupStairs(Turn t) {
        String c = t.req.choice();
        String f = t.text.replace(" ", "");
        String v = c != null && List.of("0", "1", "2").contains(c) ? c
                : f.contains("못") || f.contains("안돼") || f.contains("힘들") ? "0"
                : f.contains("몇") || f.contains("조금") || f.contains("한두") ? "1"
                : f.contains("괜찮") || f.contains("다") || f.contains("돼") ? "2" : null;
        if (v == null) {
            return Outcome.ask("계단은 못 가세요, 몇 칸은 괜찮으세요, 아니면 괜찮으세요?", STAIRS_CHOICES);
        }
        Map<String, String> s = new LinkedHashMap<>(t.convo.setup());
        s.put("stairs", v);
        t.convo = t.convo.withSetup(s);
        return setupConfirm(t);
    }

    private Outcome setupConfirm(Turn t) {
        String a = t.convo.setup().get("aid");
        String st = t.convo.setup().get("stairs");
        Map<String, Object> f = new LinkedHashMap<>(aidFields(a));
        f.put("stairs_ok", Integer.parseInt(st));
        f.put("setup_done", true);
        t.convo = t.convo.asked(null).withPending(tools.proposeMyInfo(f, "", null));
        String stairs = switch (st) {
            case "0" -> "계단은 못 가요";
            case "1" -> "계단은 몇 칸 괜찮아요";
            default -> "계단은 괜찮아요";
        };
        return Outcome.ask(aidWord(a) + "(으)로 다니고, " + stairs + ". 이렇게 기억할까요? 맞으세요?", YES_NO);
    }

    private Outcome tempUntil(Turn t) {
        LocalDate today = t.now.toLocalDate();
        Optional<LocalDate> u = KoreanTime.until(t.text, today);
        String m = t.convo.mode() == null ? "wheelchair" : t.convo.mode();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("temp_mode", m);
        if ("wheelchair".equals(m)) {
            f.put("temp_aid", "manual_wheelchair");
        }
        f.put("temp_reason", t.convo.turns().stream().anyMatch(x -> x.text().contains("다리")) || t.text.contains("다리")
                ? "다리 다침" : "몸이 불편함");
        LocalDate until = u.orElse(today.plusDays(7));
        f.put("temp_until", until.toString());
        f.put("next_ask_on", until.toString());
        String head;
        if (u.isEmpty()) {
            head = "그럼 일주일 동안(" + KoreanTime.day(until) + "까지) " + modeWord(m) + " 안내하고, 그때 다시 여쭤볼게요.";
        } else {
            head = KoreanTime.day(until) + "까지 " + modeWord(m) + " 안내할게요.";
        }
        Pending more = null;
        String extra = "";
        if ("wheelchair".equals(m)) {
            List<String> add = new ArrayList<>();
            for (String l : List.of("toilet:wheelchair", "crosswalk:curb_low")) {
                add.add(l);
            }
            more = tools.proposeLayers(add, List.of(), until, "다리 다침", "");
            extra = " 그동안 장애인 화장실과 보도턱 낮춘 횡단보도도 지도에 보여 드릴게요.";
        }
        t.convo = t.convo.asked(null).withPending(tools.proposeMyInfo(f, "", more));
        return Outcome.ask(head + extra + " 맞으세요?", YES_NO);
    }

    private Outcome elevator(Turn t) {
        String f = t.text.replace(" ", "") + (t.req.choice() == null ? "" : t.req.choice());
        t.convo = t.convo.asked(null);
        if (f.contains("지하철") || f.contains("subway") || f.contains("역")) {
            if (!t.located()) {
                return Outcome.ask("지금 계신 곳을 알아야 찾을 수 있어요. 위치 켜기를 눌러 주세요.");
            }
            tools.loadProfile(t);
            tools.findNearby(t, "subway");
            return Outcome.say(Sentences.spot(t));
        }
        if (f.contains("육교") || f.contains("footbridge")) {
            t.convo = t.convo.withPending(tools.proposeLayers(List.of("footbridge:elevator"), List.of(), null, null, ""));
            return Outcome.ask("엘리베이터 육교를 지도에 보여 드릴까요? 맞으세요?", YES_NO);
        }
        return run(t);
    }

    private Outcome goNear(Turn t) {
        Boolean yes = yesNo(t.text, t.req.choice());
        List<Place> cand = t.convo.candidates();
        t.convo = t.convo.asked(null);
        if (yes == null || cand.isEmpty()) {
            return run(t);
        }
        if (!yes) {
            t.convo = t.convo.withCandidates(List.of());
            return Outcome.say("알겠어요. 더 필요한 게 있으면 말씀해 주세요.");
        }
        Place p = cand.get(0);
        t.convo = t.convo.withDestination(new Place(p.name(), null, p.lat(), p.lon()));
        return goOrAsk(t);
    }

    private Outcome place(Turn t) {
        List<Place> cand = t.convo.candidates();
        String c = t.req.choice();
        Place picked = null;
        if (c != null && c.matches("\\d") && Integer.parseInt(c) < cand.size()) {
            picked = cand.get(Integer.parseInt(c));
        } else {
            for (Place p : cand) {
                if (t.text.contains(p.name()) || (!t.text.isBlank() && p.name().contains(t.text.strip()))) {
                    picked = p;
                    break;
                }
            }
            if (picked == null && (t.text.startsWith("첫") || t.text.startsWith("1"))) {
                picked = cand.isEmpty() ? null : cand.get(0);
            }
        }
        if (picked == null) {
            t.convo = t.convo.asked(null);
            return run(t);
        }
        t.convo = t.convo.withDestination(picked).asked(null);
        return goOrAsk(t);
    }

    private Outcome layers(Turn t) {
        String flat = t.text.replace(" ", "");
        boolean show = flat.contains("보여") || flat.contains("켜") || flat.contains("띄워") || flat.contains("표시");
        boolean hide = flat.contains("빼") || flat.contains("숨겨") || flat.contains("없애") || flat.contains("안보이");
        if (!show && !hide) {
            return null;
        }
        List<AssistantTools.LayerHit> hits = tools.layerHits(t.text);
        if (hits.isEmpty()) {
            return null;
        }
        String n = AssistantTools.norm(t.text);
        List<String> add = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            int end = i + 1 < hits.size() ? hits.get(i + 1).pos() : n.length();
            String after = n.substring(hits.get(i).end(), end);
            boolean off = after.contains("빼") || after.contains("숨겨") || after.contains("없애") || after.contains("안보");
            if (!off && i + 1 == hits.size() && !after.contains("보여") && !after.contains("켜") && hide && !show) {
                off = true;
            }
            (off ? remove : add).add(hits.get(i).layer());
        }
        Optional<LocalDate> until = KoreanTime.until(t.text, t.now.toLocalDate());
        StringBuilder q = new StringBuilder();
        if (!add.isEmpty()) {
            q.append(names(add)).append("을(를) ");
            until.ifPresent(d -> q.append(KoreanTime.day(d)).append("까지 "));
            q.append("지도에 보여 드리");
            q.append(remove.isEmpty() ? "고 " : "고 ");
        }
        if (!remove.isEmpty()) {
            q.append(names(remove)).append("은(는) ");
            if (add.isEmpty()) {
                until.ifPresent(d -> q.append(KoreanTime.day(d)).append("까지 "));
            }
            q.append("지도에서 뺄게요. ");
        } else {
            q.setLength(q.length() - 2);
            q.append("릴게요. ");
        }
        q.append("맞으세요?");
        t.convo = t.convo.withPending(tools.proposeLayers(add, remove, until.orElse(null), null, ""));
        return Outcome.ask(q.toString(), YES_NO);
    }

    private String names(List<String> layers) {
        List<String> n = new ArrayList<>();
        for (String l : layers) {
            n.add(tools.layerName(l));
        }
        return String.join(", ", n);
    }

    private static Outcome choosePlace(String query, List<Place> cand) {
        List<Choice> choices = new ArrayList<>();
        for (int i = 0; i < cand.size(); i++) {
            Place d = cand.get(i);
            choices.add(new Choice(String.valueOf(i), d.name() + (d.address() == null ? "" : " (" + d.address() + ")")));
        }
        return Outcome.ask("'" + query + "'(으)로 " + cand.size() + "곳을 찾았어요. 어디로 갈까요?", choices);
    }

    // ───────────────────────── 낱말 ─────────────────────────

    /** "집에 가자" → 집. 그 밖은 RuleReader 로 */
    static String destination(String text) {
        String f = text.replace(" ", "");
        if (f.startsWith("집에") || f.startsWith("집으로") || f.contains("집에갈") || f.contains("집에가")) {
            return "집";
        }
        return RuleReader.destination(text, false);
    }

    /** "약국 어디야" → 약국 (우리 시설 종류가 아닌 곳만). 아니면 null */
    static String whereIs(String text) {
        String f = text.strip();
        for (String q : List.of("어디야", "어디 있어", "어디있어", "어디 있니", "어디에 있어", "어딨어", "어디예요", "어디 있어요")) {
            int i = f.indexOf(q);
            if (i > 0) {
                String w = f.substring(0, i).strip();
                for (String filler : List.of("가까운 ", "근처 ", "여기 ", "제일 ")) {
                    w = w.replace(filler, "");
                }
                w = RuleReader.stripParticle(w.replace("는", "").replace("은", "").strip());
                if (!w.isEmpty() && !w.contains(" ") && w.length() <= 10 && nearbyKind(w) == null) {
                    return w;
                }
            }
        }
        return null;
    }

    static String reportType(String flat) {
        boolean here = flat.contains("여기") || flat.contains("났어") || flat.contains("중이") || flat.contains("못지나")
                || flat.contains("못가") || flat.contains("신고");
        if (!here) {
            return null;
        }
        if (flat.contains("사고")) {
            return "accident";
        }
        if (flat.contains("공사")) {
            return "construction";
        }
        if (flat.contains("턱")) {
            return "curb";
        }
        if (flat.contains("경사") || flat.contains("가팔")) {
            return "steep";
        }
        if (flat.contains("깨져") || flat.contains("부서") || flat.contains("파손") || flat.contains("움푹")) {
            return "broken";
        }
        if (flat.contains("막혀") || flat.contains("막아") || flat.contains("세워져") || flat.contains("쌓여")) {
            return "obstacle";
        }
        return null;
    }

    static String nearbyKind(String flat) {
        if (flat.contains("화장실")) {
            return "toilet";
        }
        if (flat.contains("쉴곳") || flat.contains("쉼터") || flat.contains("쉬고싶") || flat.contains("쉴데")) {
            return "shelter";
        }
        if (flat.contains("경찰") || flat.contains("파출소") || flat.contains("지구대")) {
            return "police";
        }
        if (flat.contains("편의점")) {
            return "store";
        }
        if (flat.contains("비상벨")) {
            return "bell";
        }
        if (flat.contains("지하철역") && (flat.contains("어디") || flat.contains("가까운"))) {
            return "subway";
        }
        if (flat.contains("지킴이")) {
            return "guard_house";
        }
        return null;
    }

    /** 이동 보조 낱말 → mobility_aid 값 (walk 는 보조 없음) */
    static String aid(String text) {
        String f = text.replace(" ", "");
        if (f.contains("전동휠체어")) {
            return "power_wheelchair";
        }
        if (f.contains("휠체어")) {
            return "wheelchair";
        }
        if (f.contains("지팡이")) {
            return "cane";
        }
        if (f.contains("보행기")) {
            return "walker";
        }
        if (f.contains("유모차")) {
            return "stroller";
        }
        if (f.equals("걸어서") || f.startsWith("걸어") || f.contains("그냥걸")) {
            return "walk";
        }
        return null;
    }

    /** 이동 보조 → 내 정보 칸 (mode·mobility_aid) */
    static Map<String, Object> aidFields(String aid) {
        Map<String, Object> f = new LinkedHashMap<>();
        switch (aid) {
            case "wheelchair", "manual_wheelchair" -> {
                f.put("mode", "wheelchair");
                f.put("mobility_aid", "manual_wheelchair");
            }
            case "power_wheelchair" -> {
                f.put("mode", "wheelchair");
                f.put("mobility_aid", "power_wheelchair");
            }
            case "cane", "walker" -> {
                f.put("mode", "senior");
                f.put("mobility_aid", aid);
            }
            case "stroller" -> {
                f.put("mode", "child");
                f.put("mobility_aid", "stroller");
            }
            default -> {
                f.put("mode", "adult");
                f.put("mobility_aid", null);
            }
        }
        return f;
    }

    static String aidWord(String aid) {
        return switch (aid) {
            case "wheelchair", "manual_wheelchair" -> "휠체어";
            case "power_wheelchair" -> "전동 휠체어";
            case "cane" -> "지팡이";
            case "walker" -> "보행기";
            case "stroller" -> "유모차";
            default -> "걸어서";
        };
    }

    /** 모드 → "휠체어로", "걸어서" */
    static String modeWord(String mode) {
        return switch (mode == null ? "adult" : mode) {
            case "wheelchair" -> "휠체어로";
            case "senior" -> "어르신 걸음으로";
            case "child" -> "어린이와 함께";
            default -> "걸어서";
        };
    }
}
