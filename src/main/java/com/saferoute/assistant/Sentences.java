package com.saferoute.assistant;

import java.util.List;

import com.saferoute.route.api.RouteResult;
import com.saferoute.situation.WeatherDust;

/**
 * 정해진 문장틀. AI가 없거나 실패하면 이 문장이 그대로 나간다 (시연이 AI 때문에 멈추지 않게).
 * 숫자·이름은 모두 도구 결과에서만 가져온다.
 */
final class Sentences {

    private Sentences() {
    }

    private static final java.util.regex.Pattern WALK_MIN = java.util.regex.Pattern.compile("걸어서 (\\d+)분");

    /**
     * 나가기 전 문장 다듬기 (규칙 문장·AI 문장 모두): "을(를)" 같은 두 갈래 조사를 하나로,
     * "드리겠어요" → "드릴게요", 휠체어 사용자에게 "걸어서 N분" → "휠체어로 N분" (비서 점검 11/06).
     */
    static String polish(String s, String mode) {
        if (s == null) {
            return null;
        }
        String out = Josa.fix(s).replace("드리겠어요", "드릴게요").replace("드리겠습니다", "드릴게요");
        if ("wheelchair".equals(mode)) {
            out = WALK_MIN.matcher(out).replaceAll("휠체어로 $1분");
        }
        return out;
    }

    /** "걸어서", 휠체어면 "휠체어로" */
    static String go(String mode) {
        return "wheelchair".equals(mode) ? "휠체어로" : "걸어서";
    }

    /** recommend_trip 결과를 말로 */
    static String trip(Turn t) {
        TripRecommender.Plan plan = t.plan;
        String dest = t.planDest == null ? "가실 곳" : t.planDest.name();
        if (plan.scored() == null) {
            return plan.failText();
        }
        Trip best = plan.scored().best();
        WeatherDust w = plan.weather();
        String mode = t.mode();
        StringBuilder sb = new StringBuilder();
        boolean walk = "walk".equals(best.type());
        if (!walk && (w.rain() || w.heat() || w.cold() || w.dustBad())) {
            sb.append("오늘은 ").append(TripScorer.weatherWord(ctx(w))).append(" 대중교통을 추천해요. ");
        }
        boolean heavy = "wheelchair".equals(mode) || "senior".equals(mode);
        boolean bad = w.rain() || w.heat() || w.cold() || w.dustBad();
        if (walk) {
            sb.append(dest).append("까지 ").append(go(mode)).append(' ').append(best.minutes()).append("분이에요.");
            if ("wheelchair".equals(mode)) {
                sb.append(" 휠체어가 못 지나는 곳과 확인된 턱을 피한 길이에요.");
            }
            if (bad && heavy && plan.scored().others().stream().noneMatch(o -> "transit".equals(o.type()))) {
                sb.append(" 지금은 버스 길을 찾지 못해서 ").append(go(mode).equals("휠체어로") ? "휠체어로 가는" : "걷는")
                        .append(" 길을 알려 드려요.");
            }
        } else {
            String how = best.summary().replaceAll(", \\d+분$", "");
            sb.append(dest).append("까지 ").append(Josa.ro(how)).append(' ').append(best.minutes()).append("분이에요.");
            if (best.firstBus() != null) {
                sb.append(" ").append(best.firstBus()).append("에 와요.");
            }
            if (best.lowFloor()) {
                sb.append(" 곧 오는 저상버스가 있어요.");
            }
        }
        for (String b : plan.scored().because()) {
            if (b.startsWith("지난번")) {
                sb.append(" ").append(b).append(".");
            }
        }
        for (Trip o : plan.scored().others()) {
            for (RouteResult.Event e : TripScorer.avoided(o)) {
                if (walk && TripScorer.avoided(best).stream().noneMatch(b -> b.title().equals(e.title()))) {
                    sb.append(" '").append(e.title()).append("' ").append(e.kind())
                            .append(e.kind().equals("행사") ? "가 열려 사람이 많아서 옆길로 안내할게요." : " 구간을 피해서 안내할게요.");
                }
            }
        }
        if (walk) {
            for (RouteResult.Event e : best.events()) {
                sb.append(" 가는 길에 '").append(e.title()).append("' ").append(Josa.iGa(e.kind())).append(" 있어요.");
                sb.append(e.avoid() ? " 피할 길이 없어서 지나가요. 조심하세요." : "");
            }
        }
        if (walk && plan.night()) {
            sb.append(" 밤이라 가로등이 많은 밝은 길로 골랐어요.");
        }
        if (t.convo.bodyNote() != null) {
            sb.append(" 몸이 불편하시다고 해서 덜 걷는 쪽을 먼저 봤어요. 힘드시면 '쉴 곳 어디 있어'라고 물어보세요.");
        } else if ("senior".equals(mode) && walk && best.rest() != null && best.rest() > 0) {
            sb.append(" 가는 길에 쉴 곳(쉼터·화장실)이 ").append(best.rest()).append("곳 있어요. 힘드시면 쉬어 가세요.");
        } else if ("senior".equals(mode) && walk) {
            sb.append(" 힘드시면 중간에 쉬어 가세요. '쉴 곳 어디 있어'라고 물으시면 가까운 쉼터를 알려 드려요.");
        } else if ("senior".equals(mode)) {
            sb.append(" 정류장까지 걷다가 힘드시면 '쉴 곳 어디 있어'라고 물어보세요.");
        }
        sb.append(weatherTail(w));
        return sb.toString().strip();
    }

    static TripScorer.Context ctx(WeatherDust w) {
        return new TripScorer.Context(null, w.rain(), w.heat(), w.cold(), w.dustBad(), false, false, null, null, null);
    }

    static String weatherTail(WeatherDust w) {
        StringBuilder sb = new StringBuilder();
        if (w.rain()) {
            sb.append(" 비가 와요. 우산을 챙기세요.");
        } else if (w.heat()) {
            sb.append(" 많이 더워요. 물을 챙기세요.");
        } else if (w.cold()) {
            sb.append(" 많이 추워요. 따뜻하게 입으세요.");
        }
        if (w.dustBad()) {
            sb.append(" 미세먼지가 나빠요. 마스크를 쓰세요.");
        }
        return sb.toString();
    }

    /** 날씨만 물었을 때 */
    static String weather(WeatherDust w) {
        if (!w.weatherAvailable() && !w.dustAvailable()) {
            return "지금은 날씨 정보를 받아 오지 못했어요.";
        }
        StringBuilder sb = new StringBuilder("오늘 날씨는 ");
        if (w.rain()) {
            sb.append("비가 와요.");
        } else if (w.heat()) {
            sb.append("많이 더워요.");
        } else if (w.cold()) {
            sb.append("많이 추워요.");
        } else {
            sb.append("걷기 괜찮아요.");
        }
        if (w.tempC() != null) {
            sb.append(" 기온은 ").append(Math.round(w.tempC())).append("도예요.");
        }
        if (w.dustAvailable()) {
            sb.append(w.dustBad() ? " 미세먼지가 나빠요." : " 미세먼지는 괜찮아요.");
        }
        sb.append(" 어디 가실 곳이 있으면 말씀해 주세요.");
        return sb.toString();
    }

    /** find_nearby 결과를 말로 */
    static String spot(Turn t) {
        if (t.spot == null) {
            return t.spotMessage == null ? "가까운 곳을 찾지 못했어요." : t.spotMessage;
        }
        StringBuilder sb = new StringBuilder("가장 가까운 ").append(Josa.eun(t.spotKindName)).append(" ")
                .append(t.spot.name());
        if (t.spot.distanceM() != null) {
            sb.append("으로 ").append(t.spot.distanceM()).append("m예요.");
        } else {
            sb.append("이에요.");
        }
        if ("이용 가능".equals(t.spot.status())) {
            sb.append(" 지금 열려 있어요.");
        }
        if (t.spotMessage != null) {
            sb.append(" ").append(t.spotMessage);
        }
        sb.append(" 가시려면 [여기로 가기]를 눌러 주세요.");
        return sb.toString();
    }

    /** 장소 후보 이름들 */
    static String names(List<Conversation.Place> ps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(ps.get(i).name());
        }
        return sb.toString();
    }
}
