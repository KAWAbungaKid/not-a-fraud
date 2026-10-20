package com.maven.notafraud.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

/** Foreground window + idle time, via the same Win32 calls the Python agent plan used. */
object Win32 {
    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    data class Foreground(val process: String, val title: String, val hwnd: WinDef.HWND?)

    fun foreground(): Foreground? {
        if (!isWindows) return null
        return runCatching {
            val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return null
            val buf = CharArray(512)
            User32.INSTANCE.GetWindowText(hwnd, buf, buf.size)
            val title = Native.toString(buf)
            val pidRef = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
            Foreground(processName(pidRef.value), title, hwnd)
        }.getOrNull()
    }

    private fun processName(pid: Int): String {
        val h = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid) ?: return "unknown"
        try {
            val buf = CharArray(1024)
            val size = IntByReference(buf.size)
            return if (Kernel32.INSTANCE.QueryFullProcessImageName(h, 0, buf, size)) {
                String(buf, 0, size.value).substringAfterLast('\\').lowercase()
            } else "unknown"
        } finally {
            Kernel32.INSTANCE.CloseHandle(h)
        }
    }

    /** Seconds since the last keyboard or mouse input. */
    fun idleSeconds(): Long {
        if (!isWindows) return 0
        return runCatching {
            val info = WinUser.LASTINPUTINFO()
            User32.INSTANCE.GetLastInputInfo(info)
            val ticks = Kernel32.INSTANCE.GetTickCount().toLong() and 0xFFFFFFFFL
            ((ticks - (info.dwTime.toLong() and 0xFFFFFFFFL)) / 1000).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    fun minimize(hwnd: WinDef.HWND?) {
        if (!isWindows || hwnd == null) return
        runCatching { User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_MINIMIZE) }
    }

    /** Start with Windows: HKCU Run key pointing at this executable. */
    fun setAutostart(on: Boolean): Boolean {
        if (!isWindows) return false
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return false
        val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        val cmd = if (on) listOf("reg", "add", key, "/v", "NotAFraud", "/t", "REG_SZ", "/d", "\"$exe\" --minimized", "/f")
        else listOf("reg", "delete", key, "/v", "NotAFraud", "/f")
        return runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }

    fun autostartEnabled(): Boolean {
        if (!isWindows) return false
        return runCatching {
            val p = ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "NotAFraud")
                .redirectErrorStream(true).start()
            p.waitFor() == 0
        }.getOrDefault(false)
    }
}
