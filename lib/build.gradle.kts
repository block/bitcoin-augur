import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
    id("org.jetbrains.dokka")
    id("com.diffplug.spotless")
}

group = "xyz.block"
version = rootProject.version

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // Only use explicit API mode for main source set, not for tests
        if (name.contains("main", ignoreCase = true)) {
            freeCompilerArgs.add("-Xexplicit-api=strict")
        }
        // Enable K2 (Kotlin 2.1) compiler
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    // This dependency is exported to consumers, that is to say found on their compile classpath.
    api(libs.commons.math3)

    // This dependency is used internally, and not exposed to consumers on their own compile classpath.
    implementation(libs.guava)
    implementation(libs.viktor)

    // Testing dependencies
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.mockk)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Configure Dokka for documentation using V2 API
dokka {
    dokkaSourceSets.main {
        moduleName.set("augur")
        includes.from("Module.md")
        jdkVersion.set(11)
        
        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/block/bitcoin-augur/blob/main/lib/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        // Apply to .kt files
        target("**/*.kt")
        // License header
        licenseHeader("""
            /*
             * Copyright (c) 2025 Block, Inc.
             *
             * Licensed under the Apache License, Version 2.0 (the "License");
             * you may not use this file except in compliance with the License.
             * You may obtain a copy of the License at
             *
             *      http://www.apache.org/licenses/LICENSE-2.0
             *
             * Unless required by applicable law or agreed to in writing, software
             * distributed under the License is distributed on an "AS IS" BASIS,
             * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
             * See the License for the specific language governing permissions and
             * limitations under the License.
             */

        """.trimIndent() + "\n")
    }
}

// Add @OptIn annotation to all test files to allow internal API usage
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("test", ignoreCase = true)) {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=xyz.block.augur.internal.InternalAugurApi")
        }
    }
}
