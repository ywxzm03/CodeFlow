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
    // Anthropic 官方 Java SDK（Messages API raw stream，用于 Claude Code 风格的流式工具调用）
    implementation("com.anthropic:anthropic-java:2.40.1")

    // Reactor Core（push-style 流式事件传递）
    implementation("io.projectreactor:reactor-core:3.8.6")

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
