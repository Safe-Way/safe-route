package com.saferoute.route;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 팀 DB를 쓰는 확인용 테스트의 도우미: 결과 출력, .env 읽기 */
final class TestSupport {

    private TestSupport() {
    }

    /** 프로젝트 폴더의 .env (없으면 빈 값) */
    static Map<String, String> readEnv() throws IOException {
        Map<String, String> env = new HashMap<>();
        Path p = Path.of(".env");
        if (!Files.exists(p)) {
            return env;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            line = line.strip();
            if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                String[] kv = line.split("=", 2);
                env.put(kv[0].strip(), kv[1].strip());
            }
        }
        return env;
    }

    /** 사람이 읽을 결과를 모아 한 번에 출력한다 */
    static final class Out {
        private final StringBuilder sb = new StringBuilder();

        Out(String title) {
            sb.append("\n========== ").append(title).append(" ==========\n");
        }

        void line(String format, Object... args) {
            sb.append(args.length == 0 ? format : format.formatted(args)).append('\n');
        }

        void print() {
            System.out.print(sb);
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
