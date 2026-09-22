
FROM eclipse-temurin:21-jre-jammy

ENV LANGUAGE='en_US:en'

WORKDIR /deployments

# onnxruntime (used by the local embedding model) is a glibc binary with fortified symbols
# (e.g. __vsnprintf_chk) that Alpine's musl/gcompat shim doesn't provide, so we use a
# glibc-based (Ubuntu) JRE image instead
RUN addgroup --system quarkus && adduser --system --ingroup quarkus quarkus

# Four layers so application changes don't bust the library layer cache
COPY --chown=quarkus:quarkus build/quarkus-app/lib/ /deployments/lib/
COPY --chown=quarkus:quarkus build/quarkus-app/*.jar /deployments/
COPY --chown=quarkus:quarkus build/quarkus-app/app/ /deployments/app/
COPY --chown=quarkus:quarkus build/quarkus-app/quarkus/ /deployments/quarkus/
COPY --chown=quarkus:quarkus src/main/resources/documents/ /deployments/documents/

USER quarkus

EXPOSE 8080

ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
# DocumentIngestor reads from the filesystem, not the classpath — point it at the copied docs folder
ENV RAG_INGESTION_DOCUMENTS_PATH=/deployments/documents

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /deployments/quarkus-run.jar"]
