FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy file jar từ thư mục target vào container
COPY target/*.jar app.jar

# Expose port mà service sử dụng (ví dụ: 8081)
EXPOSE 4000

ENTRYPOINT ["java", "-jar", "app.jar"]