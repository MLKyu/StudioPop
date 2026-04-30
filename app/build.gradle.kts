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

    // Release 사인이 아직 발급되지 않았으므로, signing 발급 전까지 임시로 debug keystore 로 release 를
    // 서명 — APK 가 설치 가능한 상태를 유지해 R8/리소스 최적화 결과를 디바이스에서 직접 검증.
    // signing 발급 시 새 signingConfigs { create("release") {...} } 를 추가하고
    // buildTypes.release.signingConfig 만 교체하면 됨 (다른 release 설정은 그대로 사용).
    buildTypes {
        release {
            // R8 코드 축소 + 난독화 + 최적화. 같이 적용해야 효과가 큼 — minify 만 켜고 shrink 끄면
            // 사용 안 하는 res/asset 이 그대로 묶여 APK 가 부풀어오름.
            isMinifyEnabled = true
            isShrinkResources = true
            // PNG 추가 압축 (기본값 true 지만 명시) — APK 사이즈 추가 절감.
            isCrunchPngs = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // debug 는 dev 편의 우선 — minify/shrink 끄고 빠른 빌드 유지.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // R8 이 release 빌드 후 생성하는 매핑 파일을 outputs/mapping/release/ 에 보관.
    // 크래시 리포트 스택트레이스 디오브퓨스케이션에 필요 — APK 와 함께 보관할 것.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // .so (native libs) 는 압축하지 않고 APK 에 그대로 두기 — 런타임 메모리 매핑 가능,
    // 설치 시 압축 해제 단계 생략, 디스크 점유는 줄지만 APK 사이즈는 약간 커짐.
    // android:extractNativeLibs="false" 를 매니페스트에 두지 않아도 AGP 8+ 기본값.
    packaging {
        resources {
            // 흔한 META-INF 충돌 + 사용 안 하는 라이선스 파일 제거.
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "kotlin/**",
                "kotlin-tooling-metadata.json",
            )
        }
        jniLibs {
            useLegacyPackaging = false
            // 같은 .so 가 여러 의존성에서 충돌할 때 첫 항목 우선.
            pickFirsts += setOf(
                "**/libc++_shared.so",
            )
        }
    }
}

// 외부 배포용 (서명된 APK/AAB) 파일명을 StudioPop-v<versionName>-release.<ext> 로 자동 변경.
// AGP 9 에서 VariantOutput.outputFileName 이 제거돼 빌드 시 파일명 직접 변경 불가 — assemble/bundle
// 태스크의 doLast 에서 결과물을 후처리.
// Build → Generate Signed Bundle / APK 위저드는 결과물을 일반 build 출력(app/build/outputs/...) 이
// 아닌 app/release/ 에 직접 출력 — 두 경로 모두 스캔해 양쪽 케이스 다 커버.
// 서명되지 않은 -unsigned 결과물은 외부 배포용 아니므로 rename skip (개발용 깔끔하게 유지).
androidComponents {
    onVariants { variant ->
        if (variant.name != "release") return@onVariants
        afterEvaluate {
            listOf("assembleRelease", "bundleRelease").forEach { taskName ->
                tasks.findByName(taskName)?.doLast {
                    val appName = "StudioPop"
                    val versionName = variant.outputs.firstOrNull()?.versionName?.orNull ?: "dev"
                    val newPrefix = "$appName-v$versionName-release"
                    val candidates = listOf(
                        // 일반 signed assembleRelease 출력
                        layout.buildDirectory.dir("outputs/apk/release").get().asFile,
                        // 일반 signed bundleRelease 출력
                        layout.buildDirectory.dir("outputs/bundle/release").get().asFile,
                        // 위저드(Generate Signed Bundle / APK) 가 직접 사용하는 경로
                        layout.projectDirectory.dir("release").asFile,
                    )
                    for (dir in candidates) {
                        if (!dir.exists()) continue
                        val sources = dir.listFiles()?.filter {
                            it.isFile &&
                                (it.name == "app-release.apk" || it.name == "app-release.aab")
                        } ?: continue
                        for (source in sources) {
                            val target = dir.resolve("$newPrefix.${source.extension}")
                            // 윈도우는 target 존재 시 renameTo 가 실패 — 먼저 삭제.
                            if (target.exists()) target.delete()
                            if (source.renameTo(target)) {
                                logger.lifecycle("✓ rename: ${source.name} → ${target.name} " +
                                    "(${dir.relativeTo(rootDir).path})")
                            } else {
                                logger.warn("✗ rename 실패: ${source.absolutePath}")
                            }
                        }
                    }
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