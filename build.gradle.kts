import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension

plugins {
    base
    alias(libs.plugins.kotlinGradlePlugin) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dependencyAnalysis)
    alias(libs.plugins.binaryCompatibilityValidator)
    alias(libs.plugins.spotless) apply false
    id("com.vanniktech.maven.publish.base") version libs.versions.mavenPublishGradlePlugin.get() apply false
}

buildscript {
    repositories {
        mavenCentral()
    }
}

// Configure binary compatibility validator
// This ensures that changes to the public API are detected and validated
// It helps maintain backwards compatibility by checking for unintended API changes
apiValidation {
    // Exclude internal APIs marked with @InternalAugurApi from public API checks
    nonPublicMarkers.add("xyz.block.augur.internal.InternalAugurApi")
}

subprojects {
    buildscript {
        repositories {
            mavenCentral()
        }
    }

    apply(plugin = "com.diffplug.spotless")

    // Configure Spotless with default settings for all subprojects
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        // By default, spotless auto-formatting is not executed before the compile step
        // By default, spotless linting will not happen during the check step

        kotlin {
            // Apply to .kt files
            target("**/*.kt")

            trimTrailingWhitespace()
            leadingTabsToSpaces()
            endWithNewline()

            // Use ktlint with .editorconfig settings
            ktlint("0.48.2")
                .editorConfigOverride(mapOf(
                    "max_line_length" to "off"
                ))
        }
    }

    // Use afterEvaluate to ensure the plugins are applied before configuration
    afterEvaluate {
        // Only apply these plugins to subprojects that apply the java plugin
        plugins.withId("java") {
            apply(plugin = "jacoco")

            // Configure JaCoCo for consistent code coverage reporting
            extensions.configure<JacocoPluginExtension> {
                toolVersion = "0.8.11"
            }
        }

        // Configure Maven publishing if the project applies the maven-publish plugin
        plugins.withId("com.vanniktech.maven.publish.base") {
            val publishingExtension = extensions.findByType(PublishingExtension::class.java)
            if (publishingExtension != null) {
                extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
                    // Configure POM from properties
                    pomFromGradleProperties()

                    // Configure Maven Central publishing
                    publishToMavenCentral()

                    // Sign all publications when publishing
                    signAllPublications()
                }

                publishingExtension.publications.create<MavenPublication>("maven") {
                    from(components["java"])
                }
            }
        }
    }
}

// Add a task to generate documentation site
tasks.register("generateDocs") {
    group = "documentation"
    description = "Generate complete documentation site"

    dependsOn(":dokkaGenerate")
}

// Add a task to release a new version
tasks.register("prepareRelease") {
    group = "release"
    description = "Prepare for a new release by updating versions and validating"

    dependsOn(":apiCheck", ":test", ":generateDocs")
}
