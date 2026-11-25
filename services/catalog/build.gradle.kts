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
    // 6.3.4a 변경 이벤트 발행 — SNS catalog-events(staging), 로컬은 ElasticMQ 큐 직접(booking·coupon과 같은 버전)
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sns:3.4.0")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs:3.4.0")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation(project(":libs:common-test"))
}
