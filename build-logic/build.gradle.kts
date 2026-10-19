plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// convention plugin 안에서 id("org.springframework.boot") 등을 쓰려면 플러그인 아티팩트가 classpath에 있어야 한다
dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${libs.versions.spring.boot.get()}")
    implementation("io.spring.gradle:dependency-management-plugin:${libs.versions.dependency.management.get()}")
}
