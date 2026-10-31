plugins {
    alias(libs.plugins.loom.remap)
}

val minecraftVersion = libs.versions.minecraft.v121.get()

base.archivesName = "PreventArmor-fabric-$minecraftVersion"

sourceSets.main {
    java.srcDir(rootProject.file("fabric-shared/src/main/java"))
    resources.srcDir(rootProject.file("fabric-shared/src/main/resources"))
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api.v121)
    modImplementation(libs.fabric.permissions.v121)
    include(libs.fabric.permissions.v121)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
        options.release = 21
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "minecraft" to "~$minecraftVersion",
            "java" to "21",
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
    }
}
