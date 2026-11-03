package com.saferoute.air;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.saferoute.situation.SituationProperties;

/**
 * 시도 안 측정소 값의 평균으로 먼지가 나쁜지 판단한다.
 * 에어코리아를 못 부르면 길찾기는 멈추지 않고 "모름(available=false)"으로 돌려준다.
 */
@Component
public class DustJudge {

    private static final Logger log = LoggerFactory.getLogger(DustJudge.class);

    private final AirKoreaClient air;
    private final SidoFinder sidoFinder;
    private final SituationProperties props;

    public DustJudge(AirKoreaClient air, SidoFinder sidoFinder, SituationProperties props) {
        this.air = air;
        this.sidoFinder = sidoFinder;
        this.props = props;
    }

    /** 위도·경도로 판단한다. 시도를 못 찾으면 "모름" */
    public DustResult judgeAt(double lat, double lon) {
        String sido;
        try {
            sido = sidoFinder.sidoAt(lat, lon);
        } catch (AirUnavailableException e) {
            log.warn("시도를 못 찾았어요: {}", e.getMessage());
            return DustResult.unavailable(null);
        }
        return judge(sido);
    }

    public DustResult judge(String sidoName) {
        List<StationAir> stations;
        try {
            stations = air.sido(sidoName);
        } catch (AirUnavailableException e) {
            log.warn("미세먼지 정보를 못 받았어요: {}", e.getMessage());
            return DustResult.unavailable(sidoName);
        }
        Integer pm10 = average(stations.stream().map(StationAir::pm10).toList());
        Integer pm25 = average(stations.stream().map(StationAir::pm25).toList());
        if (pm10 == null && pm25 == null) {
            return DustResult.unavailable(sidoName);
        }
        String dataTime = stations.stream().map(StationAir::dataTime)
                .filter(t -> t != null && !t.isBlank()).findFirst().orElse(null);
        return new DustResult(props.isDustBad(pm10, pm25), pm10, pm25, sidoName, dataTime, true);
    }

    /** 값이 있는 측정소만 평균내서 반올림. 하나도 없으면 null */
    static Integer average(List<Integer> values) {
        List<Integer> valid = values.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) {
            return null;
        }
        return (int) Math.round(valid.stream().mapToInt(Integer::intValue).average().orElse(0));
    }
}
