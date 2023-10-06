/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger

import com.intellij.openapi.util.registry.Registry
import java.io.File

val isNewGdbSetupEnabled: Boolean get() = Registry.`is`("org.rust.debugger.gdb.setup.v2", false)

fun bllog(vararg args: Any?) {
    val contents: String = args.map { arg -> when {
        arg == null -> {"<null>"}
        else -> { arg.toString()}
    } }.joinToString(" ")
    File("/tmp/clion-log.txt").appendText("BL: $contents\n")
}
