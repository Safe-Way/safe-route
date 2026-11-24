package com.saferoute.common;

/**
 * 지도 범위 글자 "서쪽경도,남쪽위도,동쪽경도,북쪽위도"를 숫자 네 개로 읽는다.
 * 시설·신고·행사 창구가 각자 같은 코드를 갖고 있던 것을 한곳에 모았다.
 * 순서(서 &lt; 동, 남 &lt; 북)와 크기 검사는 창구마다 다르므로 여기서 하지 않는다.
 */
public final class Bbox {

    private Bbox() {
    }

    /** "126.91,35.14,126.93,35.15" → {서, 남, 동, 북}. 네 숫자가 아니거나 NaN·무한대가 섞이면 null */
    public static double[] parse(String bbox) {
        if (bbox == null) {
            return null;
        }
        String[] p = bbox.split(",");
        if (p.length != 4) {
            return null;
        }
        double[] out = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                out[i] = Double.parseDouble(p[i].trim());
                if (!Double.isFinite(out[i])) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
