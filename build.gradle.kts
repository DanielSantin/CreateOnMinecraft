plugins {
    // 2.4.10+ needed to read Nexo 1.26.0's Kotlin 2.4.0 metadata — see repo Kotlin bump.
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "dev.createonmc"
version = "1.0.0"

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

// ── Produção: servidor SSG-Paper (roda como serviço systemd `ssg-paper`) ─────
val serverDir: File = file("/home/ubuntu/SSG-Paper-26.2")

tasks {
    shadowJar {
        archiveBaseName.set("SSGCreateOnMinecraft")
        archiveClassifier.set("")
        relocate("kotlin", "dev.createonmc.kotlin")
    }
    build {
        dependsOn(shadowJar)
    }

    // Deploy manual para o servidor de produção. NÃO roda automaticamente com
    // build/shadowJar — invoque explicitamente: ./gradlew deployProd
    register<Copy>("deployProd") {
        group = "deployment"
        description = "Copia o jar para a pasta plugins/ do servidor de produção"
        dependsOn(shadowJar)
        from(shadowJar.get().archiveFile)
        into("$serverDir/plugins")
        doLast {
            println("📦 JAR copiado para: $serverDir/plugins/${shadowJar.get().archiveFileName.get()}")
            println("ℹ️  O servidor NÃO foi reiniciado — rode 'systemctl restart ssg-paper' manualmente quando quiser aplicar.")
        }
    }
}
