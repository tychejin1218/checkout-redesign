plugins {
    // Spring Boot 4.1.2 BOM이 관리하는 Kotlin 버전(kotlin.version)과 맞춘다
    val kotlinVersion = "2.3.21"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    kotlin("plugin.jpa") version kotlinVersion
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("dev.detekt") version "2.0.0-alpha.6"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Spring의 null 안전성 어노테이션(JSpecify)을 Kotlin 타입에 엄격하게 반영
        // 생성자 파라미터의 어노테이션(@field:NotBlank 없이 @NotBlank)을 프로퍼티 필드에도 적용
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        // MyBatis 매퍼 XML 이 @Param 없이 파라미터 이름(#{goodsCd})을 쓸 수 있게 한다
        javaParameters = true
    }
}

// JPA 엔티티는 지연 로딩 프록시를 위해 open 이어야 한다 (plugin.spring 은 @Entity 를 열지 않음)
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // 저장소: SQLite 파일 2개 (신규 DB, 레거시 DB)
    runtimeOnly("org.xerial:sqlite-jdbc")

    // 신규 DB: Spring Data JPA. SQLite 방언은 Hibernate 본체가 아니라 community-dialects 에 있다
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.hibernate.orm:hibernate-community-dialects")

    // 레거시 DB: MyBatis (Spring Boot 4.1 용 starter)
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.1.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("com.ninja-squad:springmockk:5.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.jar {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    // main() 의 TimeZone.setDefault(UTC) 와 같은 조건으로 테스트한다
    systemProperty("user.timezone", "UTC")
}

// 정적 분석: ktlint(포맷) + detekt(코드 스멜). 설정은 .editorconfig, config/detekt/detekt.yml
ktlint {
    version = "1.8.0"
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
}

// detekt 는 자신이 빌드된 Kotlin 버전으로만 동작한다. Spring BOM 이 내리는 Kotlin 버전을 detekt 실행 classpath 에서만 되돌린다
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(
                dev.detekt.gradle.plugin
                    .getSupportedKotlinVersion(),
            )
        }
    }
}
