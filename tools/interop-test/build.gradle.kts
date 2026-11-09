// Runs the Android app's protocol code (pure Kotlin) on the JVM against packets
// exported by the Windows sender:  gradle run --args="<packets.bin> <original file>"
plugins {
    kotlin("jvm") version "1.9.24"
    application
}
repositories { mavenCentral() }
dependencies { implementation("com.google.zxing:javase:3.5.3") }
kotlin { jvmToolchain(17) }
sourceSets["main"].kotlin.srcDir("../../android/app/src/main/java/io/github/aboss3b13/qrshare/protocol")
application { mainClass.set("InteropTestKt") }
