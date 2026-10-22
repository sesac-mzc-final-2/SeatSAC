plugins { id("cinema.library") }

dependencies {
    api(project(":libs:common-core"))
    api(platform(libs.testcontainers.bom))
    api(libs.testcontainers.junit)
    api(libs.testcontainers.mysql)
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-testcontainers")
    compileOnly("jakarta.servlet:jakarta.servlet-api")   // MockMvc 헬퍼(WithCurrentUser)용. 서비스는 starter-web으로 이미 갖고 있다
}
