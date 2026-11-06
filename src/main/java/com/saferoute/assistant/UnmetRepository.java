package com.saferoute.assistant;

import java.sql.Date;
import java.time.LocalDate;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 비서가 못 한 요청 남기기 (재휘님 030 assistant_unmet, docs/assistant/no_tool_policy.md).
 * 도구가 없음(no_tool), 길찾기와 상관없음(off_topic), 못 알아들음(not_understood)일 때 한 줄.
 * 게스트 번호·위치·시각은 남기지 않는다 (날짜와 모드만). 전화번호·이름처럼 보이는 말은 지우고 넣는다.
 * 넣다가 실패해도(표가 아직 없음 등) 비서 답은 그대로 나간다.
 */
@Repository
public class UnmetRepository {

    private static final Logger log = LoggerFactory.getLogger(UnmetRepository.class);
    private static final Pattern PHONE = Pattern.compile("\\d[\\d\\- ]{6,}\\d");
    private static final Pattern NUMBERS = Pattern.compile("\\d{4,}");
    private static final Pattern NAME = Pattern.compile("[가-힣]{2,4}(님|씨|선생님|에게|한테)");
    static final int MAX_TEXT = 200;

    private final JdbcTemplate jdbc;

    public UnmetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void add(LocalDate on, String mode, String text, String kind, String topic, String offered) {
        try {
            jdbc.update("INSERT INTO assistant_unmet (asked_on, mode, text, kind, topic, offered) VALUES (?, ?, ?, ?, ?, ?)",
                    Date.valueOf(on), mode, clean(text), kind, topic, offered);
        } catch (DataAccessException e) {
            log.warn("못 한 요청 남기기 실패: {}", e.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    /** 전화번호·긴 숫자·이름처럼 보이는 말을 지운다 */
    static String clean(String text) {
        String s = text == null ? "" : text.strip();
        s = PHONE.matcher(s).replaceAll("(번호)");
        s = NUMBERS.matcher(s).replaceAll("(숫자)");
        s = NAME.matcher(s).replaceAll("(이름)$1");
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}
