plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun parseDotEnvValue(value: String): String {
    var parsed = value.trim()
    val commentIndex = parsed.indexOf(" #")
    if (commentIndex != -1) {
        parsed = parsed.substring(0, commentIndex).trim()
    }

    if (
        (parsed.startsWith("\"") && parsed.endsWith("\"")) ||
        (parsed.startsWith("'") && parsed.endsWith("'"))
    ) {
        parsed = parsed.substring(1, parsed.length - 1)
    }

    return parsed.replace("\\n", "\n")
}

fun readDotEnvValue(keys: Set<String>): String {
    val envFiles = listOf(rootProject.file(".env"), rootProject.file("backend/.env"))
    val linePattern = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)\\s*$")

    for (file in envFiles) {
        if (!file.exists()) continue

        for (line in file.readLines()) {
            val match = linePattern.matchEntire(line) ?: continue
            val key = match.groupValues[1]
            if (key !in keys) continue

            val value = parseDotEnvValue(match.groupValues[2])
            if (value.isNotBlank()) return value
        }
    }

    return ""
}

fun String.asBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

val defaultBackendUrl = providers.environmentVariable("BACKEND_URL").orNull
    ?: providers.environmentVariable("backend_url").orNull
    ?: readDotEnvValue(setOf("BACKEND_URL", "backend_url"))

android {
    namespace = "com.replyassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.replyassistant"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DEFAULT_BACKEND_URL", defaultBackendUrl.asBuildConfigString())
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.mlkit:text-recognition:16.0.1")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
