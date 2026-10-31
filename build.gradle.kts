plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register("updateAlaktraApk") {
    doLast {
        val builtApk = file("app/build/outputs/apk/debug/app-debug.apk")
        require(builtApk.exists() && builtApk.length() > 0L) {
            "Built APK not found at ${builtApk.absolutePath}"
        }
        builtApk.copyTo(file("AlaktraMusic.apk"), overwrite = true)
        builtApk.copyTo(file("app-debug.apk"), overwrite = true)
        println("Updated AlaktraMusic.apk (${file("AlaktraMusic.apk").length()} bytes)")
    }
}

