package com.saferoute.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AI 없이 낱말로 알아듣기 */
class RuleReaderTests {

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "할머니랑 양동시장 가고 싶어, senior, 양동시장",
            "휠체어로 광주역 갈래, wheelchair, 광주역",
            "아이랑 조선대학교까지 가는 길 알려줘, child, 조선대학교",
            "나 혼자 충장로 가려고, adult, 충장로",
            "조선대학교병원으로 데려다 줘, -, 조선대학교병원",
            "어르신이랑 같이 걸어요, senior, -",
            "학교로 가자, -, 학교",
            "금남로 가는 길, -, 금남로",
            "광주역으로 갈게, -, 광주역",
    })
    void readsModeAndDestination(String text, String mode, String dest) {
        assertThat(RuleReader.mode(text)).isEqualTo(mode);
        assertThat(RuleReader.destination(text, false)).isEqualTo(dest);
    }

    @ParameterizedTest
    @CsvSource({"양동시장, 양동시장", "광주송정역이요, 광주송정역이요"})
    void wholeTextIsPlaceWhenAskedWhere(String text, String dest) {
        assertThat(RuleReader.destination(text, true)).isEqualTo(dest);
    }
}
