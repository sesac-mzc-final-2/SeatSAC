// Spring Boot 앱(서비스) 공통: actuator · prometheus · bootJar 이름 고정 · 테스트
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("cinema.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// ---- 보안 패치: Boot BOM이 관리하는 버전을 gradle/libs.versions.toml 값으로 덮어쓴다 (3.1.4 Trivy) ----
//   netty.version·tomcat.version은 Spring Boot BOM 속성 이름. bouncycastle은 BOM 밖이라 직접 고정
val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun catalogVersion(name: String): String = libsCatalog.findVersion(name).get().requiredVersion

extra["netty.version"] = catalogVersion("netty")
extra["tomcat.version"] = catalogVersion("tomcat")

dependencyManagement {
    dependencies {
        val bc = catalogVersion("bouncycastle")
        dependency("org.bouncycastle:bcprov-jdk18on:$bc")
        dependency("org.bouncycastle:bcpkix-jdk18on:$bc")
        dependency("org.bouncycastle:bcutil-jdk18on:$bc")
    }
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
