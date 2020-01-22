import com.techshroom.inciseblue.commonLib
import net.minecrell.gradle.licenser.LicenseExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    val kt = "1.3.61"
    kotlin("jvm") version kt
    id("com.techshroom.incise-blue") version "0.5.6"
    application
}

application.mainClassName = "net.octyl.mcpirc.McpIrcExportKt"

inciseBlue {
    util {
        javaVersion = JavaVersion.VERSION_13
        enableJUnit5()
    }
    license()
    ide()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf(
            "-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xuse-experimental=kotlinx.coroutines.FlowPreview"
        )
    }
}

dependencies {
    "implementation"(kotlin("stdlib-jdk8"))
    "implementation"("io.github.microutils:kotlin-logging:1.7.8")
    commonLib("ch.qos.logback", "logback", "1.2.3") {
        "implementation"(lib("core"))
        "implementation"(lib("classic"))
    }
    commonLib("org.jetbrains.kotlinx", "kotlinx-coroutines", "1.3.3") {
        "implementation"(lib("core"))
        "implementation"(lib("jdk8"))
    }
    "implementation"("org.jetbrains.kotlinx", "kotlinx-coroutines-io-jvm", "0.1.16")
    "implementation"("org.pircbotx", "pircbotx", "2.1")
    "implementation"("com.github.ajalt", "clikt", "2.3.0")

    commonLib("org.junit.jupiter", "junit-jupiter", "5.6.0") {
        "testImplementation"(lib("api"))
        "testImplementation"(lib("params"))
        "testRuntime"(lib("engine"))
    }
}

configure<LicenseExtension> {
    include("**/*.java")
    include("**/*.kt")
}
