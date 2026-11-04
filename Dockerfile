# 운영 서버 이미지. 먼저 ./mvnw -DskipTests package 로 target/safe-route-*.jar를 만든 뒤 빌드한다.
# 배포는 .github/workflows/deploy.yml이 하고, 서버에서는 deploy/deploy.sh가 이 이미지를 띄운다.
# 바탕 이미지: Java 21 실행 환경만 든 것(JDK보다 작다)
FROM eclipse-temurin:21-jre
# deploy.sh가 옛 이미지를 지울 때 이 표시가 있는 것만 지운다 (같은 서버의 다른 프로젝트 이미지는 건드리지 않는다)
LABEL project=safe-route

# 기본 환경변수
#   TZ, user.timezone   로그와 밤 판단(일몰·일출)을 한국 시간으로
#   MaxRAMPercentage=75 컨테이너 메모리의 75%까지 자바가 쓴다 (도로망 그래프를 메모리에 올린다)
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"

# root가 아닌 계정으로 실행한다. 로그는 화면 출력이라 서버에서 docker logs 로 본다
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app && chown -R app:app /app
WORKDIR /app
# 빌드한 jar를 넣고, 이후 명령은 app 계정으로 실행한다
COPY --chown=app:app target/safe-route-*.jar app.jar
USER app

# 앱이 듣는 포트(컨테이너 안). 밖으로 여는 일은 deploy.sh의 프록시가 한다
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
