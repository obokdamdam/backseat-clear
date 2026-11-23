plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.backseatclear"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.backseatclear"
        minSdk = 28 // Pleos Connect 최소 API
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-demo"
    }

    // CarUxRestrictions(주행 중 제한) 등 AAOS 표준 차량 API
    useLibrary("android.car")

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 release APK 는 디버그 키로 서명(심사용). 마켓 출시 때 정식 키로 바꾼다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // 에뮬레이터 없이 화면을 PNG 로 그려 확인(Robolectric + Roborazzi)
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.maxHeapSize = "3g"
        }
    }
}

// JDK 21 로 빌드하고, 바이트코드만 17 수준으로 맞춘다
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Pleos Connect SDK — 공모전 제공 5종
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation(platform("androidx.compose:compose-bom:2025.05.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
