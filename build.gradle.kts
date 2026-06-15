plugins {
    id("java")
    id("application")
}

group = "com.codewarp"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Jackson for JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.codewarp.app.CodeWarp")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    jvmArgs = listOf("--enable-preview")
}

tasks.named<Test>("test") {
    jvmArgs = listOf("--enable-preview")
}