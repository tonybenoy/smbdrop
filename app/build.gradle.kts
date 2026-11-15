import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystoreProps = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.tonybenoy.smbdrop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tonybenoy.smbdrop"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
        resourceConfigurations += listOf("en", "es", "de", "fr", "hi")
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
    dependenciesInfo { includeInApk = false; includeInBundle = false }   // no proprietary dependency blob (F-Droid)
    packaging {
        resources {
            excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/MANIFEST.MF",
                "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.room:room-runtime:2.8.1")
    implementation("androidx.room:room-ktx:2.8.1")
    ksp("androidx.room:room-compiler:2.8.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    implementation("com.rapid7.client:dcerpc:0.12.13")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.slf4j:slf4j-android:1.7.36")
    testImplementation("junit:junit:4.13.2")
}
