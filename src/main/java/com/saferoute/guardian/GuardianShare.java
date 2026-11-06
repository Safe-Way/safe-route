package com.saferoute.guardian;

import java.time.LocalDateTime;

/** guardian_share 표 한 줄 (029). 위치는 마지막 한 점만 */
record GuardianShare(String token, String guestId, String walkerName, String destName, Double destLat,
                     Double destLon, String mode, LocalDateTime startedAt, LocalDateTime expiresAt,
                     Double lastLat, Double lastLon, LocalDateTime lastAt, Integer remainM, Integer etaMin,
                     boolean offRoute, LocalDateTime endedAt, String endReason) {

    boolean ended() {
        return endedAt != null;
    }
}
