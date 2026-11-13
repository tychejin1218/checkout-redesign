plugins {
    // JDK 21 이 설치되어 있지 않으면 Gradle 이 내려받는다 (build.gradle.kts 의 jvmToolchain(21))
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "checkout-redesign"
