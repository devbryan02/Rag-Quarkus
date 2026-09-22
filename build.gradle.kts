plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:quarkus-langchain4j-bom:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-openai")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-hibernate-validator:3.37.3")
    implementation("io.quarkus:quarkus-smallrye-fault-tolerance:3.37.3")
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")

    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.17.0-beta27")
    implementation("dev.langchain4j:langchain4j-document-parser-apache-poi:1.17.0-beta27")
    implementation("dev.langchain4j:langchain4j-document-parser-apache-pdfbox:1.17.0-beta27")
}

group = "com.bryan"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
