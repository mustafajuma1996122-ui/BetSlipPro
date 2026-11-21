plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.betslippro.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.betslippro.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "3.0.0"
        // Predictions feed published by the free GitHub Actions engine (GitHub Pages). Derived automatically on CI.
        val repo = System.getenv("GITHUB_REPOSITORY") ?: ""
        val feedUrl = (project.findProperty("feedUrl") as String?)
            ?: if (repo.contains("/")) "https://${repo.substringBefore('/').lowercase()}.github.io/${repo.substringAfter('/')}/predictions.json" else ""
        buildConfigField("String", "FEED_URL", "\"$feedUrl\"")
    }

    // Optional release signing: provide the 4 values as environment variables / GitHub secrets (free).
    val ksPath = System.getenv("BETSLIP_KEYSTORE_PATH")
    signingConfigs {
        if (!ksPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("BETSLIP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BETSLIP_KEY_ALIAS")
                keyPassword = System.getenv("BETSLIP_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!ksPath.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Local database, background work and encrypted key storage (all free / open source).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for feed-parsing unit tests (android.jar only has stubs)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
