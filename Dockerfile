FROM eclipse-temurin:21-jre-alpine
WORKDIR /opt/properbackup
COPY build/install/properbackup-buffer/ ./
RUN mkdir -p /opt/properbackup/storage /opt/properbackup/inbox
EXPOSE 7100
ENTRYPOINT ["bin/properbackup-buffer"]
CMD ["--db-url", "jdbc:postgresql://db:5432/properbackup", \
     "--db-user", "properbackup", \
     "--storage", "/opt/properbackup/storage", \
     "--inbox", "/opt/properbackup/inbox"]
