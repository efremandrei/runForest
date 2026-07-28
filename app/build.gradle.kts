plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val speedTestVersionCode = providers.gradleProperty("speedTestVersionCode").get().toInt()
val speedTestVersionName = providers.gradleProperty("speedTestVersionName").get()

android {
    namespace = "com.andre.speedtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.andre.speedtest"
        minSdk = 26
        targetSdk = 36
        versionCode = speedTestVersionCode
        versionName = speedTestVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    applicationVariants.all {
        val variantName = buildType.name
        outputs.all {
            val abi = filters.find { it.filterType == com.android.build.OutputFile.ABI }?.identifier ?: "universal"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "SpeedTest-v${speedTestVersionName}-build-${speedTestVersionCode}-${abi}-${variantName}.apk"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.2")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling:1.8.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

