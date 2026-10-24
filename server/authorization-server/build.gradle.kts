plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}
group = "com.shilapi"
version = "1.0.0"
repositories { google(); mavenCentral() }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("com.android.tools.build:apksig:8.13.1")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    inputs.file("keycloak/faw-play-realm.json")
    inputs.property("databaseBackend", if (System.getenv("TEST_DATABASE_URL") == null) "h2" else "postgres")
    // A database test must actually run against the requested external database and fresh credentials.
    outputs.upToDateWhen { System.getenv("TEST_DATABASE_URL") == null }
}
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
// The same static source can be served from the API origin or a standalone web host.
tasks.processResources {
    from("../site") {
        into("static")
        include("index.html", "assets/**", "downloads.json")
    }
}
