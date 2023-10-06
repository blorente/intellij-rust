/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.debugger.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.jetbrains.cidr.execution.debugger.CidrDebuggerEditorsExtensionBase
import org.rust.debugger.bllog
import org.rust.lang.core.psi.RsDebuggerExpressionCodeFragment
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.ancestorOrSelf

class RsDebuggerEditorsExtension : CidrDebuggerEditorsExtensionBase() {
    override fun getContext(project: Project, sourcePosition: XSourcePosition): PsiElement? =
        super.getContext(project, sourcePosition)?.ancestorOrSelf<RsElement>()

    override fun createExpressionCodeFragment(project: Project, text: String, context: PsiElement, mode: EvaluationMode): PsiFile {
        bllog("RsDebuggerEditorsExtension.createExpressionCodeFragment(project=", project, " text=", text, " context=", context, " mode=", mode)
        val ret = if (context is RsElement) {
            bllog("Context was RsElement")
            RsDebuggerExpressionCodeFragment(project, text, context)
        } else {
            bllog("Context was not RsElement")
            super.createExpressionCodeFragment(project, text, context, mode)
        }
        bllog("Got code fragment: ", ret)
        return ret
    }
}
