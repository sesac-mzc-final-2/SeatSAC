plugins { id("cinema.library") }

dependencies {
    api(project(":libs:common-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.resilience4j.spring.boot)
    implementation(libs.logstash.encoder)
    compileOnly("jakarta.servlet:jakarta.servlet-api")
}
