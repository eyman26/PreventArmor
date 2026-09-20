plugins {
    java
}

group = "org.eyman_"
version = "2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(
            "version" to version,
            "name" to rootProject.name
        )
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
