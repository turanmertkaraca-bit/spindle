package dev.lumen.app.platform

object RunControl {
    @Volatile
    var stopHandler: (() -> Unit)? = null
}
