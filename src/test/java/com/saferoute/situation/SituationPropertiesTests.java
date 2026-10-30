package com.saferoute.situation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** application.yml 의 기준값이 제대로 읽히는지 확인 (DB 없이 돈다) */
class SituationPropertiesTests {

    private static SituationProperties load() throws Exception {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(yml))
                .bind("situation", SituationProperties.class)
                .get();
    }

    @Test
    void readsValuesFromApplicationYml() throws Exception {
        SituationProperties p = load();
        assertThat(p.heatCelsius()).isEqualTo(33);
        assertThat(p.coldCelsius()).isEqualTo(-10);
        assertThat(p.pm10Bad()).isEqualTo(81);
        assertThat(p.pm25Bad()).isEqualTo(36);
    }

    @Test
    void boundaryValuesCount() throws Exception {
        SituationProperties p = load();
        assertThat(p.isHeat(33)).isTrue();
        assertThat(p.isHeat(32.9)).isFalse();
        assertThat(p.isCold(-10)).isTrue();
        assertThat(p.isCold(-9.9)).isFalse();
        assertThat(p.isDustBad(81, null)).isTrue();
        assertThat(p.isDustBad(80, 35)).isFalse();
        assertThat(p.isDustBad(null, 36)).isTrue();
        assertThat(p.isDustBad(null, null)).isFalse();
    }
}
