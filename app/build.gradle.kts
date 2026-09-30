plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.maximemet.supercalc"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.maximemet.supercalc"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // 还没到发布阶段，先不折腾混淆
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Symja 2016 用到 java.util.function 之类 Java 8 的 API
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    buildFeatures {
        viewBinding = true
        // 关于页要显示版本号（参考实现也是读 BuildConfig.VERSION_NAME）
        buildConfig = true
    }
}

dependencies {
    implementation(project(":engine"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}

/*
  javac 兼容补丁（不改产物，只是换个喂 classpath 的方式）。

  某些受限环境会把 `Path.toRealPath()` 拦掉，而 JDK17 的 javac 收尾时要调它：
  结果是 classpath 里的**目录**项解析不出来（"package 不存在"），关闭 jar 时还会
  抛一次 AccessDenied。两者叠加，报错看上去就是「找不到 Kotlin 产物里的类」。

  绕法两条：把 Kotlin 产物先打成 jar 再交给 javac（jar 读得动，只剩收尾那声
  异常），以及 fork 成独立进程跑 javac——它自己返回 0，异常只是 stderr 噪音。
  普通机器上这么构建也完全没问题，只是 java 编译多起一个进程。
*/
fun kotlinClassesJarFor(variant: String): TaskProvider<Jar> =
    tasks.register<Jar>("kotlinClassesJarForJavac${variant.replaceFirstChar { it.uppercase() }}") {
        dependsOn("compile${variant.replaceFirstChar { it.uppercase() }}Kotlin")
        archiveFileName.set("kotlin-classes-$variant.jar")
        destinationDirectory.set(layout.buildDirectory.dir("tmp/javac-kotlin-jar/$variant"))
        from(layout.buildDirectory.dir("tmp/kotlin-classes/$variant"))
    }

val kotlinClassesJarDebug = kotlinClassesJarFor("debug")
val kotlinClassesJarRelease = kotlinClassesJarFor("release")

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    options.isFork = true
    options.forkOptions.executable = "${System.getProperty("java.home")}/bin/javac.exe"
}

// classpath 要等 AGP 配完才有值，所以放到 afterEvaluate 里换
afterEvaluate {
    tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
        val kotlinJar = if (name.contains("Release")) kotlinClassesJarRelease else kotlinClassesJarDebug
        dependsOn(kotlinJar)
        classpath = files(kotlinJar) +
            classpath.filter { !it.path.contains("kotlin-classes") }
    }
}
