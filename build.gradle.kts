plugins {
    // 2.4.10+ needed to read Nexo 1.26.0's Kotlin 2.4.0 metadata — see repo Kotlin bump.
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.createonmc"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nexomc.com/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.64-stable")
    compileOnly("com.nexomc:nexo:1.26.0")
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
    listOf("../../ServerDev", "../CreateOnMC", "../CreateOnMinecraftServer")
        .map { file("${rootProject.projectDir}/$it") }
        .firstOrNull { it.exists() }
        ?: file("${rootProject.projectDir}/../../ServerDev") // fallback
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
        description = "Compila o plugin, copia para o servidor e o reinicia (via screen no Linux)."
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
                // ── Linux: gerencia o servidor dentro de uma screen "server" ──
                val screenName = "server"

                fun screenExists(): Boolean {
                    val proc = ProcessBuilder("screen", "-list").redirectErrorStream(true).start()
                    val output = proc.inputStream.bufferedReader().readText()
                    proc.waitFor()
                    return Regex("""\.${Regex.escape(screenName)}\s""").containsMatchIn(output)
                }

                if (screenExists()) {
                    println("🛑 Servidor rodando na screen '$screenName' — enviando 'stop' pelo console...")
                    ProcessBuilder("screen", "-S", screenName, "-X", "stuff", "stop\n").start().waitFor()

                    var waited = 0
                    while (screenExists() && waited < 60) {
                        Thread.sleep(1000)
                        waited++
                    }
                    if (screenExists()) {
                        println("⚠️  Servidor não encerrou a tempo (60s) — a screen '$screenName' ainda existe.")
                    }
                }

                println("🚀 Iniciando servidor na screen '$screenName'...")
                ProcessBuilder("screen", "-dmS", screenName, "bash", "start.sh")
                    .directory(serverDir)
                    .start()
                    .waitFor()
            }

            println("✅ Deploy concluído.")
        }
    }
}
