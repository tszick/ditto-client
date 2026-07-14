plugins {
    `java-library`
    jacoco
}

group = "io.ditto"
version = "1.1.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Used internally for HTTP JSON parsing; not exposed in the public API.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")
    testImplementation(platform("org.junit:junit-bom:6.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaCompile>("compileTestJava") {
    // Some Windows sandboxed JDKs fail to resolve sibling main .class output from the test classpath.
    source(sourceSets.main.get().allJava)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

// Produce a plain JAR without a version suffix so consumers can reference it
// with a stable file path: build/libs/ditto-java-client.jar
tasks.jar {
    archiveVersion.set("")
    manifest {
        attributes(
            "Implementation-Title" to "ditto-java-client",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
