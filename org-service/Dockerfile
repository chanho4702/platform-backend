# 런타임 전용. jar는 CI(또는 로컬)의 `gradlew :org-service:bootJar` 산출물을 복사한다.
FROM eclipse-temurin:24-jre
WORKDIR /app
COPY org-service/build/libs/app.jar app.jar
EXPOSE 9130 9131
ENTRYPOINT ["java", "-jar", "app.jar"]
