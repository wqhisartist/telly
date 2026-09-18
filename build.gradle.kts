plugins {
    id("fabric-loom") version "1.7.+"
    `maven-publish`
}

base {
    archivesName.set(project.property("mavenArtifact").toString())
    version = project.property("mavenVersion").toString()
}

repositories {
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/google")
    maven("https://maven.aliyun.com/repository/gradle-plugin")
    maven("https://maven.meteorclient.com/releases")
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("meteorclient:meteor-client:${project.property("meteor_version")}")
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filesMatching("fabric.mod.json") {
        expand("version" to project.version,
                "minecraft_version" to project.property("minecraft_version"),
                "loader_version" to project.property("loader_version"))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}