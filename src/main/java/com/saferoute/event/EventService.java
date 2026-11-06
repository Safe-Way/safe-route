package com.saferoute.event;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 지도에 그릴 행사·돌발상황 (C 파트, GET /api/events).
 * 길이 지나는 행사와 피하기는 B 경로 엔진이 routes[].events 로 준다. 여기서는 지도 범위 안 목록만.
 */
@Service
public class EventService {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    static final int LIST_LIMIT = 300;
    static final double MAX_SPAN_DEG = 0.5;

    private final EventRepository repo;
    private final Clock clock;

    public EventService(EventRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 지도 범위 안 지금 진행 중인 행사·돌발 */
    public List<RoadEvent> within(double west, double south, double east, double north) {
        if (west >= east || south >= north) {
            throw new IllegalArgumentException("bbox 는 서쪽경도,남쪽위도,동쪽경도,북쪽위도 순서예요");
        }
        if (east - west > MAX_SPAN_DEG || north - south > MAX_SPAN_DEG) {
            throw new IllegalArgumentException("지도를 조금 더 확대해 주세요");
        }
        return repo.active(west, south, east, north, LocalDateTime.now(clock.withZone(KOREA)), LIST_LIMIT);
    }
}
