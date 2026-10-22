plugins { id("cinema.library") }

dependencies {
    api(project(":libs:common-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.resilience4j.spring.boot)
    implementation(libs.logstash.encoder)
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("jakarta.validation:jakarta.validation-api")   // ConstraintViolation 핸들러 (있을 때만 등록)
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
