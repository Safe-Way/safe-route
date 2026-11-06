package com.saferoute.assistant;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 길찾기 비서 창구 (C 파트). POST /api/assistant
 * 앱이 {guestId, text, lat, lon, now, conversationId}를 보내면 {say, pick, others, because, ask, ...}로 답한다.
 * 길을 못 찾아도 200 으로 답하고 say 로 알린다 (대화 화면이 끊기지 않게, pick 은 null).
 */
@RestController
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @PostMapping("/api/assistant")
    public AssistantReply answer(@RequestBody(required = false) AssistantRequest request) {
        return service.answer(request);
    }
}
