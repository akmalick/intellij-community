// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.highlighter

import com.intellij.codeHighlighting.*
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil
import com.intellij.lang.annotation.Annotation
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.annotation.HighlightSeverity.*
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.StatusBarEx
import com.intellij.psi.PsiFile
import com.intellij.util.ui.UIUtil
import org.jetbrains.kotlin.idea.core.script.IdeScriptReportSink
import org.jetbrains.kotlin.idea.script.ScriptDiagnosticFixProvider
import org.jetbrains.kotlin.idea.util.application.isApplicationInternalMode
import org.jetbrains.kotlin.psi.KtFile
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode

class ScriptExternalHighlightingPass(
    private val file: KtFile,
    document: Document
) : TextEditorHighlightingPass(file.project, document), DumbAware {
    override fun doCollectInformation(progress: ProgressIndicator) = Unit

    override fun doApplyInformationToEditor() {
        val document = document

        if (!file.isScript()) return

        val reports = IdeScriptReportSink.getReports(file)

        val annotations = reports.mapNotNull { scriptDiagnostic ->
            val (startOffset, endOffset) = scriptDiagnostic.location?.let { computeOffsets(document, it) } ?: (0 to 0)
            val exception = scriptDiagnostic.exception
            val exceptionMessage = if (exception != null) " ($exception)" else ""
            @Suppress("HardCodedStringLiteral")
            val message = scriptDiagnostic.message + exceptionMessage
            val annotation = Annotation(
                startOffset,
                endOffset,
                scriptDiagnostic.severity.convertSeverity() ?: return@mapNotNull null,
                message,
                message
            )

            // if range is empty, show notification panel in editor
            annotation.isFileLevelAnnotation = startOffset == endOffset

            for (provider in ScriptDiagnosticFixProvider.EP_NAME.extensions) {
                provider.provideFixes(scriptDiagnostic).forEach {
                    annotation.registerFix(it)
                }
            }

            annotation
        }

        val infos = annotations.map { HighlightInfo.fromAnnotation(it) }
        UpdateHighlightersUtil.setHighlightersToEditor(myProject, myDocument, 0, file.textLength, infos, colorsScheme, id)
    }

    private fun computeOffsets(document: Document, position: SourceCode.Location): Pair<Int, Int> {
        val startLine = position.start.line.coerceLineIn(document)
        val startOffset = document.offsetBy(startLine, position.start.col)

        val endLine = position.end?.line?.coerceAtLeast(startLine)?.coerceLineIn(document) ?: startLine
        val endOffset = document.offsetBy(
            endLine,
            position.end?.col ?: document.getLineEndOffset(endLine)
        ).coerceAtLeast(startOffset)

        return startOffset to endOffset
    }

    private fun Int.coerceLineIn(document: Document) = coerceIn(0, document.lineCount - 1)

    private fun Document.offsetBy(line: Int, col: Int): Int {
        return (getLineStartOffset(line) + col).coerceIn(getLineStartOffset(line), getLineEndOffset(line))
    }

    private fun ScriptDiagnostic.Severity.convertSeverity(): HighlightSeverity? {
        return when (this) {
            ScriptDiagnostic.Severity.FATAL -> ERROR
            ScriptDiagnostic.Severity.ERROR -> ERROR
            ScriptDiagnostic.Severity.WARNING -> WARNING
            ScriptDiagnostic.Severity.INFO -> INFORMATION
            ScriptDiagnostic.Severity.DEBUG -> if (isApplicationInternalMode()) INFORMATION else null
        }
    }

    private fun showNotification(file: KtFile, @NlsContexts.PopupContent message: String) {
        UIUtil.invokeLaterIfNeeded {
            val ideFrame = WindowManager.getInstance().getIdeFrame(file.project)
            if (ideFrame != null) {
                val statusBar = ideFrame.statusBar as StatusBarEx
                statusBar.notifyProgressByBalloon(
                    MessageType.WARNING,
                    message,
                    null,
                    null
                )
            }
        }
    }

    class Registrar : TextEditorHighlightingPassFactoryRegistrar {
        override fun registerHighlightingPassFactory(registrar: TextEditorHighlightingPassRegistrar, project: Project) {
            registrar.registerTextEditorHighlightingPass(
                Factory(),
                TextEditorHighlightingPassRegistrar.Anchor.BEFORE,
                Pass.UPDATE_FOLDING,
                false,
                false
            )
        }
    }

    class Factory : TextEditorHighlightingPassFactory {
        override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
            if (file !is KtFile) return null
            return ScriptExternalHighlightingPass(file, editor.document)
        }
    }
}
