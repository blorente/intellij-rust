/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.runconfig

import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.NlsContexts.NotificationContent
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.backend.DebuggerCommandException
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriver
import com.jetbrains.cidr.execution.debugger.backend.gdb.GDBDriver
import com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriver
import org.rust.RsBundle
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.toolchain.tools.rustc
import org.rust.cargo.toolchain.wsl.RsWslToolchain
import org.rust.debugger.*
import org.rust.debugger.settings.RsDebuggerSettings
import org.rust.ide.notifications.showBalloon
import java.nio.file.InvalidPathException

class RsDebugProcessConfigurationHelper(
    private val process: CidrDebugProcess,
    cargoProject: CargoProject
) {
    private val settings = RsDebuggerSettings.getInstance()
    private val project = process.project
    private val toolchain = process.project.toolchain
    private val threadId = process.currentThreadId
    private val frameIndex = process.currentFrameIndex

    private val commitHash = cargoProject.rustcInfo?.version?.commitHash

    private val prettyPrintersPath: String? = toolchain?.toRemotePath(PP_PATH)

    private val sysroot: String? by lazy {
        cargoProject.workingDirectory
            .let {
                val srt = toolchain?.rustc()?.getSysroot(it)
                bllog("Cargo project has a working directory. Getting sysroot from rustc. Sysroot is: ", srt)
                srt
            }
            ?.let {
                val srt = toolchain?.toRemotePath(it)
                bllog("Cargo project has no working directory. Sysroot is: ", srt)
                srt
            }
    }

    fun configure() {
        bllog("RsDebugProcessConfigurationHelper.configure()")
        bllog("Values in RsDebugProcessConfigurationHelper are")
        bllog("settings ", settings)
        bllog("project ", project)
        bllog("toolchain ", toolchain)
        bllog("threadId ", threadId)
        bllog("frameIndex ", frameIndex)
        bllog("commitHash ", commitHash)
        bllog("prettyPrintersPath ", prettyPrintersPath)

        process.postCommand { driver ->
            try {
                bllog("driver.loadRustcSources()")
                driver.loadRustcSources()
                bllog("driver.loadPrettyPrinters()")
                driver.loadPrettyPrinters()
                if (settings.breakOnPanic) {
                    bllog("driver.breakOnPanic()")
                    driver.setBreakOnPanic()
                }
                bllog("driver.setSteppingFilters()")
                driver.setSteppingFilters()
                bllog("driver.done setting filters")
            } catch (e: DebuggerCommandException) {
                bllog("Got DebuggerCommandException, ", e)
                process.printlnToConsole(e.message)
                LOG.warn(e)
            } catch (e: InvalidPathException) {
                bllog("Got InvalidPathException, ", e)
                LOG.warn(e)
            }
        }
    }

    private fun DebuggerDriver.executeInterpreterCommandAndLog(threadId: Long, frameIndex: Int, command: String) {
        bllog("calling executeInterpreterCommand(threadId=", threadId, ", frameIndex=", frameIndex, "fullCommand=", command)
        executeInterpreterCommand(threadId, frameIndex, command)
    }


    private fun DebuggerDriver.setBreakOnPanic() {
        val commands = when (this) {
            is LLDBDriver -> listOf("breakpoint set -n rust_panic")
            is GDBDriver -> listOf("set breakpoint pending on", "break rust_panic")
            else -> return
        }
        for (command in commands) {
            executeInterpreterCommandAndLog(threadId, frameIndex, command)
        }
    }

    private fun DebuggerDriver.setSteppingFilters() {
        val regexes = mutableListOf<String>()
        if (settings.skipStdlibInStepping) {
            regexes.add("^(std|core|alloc)::.*")
        }
        bllog("Setting stepping filters. Regexes: ", regexes)

        val command = when (this) {
            is LLDBDriver -> "settings set target.process.thread.step-avoid-regexp"
            is GDBDriver -> "skip -rfu"
            else -> return
        }
        for (regex in regexes) {
            executeInterpreterCommandAndLog(threadId, frameIndex, "$command $regex")
        }
    }

    private fun DebuggerDriver.loadRustcSources() {
        bllog("loadRustcSources()")
        if (commitHash == null) return

        bllog("commitHash ", commitHash)
        val sysroot = checkSysroot(sysroot, RsBundle.message("notification.content.cannot.load.rustc.sources"))
            ?: return
        bllog("sysroot ", sysroot)
        val sourceMapCommand = when (this) {
            is LLDBDriver -> "settings set target.source-map"
            is GDBDriver -> "set substitute-path"
            else -> return
        }
        bllog("sourceMapCommand ", sourceMapCommand)
        val rustcHash = "/rustc/$commitHash/".systemDependentAndEscaped()
        bllog("rustcHash ", rustcHash)
        val rustcSources = "$sysroot/lib/rustlib/src/rust/".systemDependentAndEscaped()
        bllog("rustcSources ", rustcSources)
        val fullCommand = """$sourceMapCommand "$rustcHash" "$rustcSources" """
        bllog("fullCommand ", fullCommand)
        bllog("calling executeInterpreterCommandAndLog(threadId=", threadId, ", frameIndex=", frameIndex, "fullCommand=", fullCommand)
        executeInterpreterCommandAndLog(threadId, frameIndex, fullCommand)
    }

    private fun DebuggerDriver.loadPrettyPrinters() {
        when (this) {
            is LLDBDriver -> loadPrettyPrinters()
            is GDBDriver -> loadPrettyPrinters()
        }
    }

    private fun LLDBDriver.loadPrettyPrinters() {
        when (settings.lldbRenderers) {
            LLDBRenderers.COMPILER -> {
                val sysroot = checkSysroot(sysroot, RsBundle.message("notification.content.cannot.load.rustc.renderers"))
                    ?: return
                val basePath = "$sysroot/lib/rustlib/etc"

                // MSVC toolchain does not contain Python pretty-printers.
                // The corresponding Natvis files are handled by `org.rust.debugger.RustcNatvisFileProvider`
                if ("windows-msvc" in basePath) {
                    return
                }

                val lldbLookupPath = "$basePath/$LLDB_LOOKUP.py".systemDependentAndEscaped()
                val lldbCommandsPath = "$basePath/lldb_commands".systemDependentAndEscaped()
                executeInterpreterCommandAndLog(threadId, frameIndex, """command script import "$lldbLookupPath" """)
                executeInterpreterCommandAndLog(threadId, frameIndex, """command source "$lldbCommandsPath" """)
            }

            LLDBRenderers.BUNDLED -> {
                val path = prettyPrintersPath?.systemDependentAndEscaped() ?: return
                val command = """command script import "$path/lldb_formatters" """
                bllog("calling executeInterpreterCommandAndLog(threadId=", threadId, ", frameIndex=", frameIndex, "fullCommand=", command)
                executeInterpreterCommandAndLog(threadId, frameIndex, command)
            }

            LLDBRenderers.NONE -> {
            }
        }
    }

    private fun GDBDriver.loadPrettyPrinters() {
        val path = when (settings.gdbRenderers) {
            GDBRenderers.COMPILER -> {
                val sysroot = checkSysroot(sysroot, RsBundle.message("notification.content.cannot.load.rustc.renderers"))
                    ?: return
                "$sysroot/lib/rustlib/etc".systemDependentAndEscaped()
            }
            GDBRenderers.BUNDLED -> {
                prettyPrintersPath?.systemDependentAndEscaped() ?: return
            }
            GDBRenderers.NONE -> return
        }

        // Avoid multiline Python scripts due to https://youtrack.jetbrains.com/issue/CPP-9090
        val command = """python """ +
            """sys.path.insert(0, "$path"); """ +
            """import $GDB_LOOKUP; """ +
            """$GDB_LOOKUP.register_printers(gdb); """
        executeInterpreterCommandAndLog(threadId, frameIndex, command)
    }

    private fun checkSysroot(sysroot: String?, @Suppress("UnstableApiUsage") @NotificationContent message: String): String? {
        if (sysroot == null) {
            project.showBalloon(message, NotificationType.WARNING)
        }
        return sysroot
    }

    private fun String.systemDependentAndEscaped(): String {
        val path = if (toolchain is RsWslToolchain) {
            FileUtil.toSystemIndependentName(this)
        } else {
            FileUtil.toSystemDependentName(this)
        }
        return StringUtil.escapeStringCharacters(path)
    }

    companion object {
        private val LOG: Logger = logger<RsDebugProcessConfigurationHelper>()
    }
}
