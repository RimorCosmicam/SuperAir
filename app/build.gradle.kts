import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.superair"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.superair"
        minSdk = 33
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
    }

    // Release signing comes from keystore.properties at the project root, which is never committed.
    // Without it, a release build is left unsigned rather than failing.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            // No R8: the host is loaded by app_process by class name, and Sys reaches hidden
            // framework APIs by reflection. Shrinking would have to be taught all of it.
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    sourceSets {
        // The shell-side host is compiled into this same APK: app_process is handed the APK's own
        // installed path, so there is one artifact to install and no writable copy of our code
        // sitting in /data/local/tmp where anything on the device could read it.
        getByName("main") { java.srcDir("../server/src") }
    }

    buildFeatures { compose = true; aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/DEPENDENCIES") } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // The ADB client: SPAKE2 pairing, the TLS handshake and the shell streams, so the phone
    // restarts its own host with no computer attached.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
