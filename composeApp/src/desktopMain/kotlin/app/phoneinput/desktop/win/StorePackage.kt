package app.phoneinput.desktop.win

import app.phoneinput.desktop.net.HostInfo
import app.phoneinput.protocol.HostOs
import com.sun.jna.Function
import com.sun.jna.ptr.IntByReference

/**
 * Whether this copy runs from the MSIX package (the Microsoft Store build). The Store installs
 * updates and starts the app at sign-in through its own StartupTask, so the GitHub self-updater
 * and the `HKCU\...\Run` entry are not used there (a packaged app's registry writes are private).
 */
object StorePackage {
    private const val APPMODEL_ERROR_NO_PACKAGE = 15700

    val isPackaged: Boolean by lazy {
        HostInfo.os == HostOs.Windows && runCatching {
            val f = Function.getFunction("kernel32", "GetCurrentPackageFullName")
            val length = IntByReference(0)
            f.invokeInt(arrayOf(length, null)) != APPMODEL_ERROR_NO_PACKAGE
        }.getOrDefault(false)
    }

    /** Phone Input in the Microsoft Store (Partner Center Store ID). */
    private const val STORE_ID = "9P9P5HVNBCF3"

    /**
     * "Check for updates" in the Store build: opens the app's Store page, where the Store checks
     * for a newer version and offers Update (the Store also updates apps automatically).
     */
    fun openStorePage() {
        runCatching { ProcessBuilder("explorer.exe", "ms-windows-store://pdp/?ProductId=$STORE_ID").start() }
    }

    /** Windows Settings › Apps › Startup, where the packaged app's StartupTask is switched on or off. */
    fun openStartupSettings() {
        runCatching { ProcessBuilder("explorer.exe", "ms-settings:startupapps").start() }
    }
}
