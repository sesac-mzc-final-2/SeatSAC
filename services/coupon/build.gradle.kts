plugins { id("cinema.spring-service") }

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.bom.get().toString())
    }
}

// 6.3.2 coupon (ADR-118) — VIP 선착순 쿠폰 + 체크아웃 saga 참여자(쿠폰 예약·사용·반환)
dependencies {

    implementation(project(":libs:common-core"))
    implementation(project(":libs:common-web"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.springdoc.webmvc) // /v3/api-docs → CI OpenAPI diff
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis") // 재고 coupon:stock · 멱등 coupon:idem (기존 Valkey)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    // saga 명령 소비·결과 발행 (booking·payment와 같은 버전)
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs:3.4.0")
    runtimeOnly("com.mysql:mysql-connector-j")
    // Lombok (버전은 Spring Boot BOM). booking·payment와 같은 설정
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation(project(":libs:common-test"))
}
