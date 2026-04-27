plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
}

// API 키는 이제 런타임 DataStore(ApiKeyStore) 에서 관리. 설정 화면에서 입력.

android {
    namespace = "com.mingeek.studiopop"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mingeek.studiopop"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 64-bit only — Google Play 가 권장. 32-bit 는 emulator/구형용
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// 외부 배포용 (서명된 APK/AAB) 파일명을 StudioPop-v<versionName>-release.<ext> 로 변경.
// AGP 9 에서 VariantOutput.outputFileName 이 제거돼 build 시 파일명 직접 변경 불가.
// Build → Generate Signed Bundle / APK 위저드는 서명된 결과를 app/release/ 에 복사하는데
// 이 복사 단계는 Android Studio 의 후처리라 Gradle 훅으로 자동 트리거 안 됨.
// → 위저드 끝낸 뒤 한 번:
//   ./gradlew :app:renameSignedRelease
// 일반 assembleRelease 는 외부 배포용 아니라 rename 안 함 (build/outputs/apk/release/ 그대로).
tasks.register("renameSignedRelease") {
    group = "distribution"
    description = "Build → Generate Signed Bundle / APK 위저드가 app/release/ 에 출력한 결과를 " +
        "StudioPop-v<versionName>-release.<ext> 로 변경"
    doLast {
        val appName = "StudioPop"
        val versionName = android.defaultConfig.versionName ?: "dev"
        val releaseDir = file("release")
        if (!releaseDir.exists()) {
            logger.lifecycle("app/release/ 가 없음. 위저드를 먼저 실행하세요.")
            return@doLast
        }
        val targets = releaseDir.listFiles()
            ?.filter {
                it.isFile && (it.name == "app-release.apk" || it.name == "app-release.aab")
            }
            ?: emptyList()
        if (targets.isEmpty()) {
            logger.lifecycle("app/release/ 에 app-release.apk 또는 .aab 가 없음 — 이미 rename 됐거나 " +
                "위저드 결과가 다른 곳에 있어요.")
            return@doLast
        }
        for (source in targets) {
            val target = releaseDir.resolve("$appName-v$versionName-release.${source.extension}")
            if (source.renameTo(target)) {
                logger.lifecycle("✓ ${source.name} → ${target.name}")
            } else {
                logger.warn("✗ rename 실패: ${source.name}")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.retrofit)
    implementation(libs.converter.moshi)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.accompanist.permissions)
    implementation(libs.play.services.location)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.core)
    implementation(libs.logging.interceptor)
    implementation(libs.okhttp)
    implementation(libs.moshi.kotlin)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.vosk.android)
    implementation(libs.mlkit.face.detection)
    // Phase 3 (vocal separation)
    implementation(libs.onnxruntime.android)
    implementation(libs.jtransforms)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.runner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    "ksp"(libs.androidx.room.compiler)
    "ksp"(libs.moshi.kotlin.codegen)
}