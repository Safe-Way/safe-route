package com.saferoute.assistant;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.saferoute.place.KakaoLocalClient;
import com.saferoute.place.Place;
import com.saferoute.place.PlaceSearchException;

/**
 * 카카오에 없는 광주 시내 택시 승강장 손 목록 (11-13).
 * 카카오 키워드 "택시승강장"은 광주 시내에서 송정역(해오름관에서 12.8km)밖에 안 나와서(사용자 맥 터미널 확인),
 * 실제로 있는 것을 확인한 곳만 주소로 넣는다. 좌표는 지어내지 않고 처음 쓸 때 카카오 주소 검색으로 구해 기억한다.
 * 새로 넣으려면 아래 목록에 한 줄 (주소 검색이 안 되는 곳은 where에 카카오 장소 이름).
 */
final class TaxiStands {

    /** name: 사용자에게 말할 이름 / where: 카카오 주소 또는 장소 이름 / byAddress: where가 주소면 true */
    record Stand(String name, String where, boolean byAddress) {
    }

    static final List<Stand> GWANGJU = List.of(
            new Stand("유스퀘어(광주종합버스터미널) 택시승강장", "광주 서구 무진대로 904", true),
            new Stand("광주역 택시승강장", "광주역", false));

    private static final Logger log = LoggerFactory.getLogger(TaxiStands.class);

    private final KakaoLocalClient kakao;
    private final Map<String, double[]> spots = new ConcurrentHashMap<>();

    TaxiStands(KakaoLocalClient kakao) {
        this.kakao = kakao;
    }

    /** 목록의 승강장을 lat·lon에서 거리와 함께 (좌표를 못 구한 곳은 빠짐, 다음에 다시 시도) */
    List<Place> around(double lat, double lon) {
        return GWANGJU.stream().map(s -> {
            double[] at = spot(s);
            return at == null ? null
                    : new Place("hand:" + s.name(), s.name(), s.byAddress() ? s.where() : null, at[0], at[1],
                            AssistantTools.straightM(lat, lon, at[0], at[1]));
        }).filter(p -> p != null).toList();
    }

    private double[] spot(Stand s) {
        double[] at = spots.get(s.name());
        if (at != null) {
            return at;
        }
        try {
            List<Place> found = s.byAddress() ? kakao.searchAddress(s.where()) : kakao.search(s.where(), null, null);
            if (found.isEmpty()) {
                log.warn("택시 승강장 손 목록 좌표 못 찾음: {}", s.name());
                return null;
            }
            // 장소 이름이면 이름이 똑같은 곳 먼저 ("광주역"을 찾았는데 "남광주역"이 앞에 오는 경우)
            Place p = found.stream().filter(f -> f.name().replace(" ", "").equals(s.where().replace(" ", "")))
                    .findFirst().orElse(found.get(0));
            at = new double[] {p.lat(), p.lon()};
            spots.put(s.name(), at);
            return at;
        } catch (PlaceSearchException e) {
            log.warn("택시 승강장 손 목록 좌표 검색 실패: {}", e.getMessage());
            return null;
        }
    }
}
