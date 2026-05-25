plugins {
    kotlin("jvm") version "2.2.0"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.createonmc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.64-stable")
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(21)
}

// Paper 26.1.2 publishes its artifact with requiresJvm=25 metadata; override so
// Gradle selects it even though we compile with JDK 21.
configurations.named("compileClasspath") {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

val serverDir = file("${rootProject.projectDir}/../CreateOnMinecraftServer")

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("kotlin", "dev.createonmc.kotlin")
        destinationDirectory.set(file("$serverDir/plugins"))
    }
    build {
        dependsOn(shadowJar)
    }
    register("deploy") {
        dependsOn(shadowJar)
        doLast {
            exec {
                commandLine("cmd", "/c", "taskkill /F /FI \"WINDOWTITLE eq CreateOnMinecraftServer*\"")
                isIgnoreExitValue = true
            }
            Thread.sleep(2000)
            ProcessBuilder("cmd", "/c", "start start.bat")
                .directory(serverDir)
                .start()
            println("Server restarting...")
        }
    }
}
