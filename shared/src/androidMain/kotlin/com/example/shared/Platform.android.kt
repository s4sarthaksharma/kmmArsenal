package com.example.shared

import android.os.Build

actual class Platform actual constructor() {
    actual val name: String = "Android API ${Build.VERSION.SDK_INT}"
}
