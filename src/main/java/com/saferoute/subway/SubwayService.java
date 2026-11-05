package com.saferoute.subway;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.saferoute.subway.SubwayRepository.TimetableRow;

/**
 * 내 주변 지하철역과 곧 오는 열차 (2차, 광주 1호선).
 * <ul>
 *   <li>광주 지하철은 실시간 공개 API가 없어서, 열차 도착 시각과 "지금 어느 역 근처"를 시간표로 계산한다 (basis = timetable)</li>
 *   <li>요일 종류: 설·추석 공휴일 = 명절, 다른 공휴일·일요일 = 휴일, 토요일 = 토요일, 나머지 = 평일</li>
 *   <li>새벽 3시 전은 전날 시간표의 연장으로 본다 (자정 넘어 다니는 막차)</li>
 *   <li>사용자 위치는 로그에 쓰지 않는다 (NFR-07)</li>
 * </ul>
 */
@Service
public class SubwayService {

    static final int RADIUS_M = 1000;
    static final int MAX_STATIONS = 3;
    static final int TRAINS_PER_DIRECTION = 2;
    static final int DAY_START_MIN = 180; // 03:00 전은 전날로 친다

    /** 시간표 역 코드 순서 (녹동 100 ~ 평동 119). load_transit.py의 STATIONS와 같다 */
    static final List<String> STATIONS = List.of("녹동", "소태", "학동증심사입구", "남광주", "문화전당", "금남로4가",
            "금남로5가", "양동시장", "돌고개", "농성", "화정", "쌍촌", "운천", "상무", "김대중컨벤션센터", "공항", "송정공원",
            "광주송정", "도산", "평동");
    static final int FIRST_CODE = 100;
    static final int TO_PYEONGDONG = 1; // 코드가 커지는 쪽
    static final int TO_NOKDONG = 2;    // 코드가 작아지는 쪽

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SubwayRepository repo;
    private final Clock clock;
    private final Map<String, Timetable> tables = new ConcurrentHashMap<>();

    public SubwayService(SubwayRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * @throws org.springframework.dao.DataAccessException DB가 고장 났을 때 (창구가 503으로 바꾼다)
     */
    public SubwayNearbyResponse nearby(double lat, double lon) {
        List<SubwayStation> found = repo.near(lat, lon, RADIUS_M, MAX_STATIONS);
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SEOUL));
        LocalDate serviceDay = now.toLocalDate();
        int nowMin = now.getHour() * 60 + now.getMinute();
        if (nowMin < DAY_START_MIN) {
            serviceDay = serviceDay.minusDays(1);
            nowMin += 1440;
        }
        String dayType = dayType(serviceDay);
        Timetable table = found.isEmpty() ? Timetable.EMPTY : table(dayType);
        List<StationTrains> out = new ArrayList<>();
        for (SubwayStation s : found) {
            List<SubwayTrain> trains = new ArrayList<>();
            trains.addAll(table.next(s.timetableCode(), TO_PYEONGDONG, nowMin));
            trains.addAll(table.next(s.timetableCode(), TO_NOKDONG, nowMin));
            trains.sort(Comparator.comparingInt(SubwayTrain::inMin));
            out.add(new StationTrains(s.id(), s.name(), s.lat(), s.lon(), s.distanceM(),
                    new StationTrains.Facility(s.elevatorTotal(), s.elevatorAvailable()),
                    new StationTrains.Facility(s.liftTotal(), s.liftAvailable()),
                    new StationTrains.Facility(s.toiletTotal(), s.toiletAvailable()),
                    s.elevatorBrokenGates(), List.copyOf(trains),
                    StationTrains.ElevatorExits.of(s.elevatorExitCount(), s.elevatorExitNos(),
                            s.elevatorExitUnknown(), s.exitTotal())));
        }
        return new SubwayNearbyResponse("timetable", table.dayType == null ? dayType : table.dayType, out);
    }

    /** 그 날짜에 쓸 시간표 이름 */
    String dayType(LocalDate day) {
        String holiday = repo.holidayName(day);
        if (holiday != null && (holiday.contains("설") || holiday.contains("추석"))) {
            return "명절";
        }
        if (holiday != null || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return "휴일";
        }
        return day.getDayOfWeek() == DayOfWeek.SATURDAY ? "토요일" : "평일";
    }

    /** 시간표를 처음 한 번만 DB에서 읽고 기억한다. 그 요일 종류가 비어 있으면 명절→휴일→평일 순으로 대신 쓴다 */
    private Timetable table(String dayType) {
        for (String t : fallbacks(dayType)) {
            Timetable kept = tables.get(t);
            if (kept != null) {
                return kept;
            }
            List<TimetableRow> rows = repo.timetable(t);
            if (!rows.isEmpty()) {
                Timetable built = Timetable.of(t, rows);
                tables.put(t, built);
                return built;
            }
        }
        return Timetable.EMPTY;
    }

    private static List<String> fallbacks(String dayType) {
        return switch (dayType) {
            case "명절" -> List.of("명절", "휴일", "평일");
            case "휴일", "토요일" -> List.of(dayType, "평일");
            default -> List.of("평일");
        };
    }

    static String stationName(int code) {
        int i = code - FIRST_CODE;
        return i >= 0 && i < STATIONS.size() ? STATIONS.get(i) : null;
    }

    /** 새벽 3시 전 시각은 전날 이어서 (+24시간) 센다 */
    static int serviceMinute(int minute) {
        return minute < DAY_START_MIN ? minute + 1440 : minute;
    }

    /** 역·방향별로 도착 시각을 줄 세워 둔 시간표 */
    static final class Timetable {

        static final Timetable EMPTY = new Timetable(null, Map.of());

        final String dayType;
        private final Map<Integer, List<Arrival>> byStop; // 키 = 역 코드 * 10 + 방향

        private Timetable(String dayType, Map<Integer, List<Arrival>> byStop) {
            this.dayType = dayType;
            this.byStop = byStop;
        }

        static Timetable of(String dayType, List<TimetableRow> rows) {
            Map<Integer, List<Arrival>> map = new HashMap<>();
            for (TimetableRow r : rows) {
                map.computeIfAbsent(key(r.stationCode(), r.direction()), k -> new ArrayList<>())
                        .add(new Arrival(serviceMinute(r.minute()), r.endStation()));
            }
            map.values().forEach(l -> l.sort(Comparator.comparingInt(Arrival::minute)));
            return new Timetable(dayType, map);
        }

        private static int key(int code, int direction) {
            return code * 10 + direction;
        }

        private List<Arrival> at(int code, int direction) {
            return byStop.getOrDefault(key(code, direction), List.of());
        }

        /** 이 역·방향에 nowMin 이후 들어오는 열차 몇 대 */
        List<SubwayTrain> next(int code, int direction, int nowMin) {
            List<SubwayTrain> out = new ArrayList<>();
            for (Arrival a : at(code, direction)) {
                if (a.minute() < nowMin) {
                    continue;
                }
                Near near = whereNow(code, direction, a.minute(), nowMin);
                out.add(new SubwayTrain(direction == TO_PYEONGDONG ? "평동행" : "녹동행", a.endStation(),
                        hhmm(a.minute()), a.minute() - nowMin, near == null ? null : near.name(),
                        near == null ? null : near.stopsAway()));
                if (out.size() == TRAINS_PER_DIRECTION) {
                    break;
                }
            }
            return out;
        }

        /**
         * 이 역에 arriveMin에 들어올 열차가 지금 어디쯤인지 (시간표 기준).
         * 바로 옆 역끼리는 걸리는 시간(약 2분)이 배차 간격보다 짧아서, "윗역에서 떠난 뒤 아랫역에 처음 오는 열차"가
         * 같은 열차다. 이걸로 한 칸씩 거슬러 올라가며 그 열차의 윗역 도착 시각을 찾고, 지금보다 이른 역이 나오면
         * 그 역을 지나온 것이다. 거슬러 올라가다 끊기면(그 역에서 출발하는 열차) 아직 출발 전이다.
         */
        Near whereNow(int code, int direction, int arriveMin, int nowMin) {
            int step = direction == TO_PYEONGDONG ? -1 : 1; // 오는 쪽(윗역)으로 한 칸
            int down = code;
            int target = arriveMin;
            for (int k = 1; k < STATIONS.size(); k++) {
                int up = code + step * k;
                if (stationName(up) == null) {
                    return null;
                }
                List<Arrival> downList = at(down, direction);
                Integer found = null;
                for (Arrival a : at(up, direction)) {
                    if (a.minute() > target) {
                        break;
                    }
                    if (firstAtOrAfter(downList, a.minute()) == target) {
                        found = a.minute();
                    }
                }
                if (found == null) {
                    return null; // 이 열차는 down 역에서 출발 → 아직 출발 전
                }
                if (found <= nowMin) {
                    return new Near(stationName(up), k);
                }
                down = up;
                target = found;
            }
            return null;
        }

        private static int firstAtOrAfter(List<Arrival> list, int minute) {
            for (Arrival a : list) {
                if (a.minute() >= minute) {
                    return a.minute();
                }
            }
            return -1;
        }

        private static String hhmm(int minute) {
            int m = minute % 1440;
            return String.format("%02d:%02d", m / 60, m % 60);
        }
    }

    record Arrival(int minute, String endStation) {
    }

    record Near(String name, int stopsAway) {
    }
}
