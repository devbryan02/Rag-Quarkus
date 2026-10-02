import java.net.URI

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
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-openai")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-pgvector")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-hibernate-validator:3.37.3")
    implementation("io.quarkus:quarkus-smallrye-fault-tolerance:3.37.3")
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")

    implementation("dev.langchain4j:langchain4j-embeddings:1.17.0-beta27")
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

// Modelo de embeddings multilingüe (ONNX, 384 dims). No se versiona por su tamaño (~118 MB):
// se descarga una sola vez a models/ y lo usan quarkusDev, los tests y la imagen Docker.
// Revisiones fijadas por hash para que el build sea reproducible.
val embeddingModelDir = layout.projectDirectory.dir("models/paraphrase-multilingual-MiniLM-L12-v2")
val embeddingModelFiles = mapOf(
    "model.onnx" to "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/2c4055b12046f11709e9df2c122e59ffbdc2f900/onnx/model_quantized.onnx",
    "tokenizer.json" to "https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/e8f8c211226b894fcb81acc59f3b34ba3efd5f42/tokenizer.json",
)

val downloadEmbeddingModel by tasks.registering {
    group = "build setup"
    description = "Descarga el modelo de embeddings multilingüe a models/ si no existe."
    outputs.dir(embeddingModelDir)
    doLast {
        embeddingModelFiles.forEach { (name, url) ->
            val target = embeddingModelDir.file(name).asFile
            if (target.exists()) return@forEach
            target.parentFile.mkdirs()
            logger.lifecycle("Descargando $name desde $url")
            // Se descarga a un .part y se renombra al final para no dejar un archivo
            // truncado si la descarga se corta.
            val partial = File(target.path + ".part")
            URI(url).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            check(partial.renameTo(target)) { "No se pudo mover $partial a $target" }
        }
    }
}

tasks.named("quarkusDev") { dependsOn(downloadEmbeddingModel) }
tasks.named("quarkusBuild") { dependsOn(downloadEmbeddingModel) }
tasks.named("test") { dependsOn(downloadEmbeddingModel) }
