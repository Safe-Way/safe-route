package com.saferoute.route;

/** 다른 패키지 시험(비서 추천 점수)에서 route.yml 설정을 읽을 때 */
public final class RouteTestProps {

    private RouteTestProps() {
    }

    public static RouteProperties load() {
        return TestRoutes.props();
    }
}
