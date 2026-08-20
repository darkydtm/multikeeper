package com.tonapps.tonkeeper.ui.screen.root

import android.net.Uri
import androidx.lifecycle.SavedStateHandle

class RootModelState(private val savedStateHandle: SavedStateHandle) {

    var returnUri: Uri?
        get() = savedStateHandle[RETURN_URI_KEY]
        set(value) = savedStateHandle.set(RETURN_URI_KEY, value)

    var returnPackageName: String?
        get() = savedStateHandle[RETURN_PACKAGE_NAME_KEY]
        set(value) = savedStateHandle.set(RETURN_PACKAGE_NAME_KEY, value)

    private companion object {
        private const val RETURN_URI_KEY = "return_uri"
        private const val RETURN_PACKAGE_NAME_KEY = "return_package_name"
    }
}
