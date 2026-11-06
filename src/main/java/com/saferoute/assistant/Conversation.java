package com.saferoute.assistant;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 한 대화에서 지금까지 정해진 것. 서버 두 대가 같이 돌아서 서버 메모리에 두지 않고,
 * conversationId 글자 안에 담아 앱에 돌려준다 (앱은 다음 요청에 그대로 보냄). DB에도 저장하지 않는다.
 *
 * @param turns       최근 대화 (AI가 앞말을 알게, 최대 MAX_TURNS 개, 글만)
 * @param destination 고른 도착지. 아직 모르면 null
 * @param candidates  "어디로 갈까요?" 하고 보여 준 장소 후보 (choice "0"~"2"가 이 순서)
 * @param mode        이번 대화에서 말한 모드 ("휠체어야"). 저장된 모드보다 먼저 씀. 없으면 null
 * @param asked       방금 되물은 것 (destination, place, location, setup_aid, setup_stairs, temp_until, elevator). 없으면 null
 * @param pending     확인을 기다리는 저장 일 ("맞으세요?" 뒤 "네"를 들으면 함). 없으면 null
 * @param setup       처음 설정에서 지금까지 들은 답 (aid, stairs)
 * @param bodyNote    오늘 몸 상태 한마디 ("무릎이 아파"). 없으면 null
 */
public record Conversation(List<Turn> turns, Place destination, List<Place> candidates, String mode, String asked,
                           Pending pending, Map<String, String> setup, String bodyNote) {

    static final int MAX_TURNS = 6;
    static final int MAX_TEXT = 200;

    /** 대화 한 줄. role 은 user 또는 model */
    public record Turn(String role, String text) {
    }

    /**
     * 장소 하나.
     *
     * @param distanceM 장소 검색이 알려 준 지금 위치에서의 거리(m). 모르면 null (버튼 글에 씀)
     */
    public record Place(String name, String address, double lat, double lon, Integer distanceM) {
        public Place(String name, String address, double lat, double lon) {
            this(name, address, lat, lon, null);
        }
    }

    /**
     * 저장 전에 확인을 받는 일.
     *
     * @param tool     update_my_info, set_map_layers, report_problem
     * @param args     도구에 넘길 값
     * @param question 사용자에게 한 확인 질문
     * @param more     "네"를 들으면 이어서 할 다른 저장 일 (예: 임시 휠체어 + 지도 시설). 없으면 null
     */
    public record Pending(String tool, Map<String, Object> args, String question, Pending more) {
    }

    static Conversation start() {
        return new Conversation(List.of(), null, List.of(), null, null, null, Map.of(), null);
    }

    Conversation withTurn(String role, String text) {
        if (text == null || text.isBlank()) {
            return this;
        }
        List<Turn> t = new ArrayList<>(turns);
        String s = text.strip();
        t.add(new Turn(role, s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s));
        while (t.size() > MAX_TURNS) {
            t.remove(0);
        }
        return new Conversation(List.copyOf(t), destination, candidates, mode, asked, pending, setup, bodyNote);
    }

    Conversation withDestination(Place d) {
        return new Conversation(turns, d, List.of(), mode, asked, pending, setup, bodyNote);
    }

    Conversation withCandidates(List<Place> c) {
        return new Conversation(turns, destination, List.copyOf(c), mode, asked, pending, setup, bodyNote);
    }

    Conversation withMode(String m) {
        return new Conversation(turns, destination, candidates, m, asked, pending, setup, bodyNote);
    }

    Conversation asked(String what) {
        return new Conversation(turns, destination, candidates, mode, what, pending, setup, bodyNote);
    }

    Conversation withPending(Pending p) {
        return new Conversation(turns, destination, candidates, mode, asked, p, setup, bodyNote);
    }

    Conversation withSetup(Map<String, String> s) {
        return new Conversation(turns, destination, candidates, mode, asked, pending, Map.copyOf(s), bodyNote);
    }

    Conversation withBodyNote(String note) {
        return new Conversation(turns, destination, candidates, mode, asked, pending, setup, note);
    }

    /** conversationId 글자 ↔ 대화. 글자가 틀렸으면 새 대화로 본다 */
    static final class Codec {

        private final JsonMapper json;

        Codec(JsonMapper json) {
            this.json = json;
        }

        String encode(Conversation c) {
            byte[] b = json.writeValueAsBytes(c);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        }

        Conversation decode(String id) {
            if (id == null || id.isBlank() || id.length() > 8000) {
                return start();
            }
            try {
                Conversation c = json.readValue(Base64.getUrlDecoder().decode(id.strip()), Conversation.class);
                return new Conversation(c.turns() == null ? List.of() : c.turns(), c.destination(),
                        c.candidates() == null ? List.of() : c.candidates(), c.mode(), c.asked(), c.pending(),
                        c.setup() == null ? Map.of() : c.setup(), c.bodyNote());
            } catch (IllegalArgumentException | JacksonException e) {
                return start();
            }
        }

        static String text(byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
    }
}
