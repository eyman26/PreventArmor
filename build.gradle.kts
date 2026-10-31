plugins {
    alias(libs.plugins.loom) apply false
    alias(libs.plugins.loom.remap) apply false
}

val collectJars by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies every platform jar into build/dist."
    from(project(":paper").tasks.named("jar"))
    from(project(":fabric-1.21").tasks.named("remapJar"))
    from(project(":fabric-26").tasks.named("jar"))
    into(layout.buildDirectory.dir("dist"))
}

tasks.register("buildAll") {
    group = "build"
    dependsOn(subprojects.map { it.tasks.named("build") }, collectJars)
}
