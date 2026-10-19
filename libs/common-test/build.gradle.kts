plugins { id("cinema.library") }

dependencies {
    api(project(":libs:common-core"))
    api(platform(libs.testcontainers.bom))
    api(libs.testcontainers.junit)
    api(libs.testcontainers.mysql)
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
}
