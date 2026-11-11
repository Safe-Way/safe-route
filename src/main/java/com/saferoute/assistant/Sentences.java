package com.saferoute.assistant;

import java.util.ArrayList;
import java.util.List;

import com.saferoute.bus.BusArrival;
import com.saferoute.transit.TransitResult;

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
     * "드리겠어요"·"드리릴게요"(제미나이 오타) → "드릴게요", 휠체어 사용자에게 "걸어서 N분" → "N분" (비서 점검 11/06, 이동 수단 말 없이 시간만).
     */
    static String polish(String s, String mode) {
        if (s == null) {
            return null;
        }
        String out = Josa.fix(s).replace("드리겠어요", "드릴게요").replace("드리겠습니다", "드릴게요")
                .replace("드리릴게요", "드릴게요");
        if ("wheelchair".equals(mode)) {
            out = WALK_MIN.matcher(out).replaceAll("$1분");
        }
        return out;
    }

    /**
     * 들를 곳 묶기 결과를 말로.
     * 예: "다이소 충장로점, GS25 충장점(택배) 들렀다 집까지 25분 걸려요. 바로 가는 것보다 6분 더 걸려요."
     */
    static String stops(Turn t) {
        StopPlanner.Plan p = t.stopPlan;
        if (p.failText() != null) {
            return p.failText();
        }
        String dest = t.planDest == null ? "가실 곳" : t.planDest.name();
        StringBuilder sb = new StringBuilder(stopNames(p)).append(" 들렀다 ").append(dest).append("까지 ")
                .append(p.minutes()).append("분 걸려요.");
        if (p.directMin() != null) {
            int extra = p.minutes() - p.directMin();
            sb.append(extra > 0 ? " 바로 가는 것보다 " + extra + "분 더 걸려요." : " 바로 가는 것과 시간이 거의 같아요.");
        }
        if (p.savedMin() != null && !p.stops().isEmpty()) {
            sb.append(" 순서를 바꾸면 ").append(p.savedMin()).append("분 덜 걸려서 ")
                    .append(p.stops().get(0).name()).append("부터 들르게 했어요.");
        }
        if ("wheelchair".equals(t.mode())) {
            sb.append(" 확인된 턱과 못 지나는 곳을 피한 길이에요.");
        }
        return sb.toString();
    }

    /** "다이소 충장로점 → GS25 충장점(택배) 들렀다, 25분" */
    static String stopSummary(StopPlanner.Plan p) {
        return stopNames(p).replace(", ", " → ") + " 들렀다, " + p.minutes() + "분";
    }

    private static String stopNames(StopPlanner.Plan p) {
        List<String> names = new ArrayList<>();
        for (StopPlanner.Stop s : p.stops()) {
            names.add(s.name().contains(s.label()) ? s.name() : s.name() + "(" + s.label() + ")");
        }
        return String.join(", ", names);
    }

    /** 엔진 events[].kind 의 신고 (원일님 w11 RoadReport.KIND) */
    static final String REPORT = "신고";

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
            // "문화전당까지 22분 걸려요." 시간은 모드 기본 속도(잰 적이 있으면 내 속도)로 센 값
            sb.append(dest).append("까지 ").append(best.minutes()).append("분 걸려요.");
            if ("wheelchair".equals(mode)) {
                sb.append(" 확인된 턱과 못 지나는 곳을 피한 길이에요.");
            }
            if (bad && heavy && plan.scored().others().stream().noneMatch(o -> "transit".equals(o.type()))) {
                sb.append(" 지금은 버스 길을 찾지 못해서 이 길을 알려 드려요.");
            }
        } else {
            sb.append(dest).append("까지 ").append(best.minutes()).append("분 걸려요. ");
            String steps = steps(best.legs());
            if (steps != null) {
                sb.append(steps);
            } else {
                String how = best.summary().replaceAll(", \\d+분$", "");
                sb.append(Josa.ro(how)).append(" 가요.");
            }
            String next = nextBus(best.legs());
            if (next != null) {
                sb.append(' ').append(next);
            } else {
                if (best.firstBus() != null) {
                    sb.append(" ").append(best.firstBus()).append("에 와요.");
                }
                if (best.lowFloor()) {
                    sb.append(" 곧 오는 저상버스가 있어요.");
                }
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
                    if (REPORT.equals(e.kind())) {
                        sb.append(" ").append(e.title()).append(" 신고가 있는 곳을 피해서 안내할게요.");
                        continue;
                    }
                    sb.append(" '").append(e.title()).append("' ").append(e.kind())
                            .append(e.kind().equals("행사") ? "가 열려 사람이 많아서 옆길로 안내할게요." : " 구간을 피해서 안내할게요.");
                }
            }
        }
        if (walk) {
            for (RouteResult.Event e : best.events()) {
                if (REPORT.equals(e.kind())) {
                    // 신고는 title 이 종류 이름뿐이라 따옴표 없이 "가는 길에 공사 신고가 있어요."
                    sb.append(" 가는 길에 ").append(e.title()).append(" 신고가 있어요.");
                } else {
                    sb.append(" 가는 길에 '").append(e.title()).append("' ").append(Josa.iGa(e.kind())).append(" 있어요.");
                }
                sb.append(e.avoid() ? " 피할 길이 없어서 지나가요. 조심하세요." : "");
            }
        }
        if (walk && plan.night()) {
            sb.append(" 밤이라 가로등이 많은 밝은 길로 골랐어요.");
        }
        if (w.rain()) {
            sb.append(roof(best));
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

    /**
     * 대중교통 구간을 차례대로 말로.
     * "금남로4가 정류장까지 5분 가서, 59번 버스를 타고 6정거장(10분), 남광주시장 정류장에서 내려 3분 더 가요."
     * 구간 정보가 없으면 null (한 줄 요약으로 대신 말함)
     */
    static String steps(List<TransitResult.Leg> legs) {
        if (legs == null || legs.stream().noneMatch(l -> !"walk".equals(l.type()))) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        boolean rode = false;
        for (int i = 0; i < legs.size(); i++) {
            TransitResult.Leg l = legs.get(i);
            if ("walk".equals(l.type())) {
                TransitResult.Leg next = i + 1 < legs.size() ? legs.get(i + 1) : null;
                if (next == null) {
                    parts.add(l.minutes() > 0 ? l.minutes() + "분 더 가요" : "도착해요");
                } else if (l.minutes() > 0) {
                    parts.add(stop(next.from(), next.type()) + "까지 " + l.minutes() + "분 가서");
                }
                continue;
            }
            String ride = "bus".equals(l.type())
                    ? String.join("·", l.lines()) + "번 버스를 타고"
                    : Josa.eul("지하철 " + String.join("·", l.lines())) + " 타고";
            if (rode && (i == 0 || !"walk".equals(legs.get(i - 1).type()))) {
                ride = stop(l.from(), l.type()) + "에서 " + ride;
            }
            String count = l.stationCount() == null ? "" : " " + l.stationCount() + ("bus".equals(l.type()) ? "정거장" : "역");
            parts.add(ride + count + "(" + l.minutes() + "분)");
            String off = stop(l.to(), l.type()) + "에서 내려";
            boolean last = i + 1 == legs.size();
            parts.add(last ? off + "요" : off);
            rode = true;
        }
        String s = String.join(", ", parts).replace("내려, ", "내려 ");
        if (s.endsWith("내려요")) {
            s = s.substring(0, s.length() - 3) + "내리면 돼요";
        }
        return s + ".";
    }

    /** "금남로4가 정류장", "문화전당역" (이름이 이미 "역"으로 끝나면 그대로) */
    private static String stop(TransitResult.Stop s, String type) {
        String name = s == null || s.name() == null ? "타는 곳" : s.name().strip();
        if ("subway".equals(type)) {
            return name.endsWith("역") ? name : name + "역";
        }
        return name.endsWith("정류장") || name.endsWith("역") ? name : name + " 정류장";
    }

    /** "다음 저상버스는 4분 뒤예요." 첫 버스 구간의 곧 오는 버스. 모르면 null */
    static String nextBus(List<TransitResult.Leg> legs) {
        if (legs == null) {
            return null;
        }
        for (TransitResult.Leg l : legs) {
            if ("subway".equals(l.type())) {
                return null;
            }
            if ("bus".equals(l.type())) {
                if (l.buses() == null || l.buses().isEmpty()) {
                    return null;
                }
                BusArrival b = l.buses().stream().filter(x -> x.remainMin() != null && x.lowFloor()).findFirst()
                        .orElse(l.buses().stream().filter(x -> x.remainMin() != null).findFirst().orElse(null));
                if (b == null) {
                    return null;
                }
                return (b.lowFloor() ? "다음 저상버스는 " : "다음 버스는 ")
                        + (b.remainMin() <= 0 ? "곧 와요." : b.remainMin() + "분 뒤예요.");
            }
        }
        return null;
    }

    /**
     * 비 오는 날 이 길에서 지붕 아래(지하 포함)로 걷는 길이. 엔진 covered.roof 를 그대로 더한다 (원일님 2026-11-09).
     * 걷기는 그 경로 하나, 대중교통은 걷는 구간들. 10m 단위로 말하고 0이면 말하지 않는다.
     * "이 길은 600m가 지붕 있는 길(지하 포함)이에요." / "걷는 길 중 300m는 지붕 있는 길(지하 포함)이에요."
     */
    static String roof(Trip t) {
        boolean walk = "walk".equals(t.type());
        int m = 0;
        if (walk) {
            m = roofM(t.walkRoute());
        } else {
            for (TransitResult.Leg l : t.legs()) {
                if ("walk".equals(l.type())) {
                    m += roofM(l.walkRoute());
                }
            }
        }
        int said = Math.round(m / 10f) * 10;
        if (said <= 0) {
            return "";
        }
        return walk ? " 이 길은 " + said + "m가 지붕 있는 길(지하 포함)이에요."
                : " 걷는 길 중 " + said + "m는 지붕 있는 길(지하 포함)이에요.";
    }

    private static int roofM(RouteResult.Route r) {
        if (r == null || r.covered() == null) {
            return 0;
        }
        return r.covered().getOrDefault("roof", 0);
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
