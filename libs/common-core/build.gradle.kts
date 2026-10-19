plugins { id("cinema.library") }

// 외부 의존성 0 — gateway(WebFlux)·MVC 서비스 모두 쓴다
dependencies {
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")
}
