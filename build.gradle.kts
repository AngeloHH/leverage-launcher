import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    java
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.leverage"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val authlibInjectorVersion = "1.2.8"
val authlibInjectorSha256 =
    "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10"
val authlibInjectorUrl =
    "https://authlib-injector.yushi.moe/artifact/56/" +
    "authlib-injector-$authlibInjectorVersion.jar"
val embeddedAgentsDirectory =
    layout.buildDirectory.dir("generated-resources/embedded-agents")

val prepareEmbeddedAuthlibInjector by tasks.registering {
    val output = embeddedAgentsDirectory.map {
        it.file("agents/authlib-injector-$authlibInjectorVersion.jar")
    }
    outputs.file(output)

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        val destination = output.get().asFile
        if (destination.isFile &&
            sha256(destination) == authlibInjectorSha256
        ) return@doLast

        val cached = layout.projectDirectory.file(
            "data/tools/authlib-injector-$authlibInjectorVersion.jar"
        ).asFile
        if (!cached.isFile || sha256(cached) != authlibInjectorSha256) {
            cached.parentFile.mkdirs()
            val temporary = File(cached.parentFile, cached.name + ".part")
            try {
                URI.create(authlibInjectorUrl).toURL().openStream().use { input ->
                    temporary.outputStream().buffered().use { outputStream ->
                        input.copyTo(outputStream)
                    }
                }
                check(sha256(temporary) == authlibInjectorSha256) {
                    "El SHA-256 descargado de authlib-injector no coincide"
                }
                Files.move(
                    temporary.toPath(), cached.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                temporary.delete()
            }
        }

        destination.parentFile.mkdirs()
        cached.copyTo(destination, overwrite = true)
        check(sha256(destination) == authlibInjectorSha256) {
            "No se pudo verificar authlib-injector antes de empaquetarlo"
        }
    }
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.1")
    implementation("com.formdev:flatlaf-extras:3.7.1")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.eclipse.angus:jakarta.mail:2.0.4")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets {
    main {
        resources.srcDir(embeddedAgentsDirectory)
    }
}

tasks.processResources {
    dependsOn(prepareEmbeddedAuthlibInjector)
}

application {
    mainClass = "dev.leverage.Leverage"
}

val checkSourceWidth by tasks.registering {
    val sources = fileTree("src") { include("**/*.java") } +
        files("build.gradle.kts", "settings.gradle.kts")
    inputs.files(sources)
    doLast {
        val wide = sources.files.sortedBy { it.path }.flatMap { file ->
            file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                if (line.length <= 90) null else "${relativePath(file)}:${index + 1}"
            }
        }
        check(wide.isEmpty()) {
            "Source lines exceed 90 columns:\n" + wide.joinToString("\n")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    dependsOn(checkSourceWidth)
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName = "leverage"
    archiveVersion = ""
    archiveClassifier = ""
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
        attributes["Multi-Release"] = "true"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
