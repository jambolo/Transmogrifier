package io.github.jambolo.transmogrifier

import android.app.Application

class TransmogrifierApplication : Application() {
    // Lazy so the `:runner` process, which also creates this class, never builds it or loads API keys.
    val container by lazy { AppContainer(this) }
}
