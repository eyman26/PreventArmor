plugins {
    alias(libs.plugins.loom)
}

// 26.x ships unobfuscated, so there is no mappings line and no remapJar step.
val minecraftVersion = libs.versions.minecraft.v26.get()

base.archivesName = "PreventArmor-fabric-$minecraftVersion"

sourceSets.main {
    java.srcDir(rootProject.file("fabric-shared/src/main/java"))
    resources.srcDir(rootProject.file("fabric-shared/src/main/resources"))
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api.v26)
    implementation(libs.fabric.permissions.v26)
    include(libs.fabric.permissions.v26)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
        options.release = 25
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "minecraft" to "~$minecraftVersion",
            "java" to "25",
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }
}
