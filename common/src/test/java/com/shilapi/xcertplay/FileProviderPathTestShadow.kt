package com.shilapi.xcertplay

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/** FileProvider assumes Android's '/' separator; Robolectric uses the host filesystem. */
@Implements(className = "androidx.core.content.FileProvider\$SimplePathStrategy", isInAndroidSdk = false)
class FileProviderPathTestShadow {
    @Implementation
    protected fun belongsToRoot(filePath: String, rootPath: String): Boolean =
        filePath.trimEnd(File.separatorChar).startsWith(rootPath.trimEnd(File.separatorChar) + File.separator)
}
