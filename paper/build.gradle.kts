plugins {
    `java-library`
    alias(libs.plugins.run.paper)
}

base.archivesName = "PreventArmor-paper"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Compiled against the oldest supported API; checkLatestApi re-compiles the same sources
// against the newest Paper so removed/renamed API shows up at build time, not on a live server.
val latestApi: Configuration by configurations.creating

dependencies {
    compileOnly(libs.paper.api)
    latestApi(libs.paper.api.latest)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// Paper 26.x API jars are Java 25 class files, which javac 21 refuses to read.
val jdk25 = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    val checkLatestApi = register<JavaCompile>("checkLatestApi") {
        group = "verification"
        source = sourceSets.main.get().java
        classpath = latestApi
        destinationDirectory = layout.buildDirectory.dir("latest-api-check")
        javaCompiler = jdk25
        options.release = 21
    }

    check { dependsOn(checkLatestApi) }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }
}

runPaper.folia.registerTask()
