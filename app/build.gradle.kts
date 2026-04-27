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

// 빌드 APK 파일명 커스터마이즈: "StudioPop-v<versionName>-<buildType>.apk"
// AGP 9 에서 VariantOutput.outputFileName 이 제거돼 직접 이름 변경 불가 — assemble 태스크 이후
// 원본(app-<buildType>.apk) 을 사용자 친화적 이름으로 복사. 원본도 그대로 남아 Gradle 출력 추적 호환.
androidComponents {
    onVariants { variant ->
        val appName = "StudioPop"
        afterEvaluate {
            val capitalized = variant.name.replaceFirstChar { it.uppercase() }
            tasks.findByName("assemble$capitalized")?.doLast {
                val version = variant.outputs.firstOrNull()?.versionName?.orNull ?: "dev"
                val outputDir = layout.buildDirectory
                    .dir("outputs/apk/${variant.name}").get().asFile
                // 서명 유무에 따라 원본 이름이 app-release.apk 또는 app-release-unsigned.apk 로 갈림.
                // 프리픽스 + .apk 로 매칭해 모든 케이스 커버.
                val source = outputDir.listFiles()
                    ?.firstOrNull {
                        it.name.startsWith("app-${variant.name}") &&
                                it.name.endsWith(".apk") &&
                                !it.name.startsWith(appName)
                    }
                if (source != null) {
                    val target = outputDir.resolve(
                        "$appName-v$version-${variant.buildType}.apk"
                    )
                    source.copyTo(target, overwrite = true)
                }
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