plugins {
    alias(libs.plugins.android.application)
}

// Android 应用构建配置
android {
    namespace = "com.example.zhizijing"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.zhizijing"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // 启用布局绑定生成类
    buildFeatures {
        viewBinding = true
    }
}

// 核心能力依赖集中声明
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.mlkit.pose.detection)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.play.services.nearby)
    implementation(libs.gson)
    annotationProcessor(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val prepareAsciiDebugUnitTest = tasks.register("prepareAsciiDebugUnitTest") {
    dependsOn("compileDebugUnitTestKotlin", "bundleDebugClassesToRuntimeJar")

    doLast {
        val asciiRoot = File(System.getProperty("user.home"), ".gradle/zhizijing-ascii-test/debugUnitTest")
        val testClasses = layout.buildDirectory
            .dir("intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes")
            .get()
            .asFile
        val appClassesJar = layout.buildDirectory
            .file("intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar")
            .get()
            .asFile

        copy {
            from(testClasses)
            into(File(asciiRoot, "test-classes"))
        }
        copy {
            from(appClassesJar)
            into(File(asciiRoot, "app-classes"))
        }
    }
}

afterEvaluate {
    tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest").configure {
        dependsOn(prepareAsciiDebugUnitTest)

        val asciiRoot = File(System.getProperty("user.home"), ".gradle/zhizijing-ascii-test/debugUnitTest")
        val asciiTestClasses = File(asciiRoot, "test-classes")
        val asciiAppClassesJar = File(asciiRoot, "app-classes/classes.jar")
        testClassesDirs = files(asciiTestClasses)
        classpath = files(asciiTestClasses, asciiAppClassesJar, classpath)
    }
}
