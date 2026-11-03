package com.saferoute.weather;

import java.time.ZonedDateTime;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.saferoute.situation.SituationProperties;

/**
 * 기상청 초단기실황으로 비·폭염·한파를 판단한다.
 * 기상청을 못 부르면 길찾기는 멈추지 않고 "모름(available=false)"으로 돌려준다.
 */
@Component
public class WeatherJudge {

    /** 비로 보는 강수형태: 1 비, 2 비/눈, 5 빗방울, 6 빗방울눈날림 (3 눈, 7 눈날림은 비가 아님) */
    static final Set<Integer> RAIN_TYPES = Set.of(1, 2, 5, 6);

    private static final Logger log = LoggerFactory.getLogger(WeatherJudge.class);

    private final KmaClient kma;
    private final SituationProperties props;

    public WeatherJudge(KmaClient kma, SituationProperties props) {
        this.kma = kma;
        this.props = props;
    }

    public WeatherResult judgeAt(double lat, double lon, ZonedDateTime now) {
        WeatherNow w;
        try {
            w = kma.now(KmaGrid.from(lat, lon), now);
        } catch (WeatherUnavailableException e) {
            log.warn("날씨 정보를 못 받았어요: {}", e.getMessage());
            return WeatherResult.unavailable();
        }
        if (w.tempC() == null && w.rain1hMm() == null && w.pty() == null) {
            return WeatherResult.unavailable();
        }
        // 강수형태를 알면 그걸로, 모르면 1시간 강수량이 0보다 크면 비
        boolean rain = w.pty() != null
                ? RAIN_TYPES.contains(w.pty())
                : w.rain1hMm() != null && w.rain1hMm() > 0;
        boolean heat = w.tempC() != null && props.isHeat(w.tempC());
        boolean cold = w.tempC() != null && props.isCold(w.tempC());
        return new WeatherResult(rain, heat, cold, w.tempC(), w.rain1hMm(), w.observedAt(), true);
    }

    /** 지금 시각으로 판단 */
    public WeatherResult judgeNow(double lat, double lon) {
        return judgeAt(lat, lon, ZonedDateTime.now(KmaClient.KOREA));
    }
}
