plugins { id("cinema.spring-service") }

dependencyManagement {
    imports {
        mavenBom(libs.spring.cloud.bom.get().toString()) 
        mavenBom(libs.resilience4j.bom.get().toString())   // 추가: Resilience4j 모듈 전부 2.3.0으로
    }
}

dependencies {

    implementation(project(":libs:common-core"))
    implementation(libs.logstash.encoder)
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive") // 요청 수 제한(선택)
    testImplementation(project(":libs:common-test"))
}
