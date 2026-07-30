// Top-level build file. Plugin versions come from gradle/libs.versions.toml; each module
// applies what it needs. Keep configuration in the module build files, not here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
