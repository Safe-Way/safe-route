package com.saferoute.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 어느 주소에서 오류가 나도 스프링 기본 화면(Whitelabel Error Page) 대신
 * {code, message} 모양으로 답한다. 각 주소가 직접 만든 오류 응답은 그대로 나간다.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** 없는 주소 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "없는 주소예요");
    }

    /** 꼭 필요한 값이 빠짐 (예: query 없이 /api/places) */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> missingParam(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_PARAM", e.getParameterName() + " 값이 필요해요");
    }

    /** 숫자 자리에 글자가 옴 (예: lat=abc) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> wrongType(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_PARAM", e.getName() + " 값의 형식이 맞지 않아요");
    }

    /** POST 본문 JSON 이 깨졌거나 비어 있음 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "BAD_PARAM", "요청 내용을 읽을 수 없어요");
    }

    /** GET 주소에 POST 를 보내는 등 방식이 틀림 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> wrongMethod(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "이 주소는 " + e.getMethod() + " 요청을 받지 않아요");
    }

    /** 그 밖에 예상하지 못한 오류. 자세한 내용은 서버 로그에만 남기고 앱에는 짧게 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("처리하지 못한 오류", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "SERVER_ERROR", "잠시 후 다시 시도해 주세요");
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}
