// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.classic.quickfixes.KotlinQuickFixAction
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

object RestrictedRetentionForExpressionAnnotationFactory : KotlinIntentionActionsFactory() {

    private val sourceRetention = "${StandardNames.FqNames.annotationRetention.asString()}.${AnnotationRetention.SOURCE.name}"
    private val sourceRetentionAnnotation = "@${StandardNames.FqNames.retention.asString()}($sourceRetention)"

    override fun doCreateActions(diagnostic: Diagnostic): List<IntentionAction> {
        val annotationEntry = diagnostic.psiElement as? KtAnnotationEntry ?: return emptyList()
        val containingClass = annotationEntry.containingClass() ?: return emptyList()
        val retentionAnnotation = containingClass.annotation(StandardNames.FqNames.retention)
        val targetAnnotation = containingClass.annotation(StandardNames.FqNames.target)
        val expressionTargetArgument = if (targetAnnotation != null) findExpressionTargetArgument(targetAnnotation) else null

        return listOfNotNull(
            if (expressionTargetArgument != null) RemoveExpressionTargetFix(expressionTargetArgument) else null,
            if (retentionAnnotation == null) AddSourceRetentionFix(containingClass) else ChangeRetentionToSourceFix(retentionAnnotation)
        )
    }

    private fun KtClass.annotation(fqName: FqName): KtAnnotationEntry? {
        return annotationEntries.firstOrNull {
            it.typeReference?.text?.endsWith(fqName.shortName().asString()) == true
                    && analyze()[BindingContext.TYPE, it.typeReference]?.constructor?.declarationDescriptor?.fqNameSafe == fqName
        }
    }

    private fun findExpressionTargetArgument(targetAnnotation: KtAnnotationEntry): KtValueArgument? {
        val valueArgumentList = targetAnnotation.valueArgumentList ?: return null
        if (targetAnnotation.lambdaArguments.isNotEmpty()) return null

        for (valueArgument in valueArgumentList.arguments) {
            val argumentExpression = valueArgument.getArgumentExpression() ?: continue
            if (argumentExpression.text.contains(KotlinTarget.EXPRESSION.toString())) {
                return valueArgument
            }
        }

        return null
    }

    private class AddSourceRetentionFix(element: KtClass) : KotlinQuickFixAction<KtClass>(element) {
        override fun getText() = KotlinBundle.message("add.source.retention")

        override fun getFamilyName() = text

        override fun invoke(project: Project, editor: Editor?, file: KtFile) {
            val element = element ?: return
            val added = element.addAnnotationEntry(KtPsiFactory(element).createAnnotationEntry(sourceRetentionAnnotation))
            ShortenReferences.DEFAULT.process(added)
        }
    }

    private class ChangeRetentionToSourceFix(retentionAnnotation: KtAnnotationEntry) :
      KotlinQuickFixAction<KtAnnotationEntry>(retentionAnnotation) {

        override fun getText() = KotlinBundle.message("change.existent.retention.to.source")

        override fun getFamilyName() = text

        override fun invoke(project: Project, editor: Editor?, file: KtFile) {
            val retentionAnnotation = element ?: return
            val psiFactory = KtPsiFactory(retentionAnnotation)
            val added = if (retentionAnnotation.valueArgumentList == null) {
                retentionAnnotation.add(psiFactory.createCallArguments("($sourceRetention)")) as KtValueArgumentList
            } else {
                if (retentionAnnotation.valueArguments.isNotEmpty()) {
                    retentionAnnotation.valueArgumentList?.removeArgument(0)
                }
                retentionAnnotation.valueArgumentList?.addArgument(psiFactory.createArgument(sourceRetention))
            }
            if (added != null) {
                ShortenReferences.DEFAULT.process(added)
            }
        }
    }

    private class RemoveExpressionTargetFix(expressionTargetArgument: KtValueArgument) :
      KotlinQuickFixAction<KtValueArgument>(expressionTargetArgument) {

        override fun getText() = KotlinBundle.message("remove.expression.target")

        override fun getFamilyName() = text

        override fun invoke(project: Project, editor: Editor?, file: KtFile) {
            val expressionTargetArgument = element ?: return
            val argumentList = expressionTargetArgument.parent as? KtValueArgumentList ?: return

            if (argumentList.arguments.size == 1) {
                val annotation = argumentList.parent as? KtAnnotationEntry ?: return
                annotation.delete()
            } else {
                argumentList.removeArgument(expressionTargetArgument)
            }
        }
    }
}
