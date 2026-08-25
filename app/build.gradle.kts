import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The single source of truth for the app's version. Bump here and nowhere else;
// Settings' About line reads BuildConfig.VERSION_NAME, which comes from this.
val perchVersionCode = 6
val perchVersionName = "0.5.0"

// Release signing lives outside the repo, at ~/.perch/signing.properties (U02).
// Losing that keystore makes every future install a data wipe rather than an
// update, so it is deliberately not a build artifact and not in git. A clone
// without it must still build: `signingConfig` stays null and the release build
// falls back to debug signing with a warning, below.
val signingPropsFile = File(System.getProperty("user.home"), ".perch/signing.properties")
val perchSigningProps: Properties? = signingPropsFile.takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

android {
    namespace = "dev.mkiros.perch"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mkiros.perch"
        minSdk = 26
        targetSdk = 35
        versionCode = perchVersionCode
        versionName = perchVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (perchSigningProps != null) {
            create("release") {
                storeFile = file(perchSigningProps.getProperty("storeFile"))
                storePassword = perchSigningProps.getProperty("storePassword")
                keyAlias = perchSigningProps.getProperty("keyAlias")
                keyPassword = perchSigningProps.getProperty("keyPassword")
                // v2 covers API 24+ and minSdk is 26, so v1 (JAR signing) buys
                // nothing and AGP disables it anyway. v3 is on for the key-rotation
                // lineage it would need if this key were ever compromised — the
                // certificate, and therefore the update identity, is unchanged by it.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (perchSigningProps != null) {
                signingConfigs.getByName("release")
            } else {
                // A clean clone still builds. It just produces an APK that cannot
                // update anyone's install — which is exactly what v0.1.0 was.
                logger.warn(
                    "Perch: ${signingPropsFile.path} not found — signing release with the " +
                        "debug key. This APK will NOT install over a release-signed Perch."
                )
                signingConfigs.getByName("debug")
            }
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
        compose = true
        // Settings' About line reads BuildConfig.VERSION_NAME (T27).
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true

        // T32's live acceptance gate is the only test allowed to touch the network, and
        // it opts in from the command line: `-Pperch.live=true`. A Gradle property is not
        // visible to the test JVM, so it is forwarded as a system property here — which
        // also makes it a task input, so toggling it re-runs the tests. A live run is
        // never up-to-date: the point of it is that the internet changed.
        unitTests.all {
            val live = project.findProperty("perch.live")?.toString() ?: "false"
            it.systemProperty("perch.live", live)
            if (live == "true") {
                it.outputs.upToDateWhen { false }
                it.testLogging { showStandardStreams = true }
            }
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)

    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
