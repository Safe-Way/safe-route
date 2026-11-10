package com.saferoute.route.api;

/** 경로를 못 줄 때 던진다. 응답은 { "code": error.name(), "message": getMessage() } */
public class RouteException extends RuntimeException {

    private final RouteError error;

    public RouteException(RouteError error) {
        this(error, (Throwable) null);
    }

    /** 기본 문장 대신 이 문장으로 (들를 곳 구간에서 막힌 곳을 알릴 때, 2026-11-10) */
    public RouteException(RouteError error, String message) {
        super(message);
        this.error = error;
    }

    public RouteException(RouteError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public RouteError error() {
        return error;
    }
}
