plugins {
    id("com.android.application") version "8.12.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}

val speedTestVersionCode = providers.gradleProperty("speedTestVersionCode").get()
val speedTestVersionName = providers.gradleProperty("speedTestVersionName").get()

tasks.register<Copy>("packageDebugApks") {
    dependsOn(":app:assembleDebug")
    doFirst {
        delete(fileTree("artifacts") { include("*.apk") })
    }
    from("app/build/outputs/apk/debug")
    include("SpeedTest-v${speedTestVersionName}-build-${speedTestVersionCode}-arm64-v8a-debug.apk")
    into("artifacts")
}

tasks.register("printSpeedTestVersion") {
    doLast {
        println("Speed Test v$speedTestVersionName build $speedTestVersionCode")
    }
}

