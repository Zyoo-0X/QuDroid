import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val legalAssetsDir = layout.buildDirectory.dir("generated/legalAssets")
val copyLegalNotices = tasks.register<Copy>("copyLegalNotices") {
    from(
        rootProject.file("LICENSE"),
        rootProject.file("NOTICE.md"),
        rootProject.file("docs/build-source.md"),
        rootProject.file("docs/license-audit.md")
    )
    into(legalAssetsDir.map { it.dir("legal") })
}

val signProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val canSign = signProps.getProperty("storeFile")?.let { rootProject.file(it).isFile } == true

val engineSrc = file("src/main/assets/engines")
val engineOut = layout.buildDirectory.dir("generated/engineAssets")
val packEngines = tasks.register("packEngines") {
    inputs.dir(engineSrc)
    outputs.dir(engineOut)
    doLast {
        val o = engineOut.get().asFile
        o.deleteRecursively()
        File(o, "blobs").mkdirs()
        val idx = StringBuilder()
        engineSrc.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { f ->
                val sha = MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                val b = File(o, "blobs/$sha")
                if (!b.exists()) f.copyTo(b)
                val rel = f.relativeTo(engineSrc).path.replace('\\', '/')
                idx.append("engines/$rel\t$sha\t${f.length()}\n")
            }
        File(o, "engines.idx").writeText(idx.toString())
    }
}
tasks.named("preBuild") { dependsOn(packEngines) }

android {
    namespace = "app.aemu"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.qudroid.zyoo0x"
        minSdk = 26
        targetSdk = 28
        versionCode = 315
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (canSign) create("release") {
            storeFile = rootProject.file(signProps.getProperty("storeFile"))
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias")
            keyPassword = signProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ""
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets["main"].assets.setSrcDirs(listOf(engineOut, legalAssetsDir, file("src/main/skinAssets")))

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        noCompress += listOf("payload", "tar", "gz", "so", "img")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
            optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.11.00")
    val shizukuVersion = "13.1.5"

    implementation(bom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-alpha16")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.junrar:junrar:8.1.1")
    implementation("org.brotli:dec:0.1.2")
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    implementation("com.github.luben:zstd-jni:1.5.7-4@aar")
    implementation("org.anarres.lzo:lzo-commons:1.0.6")
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild") { dependsOn(copyLegalNotices) }

tasks.register<Copy>("copyReleaseApks") {
    dependsOn("assembleRelease")
    val v = android.defaultConfig.versionName
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "QuDroid-Plus-app.qudroid.vm.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("release-apks/$v"))
}
