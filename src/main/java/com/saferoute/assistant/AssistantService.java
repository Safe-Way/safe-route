package com.saferoute.assistant;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.saferoute.assistant.AssistantReply.Other;
import com.saferoute.assistant.AssistantReply.Pick;
import com.saferoute.assistant.AssistantReply.Situation;
import com.saferoute.route.api.RouteResult;
import com.saferoute.situation.WeatherDust;
import com.saferoute.transit.TransitResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * 길찾기 비서 (C 파트, 팀 설계 "AI 도구 설계" 11/06). POST /api/assistant 한 번에:
 * ① 급한 말이면 112·보호자부터 ② 저장 전 확인("맞으세요?")에 대한 네·아니요면 저장 ③ 방금 되물은 것의 답이면 이어서
 * ④ 그 밖은 제미나이가 도구 7개 중에서 골라 부르고(GeminiBrain), 안 되면 규칙이 같은 도구를 부른다(RuleBrain).
 * 점수는 늘 우리 규칙(TripScorer)대로. 대화는 저장하지 않고 conversationId 에 담아 앱에 돌려준다 (NFR-07).
 * 제미나이에는 좌표를 보내지 않는다.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final RuleBrain rules;
    private final GeminiBrain gemini;
    private final AssistantTools tools;
    private final Conversation.Codec codec;
    private final Clock clock;

    public AssistantService(RuleBrain rules, GeminiBrain gemini, AssistantTools tools, JsonMapper json, Clock clock) {
        this.rules = rules;
        this.gemini = gemini;
        this.tools = tools;
        this.codec = new Conversation.Codec(json);
        this.clock = clock;
    }

    public AssistantReply answer(AssistantRequest req) {
        Turn t = turn(req == null ? AssistantRequest.empty() : req);
        Outcome o = think(t);
        AssistantReply r = reply(t, o);
        AssistantReply.Usage u = r.usage();
        // 말 원문·게스트 번호는 남기지 않는다 (R1)
        log.info("비서 토큰 합: 모델 {} 왕복 {} 입력 {} 출력 {} 보낸 도구 {} 다시 물음 {} 참고 {}",
                t.usage.model() == null ? "-" : t.usage.model(), u.rounds(), u.prompt(), u.output(), u.tools(), u.retried(),
                t.knowledgeTopics);
        return r;
    }

    private Turn turn(AssistantRequest req) {
        LocalDateTime real = LocalDateTime.now(clock.withZone(KOREA));
        AssistantRequest.Test test = req.test();
        LocalDateTime now = test == null ? null : KoreanTime.testTime(test.time(), real.toLocalDate()).orElse(null);
        WeatherDust weather = test == null ? null : testWeather(test.weather());
        return new Turn(req, codec.decode(req.conversationId()), now == null ? real : now, now != null, weather);
    }

    Outcome think(Turn t) {
        // ① 급한 말
        if (RuleBrain.urgent(t.text)) {
            t.convo = t.convo.withPending(null).asked(null);
            return RuleBrain.emergency();
        }
        tools.loadProfile(t);
        // ①-1 말로 할 때 끼는 "어.. 잠시만.. 아" 같은 말은 빼고 본다 (11-12 음성 시험)
        t.text = Fillers.clean(t.text);
        // ①-2 "광주역에서 …"처럼 출발지를 말했으면 떼어 찾아 두고, 나머지 말로 이어 간다 (11-12)
        Outcome from = origin(t);
        if (from != null) {
            return from;
        }
        // ② 저장 전 확인에 대한 답
        if (t.convo.pending() != null) {
            Outcome o = rules.confirm(t, t.convo.pending());
            if (o != null) {
                return o;
            }
        }
        // ③ 버튼으로 고른 모드, 방금 되물은 것의 답
        if (t.req.choice() != null && AssistantTools.MODES.contains(t.req.choice())) {
            t.convo = t.convo.withMode(t.req.choice());
        }
        Outcome asked = rules.answerAsked(t);
        if (asked != null) {
            return asked;
        }
        // ④ 오늘 제안 (사용자가 말하지 않음)
        if (t.text.isEmpty() || t.text.startsWith("(")) {
            return rules.suggest(t);
        }
        // ⑤ 들를 곳 묶기 ("다이소 들르고 택배 찾고 집 갈래"). 고르기·길 찾기는 우리 규칙이 해서 AI를 거치지 않는다
        Outcome stops = rules.stops(t);
        if (stops != null) {
            return stops;
        }
        // ⑤ 몸 상태가 잠깐 바뀜 ("다쳐서 휠체어 타고 다녀"). 끝나는 날을 꼭 물어야 해서 규칙이 먼저 "언제까지?"
        Outcome temp = rules.tempBody(t);
        if (temp != null) {
            return temp;
        }
        // ⑤ 도구가 없는 요청 (못 하는 일·상관없는 말·인사). AI 비용을 아끼고 했다고 지어내지 않게 규칙이 먼저
        Outcome noTool = rules.noTool(t);
        if (noTool != null) {
            return noTool;
        }
        // ⑥ 제미나이 → 안 되면 규칙
        boolean aiDown = t.req.test() != null && "down".equals(t.req.test().ai());
        if (gemini.available() && !aiDown) {
            try {
                Outcome o = gemini.run(t);
                if (o != null) {
                    return o;
                }
            } catch (RuntimeException e) {
                log.warn("제미나이 비서 실패, 규칙으로 대신함: {}", e.getClass().getSimpleName());
            }
            t.confirm = null;
        }
        return rules.run(t);
    }

    /** 출발지 말을 떼고 찾는다. 못 찾았을 때만 되묻는 답, 아니면 null */
    private Outcome origin(Turn t) {
        OriginReader.Split s = t.convo.asked() == null ? OriginReader.split(t.text) : null;
        if (s == null) {
            // 새 길 찾기 말에 출발지가 없으면 지금 위치에서 출발 (되묻는 중이면 앞서 말한 출발지 그대로)
            if (t.convo.origin() != null && t.convo.asked() == null && t.req.choice() == null
                    && t.convo.pending() == null) {
                t.convo = t.convo.withOrigin(null);
            }
            return null;
        }
        t.text = s.rest();
        if (s.origin() == null) {
            t.convo = t.convo.withOrigin(null);
            return null;
        }
        AssistantTools.OriginFound f = tools.findOrigin(t, s.origin());
        if (f.place() == null) {
            t.convo = t.convo.withOrigin(null);
            return Outcome.ask(f.say());
        }
        t.convo = t.convo.withOrigin(f.place());
        return null;
    }

    AssistantReply reply(Turn t, Outcome o) {
        Pick pick = null;
        List<Other> others = List.of();
        List<String> because = List.of();
        Situation situation = null;
        if (t.plan != null) {
            WeatherDust w = t.plan.weather();
            List<String> events = new ArrayList<>();
            if (t.plan.scored() != null) {
                Trip b = t.plan.scored().best();
                pick = new Pick(b.type(), b.request(), b.summary(), b.minutes(), b.route());
                others = t.plan.scored().others().stream()
                        .filter(x -> !(x.type().equals(b.type()) && "walk".equals(x.type())))
                        .map(x -> new Other(x.type(), x.summary(), x.minutes())).toList();
                because = t.plan.scored().because();
                for (RouteResult.Event e : b.events()) {
                    events.add(e.title());
                }
            }
            situation = new Situation(t.plan.night(), w.rain(), w.heat(), w.cold(), w.tempC(), w.weatherAvailable(),
                    w.dustBad(), w.dustAvailable(), List.copyOf(events));
        }
        List<AssistantReply.Stop> stops = List.of();
        StopPlanner.Plan sp = t.stopPlan;
        if (sp != null && sp.failText() == null) {
            pick = new Pick("walk", sp.request(), Sentences.stopSummary(sp), sp.minutes(), "safe");
            stops = sp.stops().stream()
                    .map(s -> new AssistantReply.Stop(s.label(), s.name(), s.address(), s.lat(), s.lon())).toList();
        }
        String mode = t.mode();
        String say = Sentences.polish(o.say(), mode);
        String ask = Sentences.polish(o.ask(), mode);
        if (o.ai()) {
            say = mustSay(say, mode, pick);
            if (pick != null && "transit".equals(pick.type()) && t.plan != null
                    && t.plan.scored() != null) {
                say = Sentences.polish(mustSayTransit(say, t.plan.scored().best().legs(), because), mode);
            }
            if (pick != null && t.plan != null && t.plan.night() && Sentences.scared(t.text)
                    && say != null && !say.contains("도와줘")) {
                say = say.strip() + " " + Sentences.SCARED_TIP;
            }
        }
        Conversation.Place from = t.convo.origin();
        if (pick != null && from != null && say != null && !say.contains(from.name())) {
            say = from.name() + "에서 출발해요. " + say.strip();
        }
        if (ask != null && say != null && ask.strip().equals(say.strip())) {
            // 같은 문장을 두 번 보내지 않는다 (비서 점검 c10). 버튼(choices)은 그대로 간다
            ask = null;
        }
        if (pick != null) {
            pick = new Pick(pick.type(), pick.request(), Sentences.polish(pick.summary(), mode), pick.minutes(),
                    pick.route());
        }
        others = others.stream().map(x -> new Other(x.type(), Sentences.polish(x.summary(), mode), x.minutes()))
                .toList();
        Conversation next = t.convo.withTurn("user", t.text).withTurn("model", say);
        List<String> called = called(t.tools);
        return new AssistantReply(say, pick, others, because, ask, o.choices(), t.spot,
                codec.encode(next.withLastTools(called)), List.copyOf(called), o.ai(), situation, stops,
                t.usage.snapshot(t.toolsSent, t.toolsRetried));
    }

    /** 응답 tools: 부른 도구 이름. 같은 도구를 바로 이어 두 번 부르면 한 번으로 (점검 11번: 제미나이가 search_place 를 두 번 부름) */
    static List<String> called(List<String> tools) {
        List<String> called = new ArrayList<>();
        for (String name : tools) {
            String n = name.replace(":confirm", "");
            if (called.isEmpty() || !called.get(called.size() - 1).equals(n)) {
                called.add(n);
            }
        }
        return called;
    }

    static final String WHEELCHAIR_WHY = Sentences.WHEELCHAIR_WHY;

    /** 제미나이가 예전 문장처럼 "턱을 피한 길"이라고 지어 말한 문장 (DB에 확인된 턱이 없어 사실과 다름) */
    private static final java.util.regex.Pattern CURB_CLAIM = java.util.regex.Pattern.compile(
            "\\s*[^.!?]*턱[^.!?]*피(한|했|해)[^.!?]*[.!?]");

    /**
     * 제미나이가 말을 바꿔도 꼭 들어가야 하는 문장을 서버가 붙인다 (비서 점검 c10).
     * 휠체어로 걷는 길을 추천했으면 왜 이 길인지 한 줄.
     */
    static String mustSay(String say, String mode, Pick pick) {
        if (say == null) {
            return null;
        }
        if ("wheelchair".equals(mode) && pick != null && "walk".equals(pick.type()) && !say.contains(WHEELCHAIR_WHY)) {
            String rest = CURB_CLAIM.matcher(say).replaceAll("").strip();
            return (rest.isEmpty() ? "" : rest + " ") + WHEELCHAIR_WHY;
        }
        // 어르신: 제미나이가 줄이면서 쉴 곳 안내를 빼먹으면 서버가 붙인다 (점검 11번 11/12, 규칙 답과 같은 문장)
        if ("senior".equals(mode) && pick != null && !say.contains("쉴") && !say.contains("쉬어")) {
            return say.strip() + " " + ("walk".equals(pick.type()) ? SENIOR_REST_WALK : SENIOR_REST_TRANSIT);
        }
        return say;
    }

    static final String SENIOR_REST_WALK = "힘드시면 중간에 쉬어 가세요. '쉴 곳 어디 있어'라고 물으시면 가까운 쉼터를 알려 드려요.";
    static final String SENIOR_REST_TRANSIT = "정류장까지 걷다가 힘드시면 '쉴 곳 어디 있어'라고 물어보세요.";

    /**
     * 대중교통을 추천했는데 제미나이가 말을 줄이면서 빼먹은 것을 서버가 붙인다 (비서 점검 1번 11/10).
     * 어떤 교통을 어디서 타는지(구간 순서)와 "지난번 비 올 때도 대중교통을 타셨어요".
     */
    static String mustSayTransit(String say, List<TransitResult.Leg> legs, List<String> because) {
        if (say == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(say.strip());
        String steps = Sentences.steps(legs);
        if (steps != null && !say.contains("타고")) {
            sb.append(' ').append(steps);
        }
        if (because != null && !say.contains("지난번")) {
            for (String b : because) {
                if (b.startsWith("지난번")) {
                    sb.append(' ').append(b).append('.');
                }
            }
        }
        if (because != null && because.contains(TripScorer.NIGHT_STOP_BRIGHT) && !say.contains("가로등")) {
            sb.append(' ').append(TripScorer.NIGHT_STOP_SAY);
        }
        if (because != null && !say.contains("어두운")) {
            because.stream().filter(b -> b.startsWith(TripScorer.NIGHT_STOP_DARK)).findFirst()
                    .ifPresent(b -> sb.append(' ').append(b).append('.'));
        }
        return sb.toString();
    }

    /** 시험 날씨 → 판단 결과 (clear, rain, heat, cold, dust) */
    static WeatherDust testWeather(String w) {
        if (w == null || w.isBlank()) {
            return null;
        }
        return switch (w.strip()) {
            case "rain" -> new WeatherDust(true, false, false, 12.0, true, false, true);
            case "heat" -> new WeatherDust(false, true, false, 34.0, true, false, true);
            case "cold" -> new WeatherDust(false, false, true, -11.0, true, false, true);
            case "dust" -> new WeatherDust(false, false, false, 15.0, true, true, true);
            case "clear" -> new WeatherDust(false, false, false, 15.0, true, false, true);
            default -> null;
        };
    }
}
