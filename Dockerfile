FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app
COPY . .
RUN javac -encoding UTF-8 CineSphereApp.java
CMD ["java", "CineSphereApp"]
