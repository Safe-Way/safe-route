package com.saferoute.situation;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.shredzone.commons.suncalc.SunTimes;
import org.springframework.stereotype.Component;

/**
 * 밤인지 정한다 (FR-02 1차). 인터넷 없이 위치와 시각만으로 일몰을 계산한다.
 * 해 뜨기 전이거나 해 진 뒤면 밤이다.
 */
@Component
public class NightJudge {

    /** 서비스 지역이 한국이라 시각은 모두 한국 시간으로 본다 */
    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public NightResult judge(double lat, double lon, ZonedDateTime at, NightOption option) {
        ZonedDateTime local = at.withZoneSameInstant(KOREA);
        SunTimes sun = SunTimes.compute()
                .on(local.toLocalDate())
                .timezone(KOREA)
                .at(lat, lon)
                .execute();
        ZonedDateTime rise = sun.getRise();
        ZonedDateTime set = sun.getSet();

        boolean night = switch (option) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> local.isBefore(rise) || !local.isBefore(set);
        };
        String source = option == NightOption.AUTO ? "auto" : "manual";
        return new NightResult(night, source, toMinute(rise), toMinute(set));
    }

    /** 지금 시각으로 판단 */
    public NightResult judgeNow(double lat, double lon, NightOption option) {
        return judge(lat, lon, ZonedDateTime.now(KOREA), option);
    }

    private static LocalTime toMinute(ZonedDateTime time) {
        return time.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
    }
}
