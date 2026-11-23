import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.lifehub.plugins.travel"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":plugin-api"))
    implementation(project(":plugin-runtime"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":plugin-testing"))
    testImplementation(libs.junit)

    constraints {
        // 与 app 模块解析到的版本对齐，避免单独解析 1.3.0 时重新下载。
        implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    }
}
