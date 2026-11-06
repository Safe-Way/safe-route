package com.saferoute.me;

import java.util.ArrayList;
import java.util.List;

/**
 * 다닌 기록으로 내 정보를 조금씩 고친다 ("계단·대중교통 선호를 행동에서 배우기", 11/06).
 * 도착·그만둠을 적을 때마다 최근 기록 RECENT 건을 보고 정한다. 규칙은 쉽게 설명할 수 있게 단순하게 둔다.
 * <ul>
 *   <li>대중교통 선호(prefer_transit): 끝난 기록이 MIN_TRIPS 건 이상일 때, 대중교통 비율이 70% 이상이면 2(좋아함),
 *       30% 이하면 0(걷기 좋아함), 그 사이면 1(보통)</li>
 *   <li>계단(stairs_ok): 방금 끝난 이동에 "계단" 불편이 있고, 최근 기록에 계단 불편이 모두 STAIRS_COMPLAINTS 번 이상이면
 *       한 단계 낮춘다 (2 → 1 → 0, 모르면 1). 안전 때문에 저절로 올리지는 않는다 (올리는 건 사용자가 직접)</li>
 * </ul>
 */
final class PreferenceLearner {

    static final int RECENT = 10;
    static final int MIN_TRIPS = 5;
    static final double LIKES = 0.7;
    static final double DISLIKES = 0.3;
    static final int STAIRS_COMPLAINTS = 2;

    private PreferenceLearner() {
    }

    /** 끝난 기록 하나 (최근 것부터) */
    record Done(String travel, String result, String feedback, String reason) {

        boolean stairsComplaint() {
            return "bad".equals(feedback) && reason != null && reason.contains("계단");
        }
    }

    /**
     * @param recent      최근 끝난 기록 (최근 것부터, 방금 끝난 것 포함)
     * @param justEnded   방금 끝난 기록
     * @param transitNow  지금 prefer_transit (모르면 null)
     * @param stairsNow   지금 stairs_ok (모르면 null)
     */
    static List<MeRequests.Learned> learn(List<Done> recent, Done justEnded, Integer transitNow, Integer stairsNow) {
        List<MeRequests.Learned> out = new ArrayList<>();
        List<Done> arrived = recent.stream().filter(d -> "arrived".equals(d.result())).toList();
        if (arrived.size() >= MIN_TRIPS) {
            double share = arrived.stream().filter(d -> "transit".equals(d.travel())).count() / (double) arrived.size();
            int want = share >= LIKES ? 2 : share <= DISLIKES ? 0 : 1;
            if (transitNow == null || transitNow != want) {
                String say = switch (want) {
                    case 2 -> "요즘 대중교통을 자주 타셔서, 앞으로 대중교통 길을 조금 더 먼저 보여 드릴게요.";
                    case 0 -> "요즘 주로 걸어가셔서, 앞으로 걷는 길을 조금 더 먼저 보여 드릴게요.";
                    default -> "걷기와 대중교통을 고르게 쓰셔서, 둘을 똑같이 비교해 드릴게요.";
                };
                out.add(new MeRequests.Learned("preferTransit", transitNow, want, say));
            }
        }
        if (justEnded != null && justEnded.stairsComplaint()
                && recent.stream().filter(Done::stairsComplaint).count() >= STAIRS_COMPLAINTS
                && (stairsNow == null || stairsNow > 0)) {
            int want = stairsNow == null ? 1 : stairsNow - 1;
            out.add(new MeRequests.Learned("stairsOk", stairsNow, want, want == 0
                    ? "계단이 힘드셨다는 말이 쌓여서, 앞으로 계단 없는 길로 안내할게요."
                    : "계단이 힘드셨다는 말이 쌓여서, 앞으로 계단이 적은 길로 안내할게요."));
        }
        return out;
    }
}
