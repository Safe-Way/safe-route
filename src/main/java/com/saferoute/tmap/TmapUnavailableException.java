package com.saferoute.tmap;

/** TMAP 키가 없거나, TMAP 이 실패했거나, 경로를 못 찾았을 때 */
public class TmapUnavailableException extends RuntimeException {

    public TmapUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
