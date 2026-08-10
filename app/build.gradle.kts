plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** Test-only handle on the raw pdfbox-android AAR, so its bundled assets can be extracted below. */
val pdfboxAar: Configuration by configurations.creating { isTransitive = false }

/**
 * The short commit the APK was built from, baked into `BuildConfig.GIT_COMMIT` so the About page can
 * name the exact source revision. Falls back to "unknown" when git isn't available (a source tarball
 * or a checkout-less CI step), since a missing hash must never fail the build.
 */
fun gitCommit(): String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

android {
    namespace = "com.nexopp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nexopp"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // PDFBox's font parser logs through android.util.Log; without this, loading a TTF in a
            // JVM unit test throws "not mocked" instead of returning a no-op.
            isReturnDefaultValues = true
        }
    }
    sourceSets.getByName("test") {
        resources.srcDir(layout.buildDirectory.dir("generated/pdfboxTestResources"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.pdfbox.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)

    pdfboxAar(libs.pdfbox.android)
}

// PDFBox ships its CMap/glyph-list data in the AAR's `assets/`, which it reaches through Android's
// AssetManager at runtime. JVM unit tests have no AssetManager, so PDFBox falls back to loading the
// same paths off the classpath — extract the AAR assets into the unit-test resources so font
// embedding (PDType0Font, "Identity-H") works in `testDebugUnitTest` the way it does on device.
val extractPdfboxAssets by tasks.registering(Copy::class) {
    from(provider { zipTree(pdfboxAar.singleFile) }) {
        include("assets/**")
        eachFile { path = path.removePrefix("assets/") }
    }
    into(layout.buildDirectory.dir("generated/pdfboxTestResources"))
    includeEmptyDirs = false
}

androidComponents.onVariants { variant ->
    tasks.matching { it.name == "process${variant.name.replaceFirstChar(Char::uppercase)}UnitTestJavaRes" }
        .configureEach { dependsOn(extractPdfboxAssets) }
}
