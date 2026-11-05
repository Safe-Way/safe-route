package com.saferoute.place;

/** 카카오 검색을 못 했을 때 (키 없음, 시간 초과, 카카오 오류 등) */
public class PlaceSearchException extends RuntimeException {

    public PlaceSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
