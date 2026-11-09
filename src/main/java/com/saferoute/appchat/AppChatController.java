package com.saferoute.appchat;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.saferoute.common.ErrorResponse;

/**
 * 앱 화면 AI 창구 (C 파트, 11/09). 앱이 제미나이 키를 들고 있지 않게, 앱의 두 AI를 서버가 대신 부른다.
 * <ul>
 *   <li>POST /api/ai/setup-chat: 말로 맞추기 (누가 어떻게 걷는지 듣고 앱 설정 고르기)</li>
 *   <li>POST /api/ai/map-chat: 지도 화면 비서 (설정 바꾸기·가까운 곳·장소·버스·신고·되묻기·답 중 하나 고르기)</li>
 * </ul>
 * 답은 앱이 예전에 제미나이에게서 받던 JSON 모양 그대로다. 503 AI_UNAVAILABLE 이면 앱은 자기 규칙으로 답한다.
 */
@RestController
public class AppChatController {

    private final AppChatService service;

    public AppChatController(AppChatService service) {
        this.service = service;
    }

    @PostMapping("/api/ai/setup-chat")
    public Map<String, Object> setup(@RequestBody(required = false) AppChatRequest request) {
        return service.setup(request);
    }

    @PostMapping("/api/ai/map-chat")
    public Map<String, Object> map(@RequestBody(required = false) AppChatRequest request) {
        return service.map(request);
    }

    @ExceptionHandler(AppChatException.class)
    public ResponseEntity<ErrorResponse> fail(AppChatException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }
}
