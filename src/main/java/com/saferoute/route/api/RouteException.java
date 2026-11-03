package com.saferoute.route.api;

/** 경로를 못 줄 때 던진다. 응답은 { "code": error.name(), "message": getMessage() } */
public class RouteException extends RuntimeException {

    private final RouteError error;

    public RouteException(RouteError error) {
        this(error, null);
    }

    public RouteException(RouteError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    public RouteError error() {
        return error;
    }
}
