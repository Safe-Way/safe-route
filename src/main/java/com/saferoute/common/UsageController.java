package com.saferoute.common;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/usage: 오늘 외부 API 호출 수와 하루 한도 (2026-11-24, 공개 배포 준비: 사용량 알림).
 * health.yml 이 30분마다 읽어 한도의 warn-ratio(기본 80%)를 넘은 API가 있으면 usage-high 이슈를 연다.
 * 호출 수만 보여 준다(누가 불렀는지는 없음). 한도는 각 서비스의 무료 한도에 맞춰 설정으로 바꾼다.
 */
@RestController
public class UsageController {

    public record Api(String api, int calls, int limit, double ratio) {
    }

    public record Usage(String day, double warnRatio, List<Api> apis, List<String> high) {
    }

    private final ApiUsage usage;
    private final Map<String, Integer> limits;
    private final double warnRatio;

    public UsageController(ApiUsage usage,
                           @Value("${usage.gemini-daily-limit:1000}") int gemini,
                           @Value("${usage.kakao-daily-limit:100000}") int kakao,
                           @Value("${transit.odsay-daily-limit:30}") int odsay,
                           @Value("${usage.warn-ratio:0.8}") double warnRatio) {
        this.usage = usage;
        this.limits = Map.of("gemini", gemini, "kakao", kakao, "odsay", odsay);
        this.warnRatio = warnRatio;
    }

    @GetMapping("/api/usage")
    public Usage today() {
        Map<String, Integer> calls = usage.todayCalls();
        List<Api> apis = List.of("gemini", "kakao", "odsay").stream().map(name -> {
            int n = calls.getOrDefault(name, 0);
            int limit = limits.get(name);
            double ratio = limit <= 0 ? 0 : Math.round(n * 1000.0 / limit) / 1000.0;
            return new Api(name, n, limit, ratio);
        }).toList();
        List<String> high = apis.stream().filter(a -> a.ratio() >= warnRatio)
                .map(a -> a.api() + " " + a.calls() + "/" + a.limit()).toList();
        return new Usage(usage.today().toString(), warnRatio, apis, high);
    }
}
