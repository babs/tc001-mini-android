import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keysDir = rootProject.file("keys")
val keystore = keysDir.resolve("keystore.properties").takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "tc001.viewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "tc001.viewer"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keysDir.resolve(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // No keys/ checkout (CI, fresh clone): build stays unsigned instead of failing.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// 001-AC10: the release APK may ship only the Android framework and the Kotlin stdlib.
val verifyRuntimeDeps by tasks.registering {
    val allowed = setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")
    // Artifacts, not module components: files() jars and project dependencies must fail too.
    val artifacts = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
    doLast {
        val extra = artifacts.get().map { it.id.componentIdentifier }
            .filterNot { it is ModuleComponentIdentifier && "${it.group}:${it.module}" in allowed }
            .map { it.displayName }
        check(extra.isEmpty()) { "001-AC10: unexpected release runtime dependencies: $extra" }
    }
}

tasks.named("check") { dependsOn(verifyRuntimeDeps) }

// 001-AC10: the release APK must stay under 5 MB.
val verifyApkSize by tasks.registering {
    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    val limit = 5_000_000L
    doLast {
        val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "001-AC10: no release APK in ${apkDir.get()}" }
        for (apk in apks) check(apk.length() < limit) { "001-AC10: ${apk.name} is ${apk.length()} bytes, limit $limit" }
    }
}

// assembleRelease is registered by AGP after this script runs, hence the lazy match.
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(verifyApkSize) }
