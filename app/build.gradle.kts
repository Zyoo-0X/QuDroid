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

// Penandatanganan TETAP: setiap APK (release & debug, tempatan & CI) mesti guna kunci yang sama, kalau tidak Android
// menolak update dengan "package conflicts with an existing package". Urutan keutamaan:
//   1. pemboleh ubah persekitaran QUDROID_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD (GitHub Secrets)
//   2. keystore.properties di root (tidak dalam repo, untuk kunci peribadi)
//   3. app/signing/signing.properties (kunci tetap dalam repo)
val signProps = Properties().apply {
    val env = System.getenv("QUDROID_STORE_FILE")?.takeIf { it.isNotBlank() }
    if (env != null) {
        setProperty("storeFile", env)
        setProperty("storePassword", System.getenv("QUDROID_STORE_PASSWORD") ?: "")
        setProperty("keyAlias", System.getenv("QUDROID_KEY_ALIAS") ?: "")
        setProperty("keyPassword", System.getenv("QUDROID_KEY_PASSWORD") ?: "")
    } else {
        val f = listOf(rootProject.file("keystore.properties"), file("signing/signing.properties"))
            .firstOrNull { it.isFile }
        f?.inputStream()?.use { load(it) }
        f?.let { setProperty("baseDir", it.parentFile.absolutePath) }
    }
}
val signStore: File? = signProps.getProperty("storeFile")?.let { path ->
    val base = signProps.getProperty("baseDir")
    listOfNotNull(File(path).takeIf { it.isAbsolute }, base?.let { File(it, path) }, rootProject.file(path))
        .firstOrNull { it.isFile }
}
val canSign = signStore != null
if (!canSign) logger.warn("QuDroid: tiada kunci tandatangan ditemui; APK tidak akan boleh dikemas kini dengan APK lain.")

// versionCode mesti sentiasa NAIK pada setiap release. Ia dikira daripada versionName (1.0.0-beta.8 -> 1000028):
// M*1_000_000 + m*10_000 + p*100 + peringkat (alpha 1-19, beta 21-49, rc 51-89, stabil 99).
fun versionCodeOf(name: String): Int {
    val core = name.trim().removePrefix("v").substringBefore('-').substringBefore('+')
    val n = core.split('.').map { it.toIntOrNull() ?: 0 }
    val base = n.getOrElse(0) { 0 } * 1_000_000 + n.getOrElse(1) { 0 } * 10_000 + n.getOrElse(2) { 0 } * 100
    val pre = name.trim().substringAfter('-', "").lowercase()
    if (pre.isEmpty()) return base + 99
    val num = Regex("\\d+").find(pre)?.value?.toIntOrNull() ?: 0
    val stage = when {
        pre.startsWith("alpha") -> minOf(num, 19)
        pre.startsWith("beta") -> 20 + minOf(num, 29)
        pre.startsWith("rc") -> 50 + minOf(num, 39)
        else -> 20 + minOf(num, 29)
    }
    return base + stage
}

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
        // versionName diubah oleh workflow (sed/regex) dan versionCode dikira daripadanya
        versionName = "1.0.0-beta.8"
        versionCode = versionCodeOf("1.0.0-beta.8")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (canSign) create("release") {
            storeFile = signStore
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
