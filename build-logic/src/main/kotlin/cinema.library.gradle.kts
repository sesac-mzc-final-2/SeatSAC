// libs/ 모듈 공통: 라이브러리(bootJar 없음), Spring Boot BOM으로 버전만 맞춤
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("cinema.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { 
        mavenBom(SpringBootPlugin.BOM_COORDINATES) 
        mavenBom("io.github.resilience4j:resilience4j-bom:2.3.0")
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
