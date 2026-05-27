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

// ── Localiza o servidor automaticamente (Linux ou Windows) ───────────────────
val serverDir: File = run {
    listOf("../CreateOnMC", "../CreateOnMinecraftServer")
        .map { file("${rootProject.projectDir}/$it") }
        .firstOrNull { it.exists() }
        ?: file("${rootProject.projectDir}/../CreateOnMinecraftServer") // fallback
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

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
        group = "minecraft"
        description = "Compila o plugin, copia para o servidor e o reinicia."
        dependsOn(shadowJar)
        doLast {
            println("📦 JAR copiado para: ${serverDir.absolutePath}/plugins")

            if (isWindows) {
                // ── Windows ──────────────────────────────────────────────
                exec {
                    commandLine("cmd", "/c", "taskkill /F /FI \"WINDOWTITLE eq CreateOnMinecraftServer*\"")
                    isIgnoreExitValue = true
                }
                Thread.sleep(2000)
                ProcessBuilder("cmd", "/c", "start start.bat")
                    .directory(serverDir)
                    .start()

            } else {
                // ── Linux ────────────────────────────────────────────────
                val pidFile = File(serverDir, "server.pid")

                if (pidFile.exists()) {
                    val pid = pidFile.readText().trim()
                    println("🛑 Parando servidor (PID $pid)...")
                    exec {
                        commandLine("bash", "-c", "kill $pid 2>/dev/null || true")
                        isIgnoreExitValue = true
                    }
                    pidFile.delete()
                    Thread.sleep(3000) // aguarda o processo encerrar
                } else {
                    println("ℹ️  server.pid não encontrado — subindo servidor pela primeira vez.")
                }

                // Inicia em background; start.sh grava o PID em server.pid
                ProcessBuilder("bash", "start.sh")
                    .directory(serverDir)
                    .start()
            }

            println("✅ Servidor reiniciando...")
        }
    }
}
