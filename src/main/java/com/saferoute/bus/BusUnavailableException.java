package com.saferoute.bus;

/** 키가 없거나, 광주버스정보가 실패했거나, 오류 코드를 돌려줬을 때 */
public class BusUnavailableException extends RuntimeException {

    public BusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
