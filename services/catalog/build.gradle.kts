plugins { id("cinema.spring-service") }

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.bom.get().toString()) 
        mavenBom(libs.resilience4j.bom.get().toString())   // 추가: Resilience4j 모듈 전부 2.3.0으로
    }
}

dependencies {

    implementation(project(":libs:common-core"))
    implementation(project(":libs:common-web"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.resilience4j.spring.boot)
    implementation(libs.springdoc.webmvc) // /v3/api-docs → CI OpenAPI diff
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation(project(":libs:common-test"))
}
