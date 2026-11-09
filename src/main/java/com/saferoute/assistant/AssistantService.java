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
        return reply(t, o);
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
        List<String> called = new ArrayList<>();
        for (String name : t.tools) {
            called.add(name.replace(":confirm", ""));
        }
        return new AssistantReply(say, pick, others, because, ask, o.choices(), t.spot,
                codec.encode(next), List.copyOf(called), o.ai(), situation, stops);
    }

    static final String WHEELCHAIR_WHY = "확인된 턱과 못 지나는 곳을 피한 길이에요.";

    /**
     * 제미나이가 말을 바꿔도 꼭 들어가야 하는 문장을 서버가 붙인다 (비서 점검 c10).
     * 휠체어로 걷는 길을 추천했으면 왜 이 길인지 한 줄.
     */
    static String mustSay(String say, String mode, Pick pick) {
        if (say == null) {
            return null;
        }
        if ("wheelchair".equals(mode) && pick != null && "walk".equals(pick.type()) && !say.contains("피한 길")) {
            return say.strip() + " " + WHEELCHAIR_WHY;
        }
        return say;
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
