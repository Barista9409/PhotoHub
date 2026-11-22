import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.photohub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.photohub.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 41
        versionName = "0.28.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // GitHub repository whose latest Release the app updates itself from
        buildConfigField("String", "UPDATE_REPO", "\"Barista9409/PhotoHub\"")
    }

    signingConfigs {
        // Release signing comes from ~/.gradle/gradle.properties or environment, never from the repo.
        create("release") {
            val ks = System.getenv("PHOTOHUB_KEYSTORE") ?: (project.findProperty("photohub.keystore") as String?)
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("PHOTOHUB_KEYSTORE_PASSWORD") ?: project.findProperty("photohub.keystore.password") as String?
                keyAlias = System.getenv("PHOTOHUB_KEY_ALIAS") ?: (project.findProperty("photohub.key.alias") as String? ?: "photohub")
                keyPassword = System.getenv("PHOTOHUB_KEY_PASSWORD") ?: project.findProperty("photohub.key.password") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-video:3.3.0")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")

    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    // QR code reading for setup (Google code scanner: no camera permission needed)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Google Drive backup (Authorization API, scope drive.file)
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

/**
 * In-app update package: ./gradlew publishUpdate
 * writes dist/photohub-<ver>.apk + dist/photohub-<ver>.json (notes from release-notes.txt).
 * Both files are attached to a GitHub Release (done by .github/workflows/android-release.yml
 * when a tag v<versionName> is pushed); phones pick up the latest Release.
 * Must be signed with the release key (PHOTOHUB_KEYSTORE...), or installed apps cannot update.
 */
tasks.register("publishUpdate") {
    dependsOn("assembleRelease")
    doLast {
        if (System.getenv("PHOTOHUB_KEYSTORE") == null && project.findProperty("photohub.keystore") == null) {
            throw GradleException("release key not configured (PHOTOHUB_KEYSTORE / photohub.keystore)")
        }
        val name = android.defaultConfig.versionName!!
        val code = android.defaultConfig.versionCode!!
        val out = rootProject.file("../dist").apply { mkdirs() }
        val apk = file("build/outputs/apk/release/app-release.apk")
        val dst = File(out, "photohub-$name.apk")
        apk.copyTo(dst, overwrite = true)
        val notesFile = file("release-notes.txt")
        val notes = if (notesFile.exists()) notesFile.readText().trim() else ""
        fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val sha = MessageDigest.getInstance("SHA-256").digest(dst.readBytes()).joinToString("") { "%02x".format(it) }
        File(out, "photohub-$name.json").writeText(
            "{\"versionCode\": $code, \"versionName\": \"${esc(name)}\", \"notes\": \"${esc(notes)}\", " +
                "\"apk\": \"${dst.name}\", \"size\": ${dst.length()}, \"sha256\": \"$sha\"}\n")
        println("wrote ${dst.path} and photohub-$name.json")
    }
}
