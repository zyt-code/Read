plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.read.parser"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.epub4j.core) {
        exclude(group = "xmlpull", module = "xmlpull")
    }
    testImplementation(libs.junit)
}
