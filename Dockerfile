# Imagem do StandardPay: compila e empacota, sem exigir Java nem Maven na máquina.
#
#   docker build -t standard-pay .
#
# A compilação roda na arquitetura de quem constrói ($BUILDPLATFORM): o jar é o
# mesmo em qualquer processador, e só a imagem final muda entre amd64 e arm64.
# Sem isso, a versão arm64 compilaria sob emulação, várias vezes mais devagar.

# O Maven da imagem é o mesmo do wrapper (.mvn/wrapper/maven-wrapper.properties).
# O ./mvnw não serve aqui: sem unzip na imagem, ele baixa o .tar.gz e o confere
# contra o checksum do .zip, e o build para.
FROM --platform=$BUILDPLATFORM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /src

# Primeiro só o que define as dependências: enquanto o pom.xml não mudar, a
# camada baixada fica em cache e um build depois de mexer no código é rápido.
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

# Os testes não rodam aqui: precisam de Docker (sobem um Postgres de verdade) e
# já rodam no CI antes de a imagem ser publicada.
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# A base que o próprio Quarkus gera, mantida pela Red Hat, com um script de
# partida que ajusta a memória da JVM ao limite do contêiner.
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24

LABEL org.opencontainers.image.title="StandardPay" \
      org.opencontainers.image.description="Simulador de plataforma de pagamentos: checkout hospedado, webhooks assinados, estornos." \
      org.opencontainers.image.source="https://github.com/holandale0/standard-pay" \
      org.opencontainers.image.licenses="MIT"

ENV LANGUAGE='en_US:en'

# Em quatro camadas, da que menos muda para a que mais muda: uma versão nova
# do StandardPay só troca a última, e o pull baixa poucos KB.
COPY --from=build --chown=185 /src/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build --chown=185 /src/target/quarkus-app/*.jar /deployments/
COPY --from=build --chown=185 /src/target/quarkus-app/app/ /deployments/app/
COPY --from=build --chown=185 /src/target/quarkus-app/quarkus/ /deployments/quarkus/

# A porta fica fixa na imagem: o padrão do application.properties pode mudar
# (ou vir de uma alteração local que entrou no build), e o contêiner precisa
# escutar onde o EXPOSE e o -p dizem. Para mudar, -e PORT=… no docker run.
ENV PORT=8080
EXPOSE 8080
USER 185
ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

ENTRYPOINT [ "/opt/jboss/container/java/run/run-java.sh" ]
