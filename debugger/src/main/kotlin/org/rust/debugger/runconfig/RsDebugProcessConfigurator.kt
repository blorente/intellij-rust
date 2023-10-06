/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.runconfig

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess
import com.jetbrains.cidr.execution.debugger.CidrDebugProcessConfigurator
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.cargoProjects
import org.rust.debugger.bllog

class RsDebugProcessConfigurator : CidrDebugProcessConfigurator {
    override fun configure(process: CidrDebugProcess) {
        bllog("========== Configuring Debug Process ", process, "===========")
        bllog("previous configurators are: ", CidrDebugProcessConfigurator.EP_NAME.extensionList)
        val cargoProject = findCargoProject(process) ?: return
        bllog("Found a valid cargo project: ", cargoProject, " configuring debug process")
        RsDebugProcessConfigurationHelper(process, cargoProject).configure()
        bllog("========== DONE Configuring Debug Process ===========")
    }

    companion object {
        val LOG: Logger = logger<RsDebugProcessConfigurator>()

        fun findCargoProject(process: CidrDebugProcess): CargoProject? {
            bllog(String.format("findCargoProject for process: %s", process))
            return when {

                process is RsLocalDebugProcess -> {
                    bllog(String.format("Detected local process. Returning cargo project: ", process.runParameters.cargoProject))
                    // In case of Rust project, select the corresponding Cargo project
                    process.runParameters.cargoProject
                }

                process.project.cargoProjects.hasAtLeastOneValidProject -> {
                    // In case of cross-language project (e.g. C project with some Rust code inside),
                    // we actually don't know which Cargo project will be used during execution.
                    // So any of the available Rust projects can be selected
                    val project: CargoProject? = process.project.cargoProjects.allProjects.firstOrNull()
                    bllog(String.format("Non local debug process, but we have some valid projects. Returning cargo project: ", project))
                    project
                }

                else -> {
                    bllog("Could not find cargo project for process.")
                    // Otherwise, don't configure the debug process for Rust
                    null
                }
            }
        }
    }
}
