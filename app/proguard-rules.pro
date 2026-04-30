# =============================================================================
# StudioPop ProGuard / R8 rules
#
# 설계 원칙:
#   - 의존성별로 섹션을 분리해 의존성 추가/제거 시 어느 블록을 손볼지 명확.
#   - 각 keep 의 *이유* 를 주석으로 남김 — 최적화 강도 조정 시 안전하게 풀 수 있음.
#   - "혹시 모르니 keep" 류는 추가하지 않음. 부풀어오를수록 R8 의 효과가 줄어듦.
# =============================================================================

# 디오브퓨스케이션 매핑이 의미 있도록 소스/라인 정보 보존.
# (크래시 리포트가 readable 한 스택트레이스를 갖도록 — outputs/mapping/release/ 와 함께
#  매핑 파일 보관.)
-keepattributes SourceFile, LineNumberTable, *Annotation*, EnclosingMethod, InnerClasses, Signature, Exceptions
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# Kotlin
# -----------------------------------------------------------------------------
# Kotlin reflection / Metadata 는 Moshi KotlinJsonAdapterFactory, Compose 일부 reflection
# 경로에서 필요. 데이터 클래스 메타데이터가 잘리면 런타임 NPE/직렬화 실패.
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Companion object 는 reflection 으로 접근될 수 있음 (Moshi/Retrofit etc.)
-keepclassmembers class **$Companion { *; }

# Kotlin coroutines — DebugProbesKt 만 stripping 시 안내 출력.
-dontwarn kotlinx.coroutines.debug.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

# -----------------------------------------------------------------------------
# Compose
# -----------------------------------------------------------------------------
# Compose 컴파일러 플러그인이 생성하는 클래스/메소드는 R8 이 알지 못함.
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# -----------------------------------------------------------------------------
# Moshi (JsonClass codegen + KotlinJsonAdapterFactory 둘 다 사용)
# -----------------------------------------------------------------------------
# 코드젠 어댑터는 KSP 가 META-INF/proguard/moshi-*.pro 를 자동 생성 (build/ 에 확인됨) —
# 별도 keep 불필요. KotlinJsonAdapterFactory fallback 경로용 최소 보호만 추가.
-keep @com.squareup.moshi.JsonQualifier @interface *
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
# 코드젠으로 만들어진 *JsonAdapter 는 reflection 으로 인스턴스화됨.
-keepnames class **JsonAdapter

# Moshi internal — Kotlin reflect.
-dontwarn org.jetbrains.annotations.**

# -----------------------------------------------------------------------------
# Retrofit + OkHttp
# -----------------------------------------------------------------------------
# Retrofit 인터페이스는 Proxy 로 invoke 되므로 시그니처/제너릭 정보 보존 필수.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.Platform$Java8

# OkHttp — 로깅 인터셉터/플랫폼 선택용 reflection.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Okio — 일부 클래스가 jdk.internal 참조.
-dontwarn okio.**
-dontwarn java.nio.file.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# -----------------------------------------------------------------------------
# Room
# -----------------------------------------------------------------------------
# Room 은 KSP 로 *_Impl 구현체를 생성 — annotation processor 산출물이라 keep 불필요하지만,
# Database 클래스의 abstract 메소드가 reflection 으로 호출되는 경로 보호.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# -----------------------------------------------------------------------------
# DataStore (preferences)
# -----------------------------------------------------------------------------
-dontwarn com.google.protobuf.**

# -----------------------------------------------------------------------------
# Media3 (Transformer / ExoPlayer / Effect)
# -----------------------------------------------------------------------------
# Media3 는 일부 효과/디코더 모듈을 reflection 으로 lookup — provider 이름 유지.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# -----------------------------------------------------------------------------
# JNI bridge — whisper.cpp 와 연결
# -----------------------------------------------------------------------------
# native 메소드는 클래스 풀네임 + 메소드 이름으로 dlsym 됨. 클래스/메소드명 모두 keep.
-keep class com.mingeek.studiopop.data.caption.WhisperCppEngine { *; }
-keepclassmembers class com.mingeek.studiopop.data.caption.WhisperCppEngine {
    native <methods>;
}

# -----------------------------------------------------------------------------
# Vosk (speech recognition, JNI)
# -----------------------------------------------------------------------------
# Vosk 는 org.vosk.* native 바인딩. 모든 public API 유지.
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# -----------------------------------------------------------------------------
# ONNX Runtime (vocal separation)
# -----------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# -----------------------------------------------------------------------------
# ML Kit (face detection)
# -----------------------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# -----------------------------------------------------------------------------
# Play services (auth, location)
# -----------------------------------------------------------------------------
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# -----------------------------------------------------------------------------
# CameraX
# -----------------------------------------------------------------------------
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# -----------------------------------------------------------------------------
# JTransforms (vocal separation FFT)
# -----------------------------------------------------------------------------
-keep class org.jtransforms.** { *; }
-keep class pl.edu.icm.jlargearrays.** { *; }
-dontwarn org.jtransforms.**
-dontwarn pl.edu.icm.jlargearrays.**

# -----------------------------------------------------------------------------
# Coil
# -----------------------------------------------------------------------------
-dontwarn coil.**

# -----------------------------------------------------------------------------
# 빌드 안정화 — JVM-only / Java EE 클래스 누락 경고 무시
# -----------------------------------------------------------------------------
-dontwarn java.beans.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**

# -----------------------------------------------------------------------------
# StudioPop 도메인 모델 — Moshi 직렬화 대상 (DTO) 보호
# -----------------------------------------------------------------------------
# DTO/Entity 클래스는 reflection (KotlinJsonAdapterFactory) fallback 경로에서 사용될 수 있음.
# 코드젠 @JsonClass 가 우선이지만, 일부는 코드젠 미사용 — 이름 유지로 안전 마진 확보.
-keep class com.mingeek.studiopop.data.**.*Dto { *; }
-keep class com.mingeek.studiopop.data.**.*Entity { *; }
-keepclassmembers class com.mingeek.studiopop.data.** {
    <init>(...);
}
