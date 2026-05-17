plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("io.javalin:javalin:6.3.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-gson:0.12.6")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.librepdf:openpdf:2.0.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.testcontainers:testcontainers:1.19.8")
    testImplementation("org.testcontainers:junit-jupiter:1.19.8")
    testImplementation("org.testcontainers:postgresql:1.19.8")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("pl.danielniemiec.properbackup.buffer.BufferMainKt")
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "pl.danielniemiec.properbackup.buffer.BufferMainKt")
    }
}
