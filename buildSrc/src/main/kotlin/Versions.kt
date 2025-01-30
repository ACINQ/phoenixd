object Versions {
    val kotlin = "1.9.23"
    val lightningKmp = "1.8.5-SNAPSHOT"
    val sqlDelight = "2.0.1" // TODO: remove 'addEnclosingTransaction' hack in AfterVersionX files when upgrading
    val okio = "3.8.0"
    val clikt = "4.2.2"
    val ktor = "2.3.8"
    fun ktor(module: String) = "io.ktor:ktor-$module:$ktor"
}