package com.saferoute.air;

/** 키가 없거나, 에어코리아가 실패했거나, 오류 코드를 돌려줬을 때 */
public class AirUnavailableException extends RuntimeException {

    public AirUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
