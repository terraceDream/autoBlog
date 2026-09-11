FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && npm install -g @openai/codex@0.153.4 \
    && useradd --uid 10001 --create-home autoblog
WORKDIR /app
COPY app.jar /app/app.jar
USER autoblog
ENV CODEX_HOME=/state/codex
EXPOSE 8080
ENTRYPOINT ["java","-Xms128m","-Xmx512m","-jar","/app/app.jar"]
