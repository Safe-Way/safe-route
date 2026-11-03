package com.saferoute.weather;

/** 키가 없거나, 기상청이 실패했거나, 오류 코드를 돌려줬을 때 */
public class WeatherUnavailableException extends RuntimeException {

    public WeatherUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
