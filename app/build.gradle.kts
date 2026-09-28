import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.silge.snidroid"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "de.silge.snidroid"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}

// Optionally copy every built APK to an export folder. Set snidroid.apkExportDir in ~/.gradle/gradle.properties
// or pass -Psnidroid.apkExportDir=... Skipped if unset or the folder does not exist.
val apkExportDir: String = providers.gradleProperty("snidroid.apkExportDir").getOrElse("")
val appVersionName: String = android.defaultConfig.versionName ?: "dev"

androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        val taskSuffix = variantName.replaceFirstChar { it.uppercase() }
        val exportTask = tasks.register<Copy>("export${taskSuffix}Apk") {
            description = "Copies the $variantName APK to $apkExportDir"
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { "SNIDroid-$appVersionName-$variantName.apk" }
            }
            into(apkExportDir)
            onlyIf {
                if (apkExportDir.isBlank()) return@onlyIf false
                val exists = File(apkExportDir).isDirectory
                if (!exists) logger.warn("APK export skipped: folder '$apkExportDir' not found")
                exists
            }
        }
        tasks.matching { it.name == "assemble$taskSuffix" }.configureEach { finalizedBy(exportTask) }
    }
}
