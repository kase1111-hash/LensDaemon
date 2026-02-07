plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

android {
    namespace = "com.lensdaemon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lensdaemon"
        minSdk = 29  // Android 10 minimum
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    flavorDimensions += "feature"
    productFlavors {
        create("core") {
            dimension = "feature"
            buildConfigField("boolean", "ENABLE_DIRECTOR", "false")
        }
        create("full") {
            dimension = "feature"
            buildConfigField("boolean", "ENABLE_DIRECTOR", "true")
        }
    }
}

dependencies {
    // Director module (included in all flavors; gated at runtime via ENABLE_DIRECTOR)
    implementation(project(":director"))
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // NanoHTTPD for embedded web server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Timber for logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // AWS S3 SDK (for S3-compatible storage)
    implementation("com.amazonaws:aws-android-sdk-s3:2.73.0")

    // SMB client (for network shares)
    implementation("com.hierynomus:smbj:0.12.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
}
