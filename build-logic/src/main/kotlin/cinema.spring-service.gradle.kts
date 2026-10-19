// Spring Boot 앱(서비스) 공통: actuator · prometheus · bootJar 이름 고정 · 테스트
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("cinema.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Dockerfile이 서비스와 무관하게 build/libs/app.jar 를 복사한다
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

// 로컬에서 bootRun 할 때 local 프로필
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active", "local"))
}
