// Folia port: dedicated backend module (architecture v3 §2).
// Java 25 toolchain (forced by folia-api's JVM 25+ class metadata). Its output is bundled only
// into the Mojang/Paper artifact and loaded only after reflective Folia detection. worldedit-core
// stays Java-21 output with zero Folia dependency; folia-api is declared only here.

plugins {
    `java-library`
    id("buildlogic.common")
}

project.description = "Bukkit-Folia"

repositories {
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "EngineHub Repository"
        url = uri("https://maven.enginehub.org/repo/")
    }
    mavenCentral()
}

dependencies {
    compileOnly(project(":worldedit-core"))
    compileOnly(libs.foliaApi)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    // De-synced from the Java-21 core: this module compiles at Java 25 (§2).
    disableAutoTargetJvm()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
