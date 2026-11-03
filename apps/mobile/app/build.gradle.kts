plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "org.wastingnotime.linkshelf"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.wastingnotime.linkshelf"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val releaseStore = providers.environmentVariable("LINKSHELF_UPLOAD_STORE")
    if (releaseStore.isPresent) {
        signingConfigs.create("upload") {
            storeFile = file(releaseStore.get())
            storePassword = providers.environmentVariable("LINKSHELF_UPLOAD_PASSWORD").get()
            keyAlias = "upload"
            keyPassword = storePassword
        }
        buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("upload") }
    }
    buildFeatures { compose = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation("androidx.core:core-splashscreen:1.2.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.documentfile:documentfile:1.1.0")
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material:material-icons-core")
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.22.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.work:work-testing:2.11.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
