// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeInsight.hints.RangeKtExpressionType.*
import org.jetbrains.kotlin.idea.codeInsight.hints.getRangeBinaryExpressionType
import org.jetbrains.kotlin.idea.codeinsight.api.classic.intentions.SelfTargetingOffsetIndependentIntention
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

class ConvertRangeCheckToTwoComparisonsIntention : SelfTargetingOffsetIndependentIntention<KtBinaryExpression>(
    KtBinaryExpression::class.java,
    KotlinBundle.lazyMessage("convert.to.comparisons")
) {
    private fun KtExpression?.isSimple() = this is KtConstantExpression || this is KtNameReferenceExpression

    override fun applyTo(element: KtBinaryExpression, editor: Editor?) {
        element.replace(convertToComparison(element)?.value ?: return)
    }

    override fun isApplicableTo(element: KtBinaryExpression) = convertToComparison(element) != null

    private fun convertToComparison(element: KtBinaryExpression): Lazy<KtExpression>? {
        if (element.operationToken != KtTokens.IN_KEYWORD) return null
        // ignore for-loop. for(x in 1..2) should not be convert to for(1<=x && x<=2)
        if (element.parent is KtForExpression) return null
        val rangeExpression = element.right ?: return null

        val arg = element.left ?: return null
        val (left, right) = rangeExpression.getArguments() ?: return null
        if (!arg.isSimple() || left?.isSimple() != true || right?.isSimple() != true) return null

        val pattern = when (rangeExpression.getRangeBinaryExpressionType()) {
            RANGE_TO -> "$0 <= $1 && $1 <= $2"
            UNTIL, RANGE_UNTIL -> "$0 <= $1 && $1 < $2"
            DOWN_TO -> "$0 >= $1 && $1 >= $2"
            null -> return null
        }

        return lazy { KtPsiFactory(element).createExpressionByPattern(pattern, left, arg, right, reformat = false) }
    }
}