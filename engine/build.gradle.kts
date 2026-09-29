plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Symja —— 与原版同源的符号计算内核（GPL-3.0）
    implementation("org.matheclipse:matheclipse-core:3.1.1")
    implementation("org.matheclipse:matheclipse-parser:3.1.1")
    implementation("org.matheclipse:matheclipse-external:3.1.1")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// 调试用：打印编译期依赖的 jar 路径
tasks.register("printClasspath") {
    val cp = configurations.named("compileClasspath")
    doLast {
        cp.get().files.forEach { println(it.absolutePath) }
    }
}
