package com.saferoute.assistant;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.saferoute.situation.WeatherDust;
import com.saferoute.situation.WeatherDustService;

/**
 * 오늘 제안 (팀 할 일 7번). 앱을 열면 먼저 부른다.
 * 같은 요일·시간대(지금 30분 전 ~ 90분 뒤)에 3번 이상 간 곳(trip_habit) + 지금 날씨 + 그런 날씨에 내가 보통 고른 것
 * (trip_weather_choice) → 가장 많이 간 곳까지 오늘의 길을 추천(recommend_trip 과 같은 규칙).
 * 비서(POST /api/assistant)에 빈 말을 보내는 것과 달리 대화 없이 한 번에 답하고, 처음 설정 질문을 하지 않는다.
 */
@Service
public class TodayService {

    private static final Logger log = LoggerFactory.getLogger(TodayService.class);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> MODES = Set.of("adult", "child", "senior", "wheelchair");
    /** 지금부터 이만큼 앞뒤 시간대의 습관을 본다 */
    static final int BEFORE_MIN = 30;
    static final int AFTER_MIN = 90;
    /** 날씨 습관을 말하려면 그 날씨에 이만큼은 다녔어야 함 */
    static final int WEATHER_MIN_TRIPS = 3;

    private final MemoryRepository memory;
    private final TripRecommender trips;
    private final WeatherDustService weatherDust;
    private final Clock clock;

    public TodayService(MemoryRepository memory, TripRecommender trips, WeatherDustService weatherDust, Clock clock) {
        this.memory = memory;
        this.trips = trips;
        this.weatherDust = weatherDust;
        this.clock = clock;
    }

    /** 잘못된 값은 IllegalArgumentException (창구가 400 으로 바꿈) */
    public TodaySuggestion today(String guestId, Double lat, Double lon, String appMode) {
        if (guestId == null || !UUID.matcher(guestId.strip()).matches()) {
            throw new IllegalArgumentException("게스트 번호(guestId) 모양이 틀렸어요");
        }
        String guest = guestId.strip().toLowerCase();
        boolean located = lat != null && lon != null;
        if (located && (lat < 33 || lat > 39 || lon < 124 || lon > 132)) {
            throw new IllegalArgumentException("위치(lat, lon)가 우리나라 안이 아니에요");
        }
        String app = appMode == null ? null : appMode.strip().toLowerCase();
        if (app != null && !app.isEmpty() && !MODES.contains(app)) {
            throw new IllegalArgumentException("모드는 adult·child·senior·wheelchair 중 하나예요");
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(KOREA));

        Profile profile = null;
        List<MemoryRepository.Habit> habits = List.of();
        try {
            profile = memory.profile(guest);
            LocalTime t = now.toLocalTime();
            LocalTime from = t.isBefore(LocalTime.of(0, BEFORE_MIN)) ? LocalTime.MIN : t.minusMinutes(BEFORE_MIN);
            LocalTime to = t.isAfter(LocalTime.of(23, 59).minusMinutes(AFTER_MIN)) ? LocalTime.of(23, 59)
                    : t.plusMinutes(AFTER_MIN);
            habits = memory.habits(guest, now.getDayOfWeek().getValue(), from, to);
        } catch (DataAccessException e) {
            log.warn("오늘 제안 기록 조회 실패: {}", e.getClass().getSimpleName());
        }
        String mode = profile != null && profile.modeNow() != null ? profile.modeNow()
                : app != null && !app.isEmpty() ? app : "adult";

        WeatherDust w = located ? weatherDust.judge(lat, lon) : WeatherDust.unknown();
        String word = word(w);
        String hint = null;
        if (word != null && !"clear".equals(word)) {
            try {
                hint = hint(word, memory.weatherChoice(guest, word));
            } catch (DataAccessException e) {
                log.warn("오늘 제안 날씨 기록 조회 실패: {}", e.getClass().getSimpleName());
            }
        }
        TodaySuggestion.Weather weather = new TodaySuggestion.Weather(word, w.rain(), w.heat(), w.cold(), w.tempC(),
                w.dustBad(), w.weatherAvailable(), w.dustAvailable());

        if (habits.isEmpty()) {
            String say = (word != null && !"clear".equals(word) ? weatherSentence(w) + " " : "")
                    + "어디 가실 곳이 있으세요? 말씀해 주시면 길을 찾아 드릴게요.";
            return new TodaySuggestion(say, null, List.of(), weather, hint, null, List.of());
        }
        MemoryRepository.Habit first = habits.get(0);
        TodaySuggestion.Habit habit = habit(first);
        List<TodaySuggestion.Habit> others = new ArrayList<>();
        for (MemoryRepository.Habit h : habits.subList(1, Math.min(habits.size(), 3))) {
            others.add(habit(h));
        }

        StringBuilder say = new StringBuilder(KoreanTime.clock(first.slot())).append("에 ")
                .append(first.toName()).append(" 가시죠? ");
        AssistantReply.Pick pick = null;
        List<String> because = List.of();
        if (located) {
            try {
                TripRecommender.Plan plan = trips.recommend(new TripRecommender.Ask(guest, lat, lon,
                        new Conversation.Place(first.toName(), null, first.lat(), first.lon()), mode, "auto", w,
                        profile, null));
                if (plan.scored() != null) {
                    Trip b = plan.scored().best();
                    pick = new AssistantReply.Pick(b.type(), b.request(), b.summary(), b.minutes(), b.route());
                    because = plan.scored().because();
                    if (!"walk".equals(b.type()) && word != null && !"clear".equals(word)) {
                        say.append("오늘은 ").append(TripScorer.weatherWord(Sentences.ctx(w)))
                                .append(" 대중교통을 추천해요. ");
                    }
                    say.append(b.summary()).append("이에요.");
                    if (hint != null) {
                        say.append(' ').append(hint).append('.');
                    }
                } else if (plan.failText() != null) {
                    say.append(plan.failText());
                }
            } catch (RuntimeException e) {
                log.warn("오늘 제안 길 추천 실패: {}", e.getClass().getSimpleName());
                say.append("길은 출발할 때 찾아 드릴게요.");
            }
        } else {
            say.append("위치를 켜 주시면 오늘 날씨에 맞는 길을 찾아 드릴게요.");
        }
        return new TodaySuggestion(say.toString().strip(), habit, List.copyOf(others), weather, hint, pick, because);
    }

    static TodaySuggestion.Habit habit(MemoryRepository.Habit h) {
        return new TodaySuggestion.Habit(h.toName(), h.lat(), h.lon(), h.slot().toString(), h.times(),
                h.byTransit() > h.byWalk() ? "transit" : "walk");
    }

    /** rain, heat, cold, dust, clear. 날씨도 미세먼지도 모르면 null */
    static String word(WeatherDust w) {
        if (!w.weatherAvailable() && !w.dustAvailable()) {
            return null;
        }
        if (w.rain()) {
            return "rain";
        }
        if (w.heat()) {
            return "heat";
        }
        if (w.cold()) {
            return "cold";
        }
        if (w.dustBad()) {
            return "dust";
        }
        return "clear";
    }

    /** 이런 날씨에 내가 보통 고른 것 */
    static String hint(String word, MemoryRepository.WeatherChoice c) {
        if (c == null || c.trips() < WEATHER_MIN_TRIPS || c.byTransit() == c.byWalk()) {
            return null;
        }
        String when = switch (word) {
            case "rain" -> "비 오는 날엔";
            case "heat" -> "더운 날엔";
            case "cold" -> "추운 날엔";
            default -> "미세먼지 나쁜 날엔";
        };
        return when + (c.byTransit() > c.byWalk() ? " 보통 대중교통을 타셨어요" : " 보통 걸어가셨어요");
    }

    private static String weatherSentence(WeatherDust w) {
        return "오늘은 " + TripScorer.weatherWord(Sentences.ctx(w)) + " 덜 걷는 길이 좋아요.";
    }
}
