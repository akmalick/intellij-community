// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import kotlin.math.min

class ToFromOriginalFileMapper private constructor(
    val originalFile: KtFile,
    val syntheticFile: KtFile,
    val completionOffset: Int
) {
    companion object {
        fun create(parameters: CompletionParameters): ToFromOriginalFileMapper {
            val originalFile = parameters.originalFile as KtFile
            val syntheticFile = parameters.position.containingFile as KtFile
            return ToFromOriginalFileMapper(originalFile, syntheticFile, parameters.offset)
        }
    }

    private val syntheticLength: Int
    private val originalLength: Int
    private val tailLength: Int
    private val shift: Int

    private val typeParamsOffset: Int
    private val typeParamsShift: Int

    //TODO: lazy initialization?

    init {
        val (originalText, syntheticText) = runReadAction {
            originalFile.text to syntheticFile.text
        }

        typeParamsOffset = (0 until completionOffset).firstOrNull { originalText[it] != syntheticText[it] } ?: 0
        if (typeParamsOffset > 0) {
            val typeParamsEnd = (typeParamsOffset until completionOffset).first { originalText[typeParamsOffset] == syntheticText[it] }
            typeParamsShift = typeParamsEnd - typeParamsOffset
        } else {
            typeParamsShift = 0
        }

        syntheticLength = syntheticText.length
        originalLength = originalText.length
        val minLength = min(originalLength, syntheticLength)
        tailLength = (0 until minLength).firstOrNull {
            syntheticText[syntheticLength - it - 1] != originalText[originalLength - it - 1]
        } ?: minLength
        shift = syntheticLength - originalLength
    }

    private fun toOriginalFile(offset: Int): Int? = when {
        offset <= typeParamsOffset -> offset
        offset in (typeParamsOffset + 1)..completionOffset -> offset - typeParamsShift
        offset >= originalLength - tailLength -> offset - shift - typeParamsShift
        else -> null
    }

    private fun toSyntheticFile(offset: Int): Int? = when {
        offset <= typeParamsOffset -> offset
        offset in (typeParamsOffset + 1)..completionOffset -> offset + typeParamsShift
        offset >= originalLength - tailLength -> offset + shift + typeParamsShift
        else -> null
    }

    fun <TElement : PsiElement> toOriginalFile(element: TElement): TElement? {
        if (element.containingFile != syntheticFile) return element
        val offset = toOriginalFile(element.startOffset) ?: return null
        return PsiTreeUtil.findElementOfClassAtOffset(originalFile, offset, element::class.java, true)
    }

    fun <TElement : PsiElement> toSyntheticFile(element: TElement): TElement? {
        if (element.containingFile != originalFile) return element
        val offset = toSyntheticFile(element.startOffset) ?: return null
        return PsiTreeUtil.findElementOfClassAtOffset(syntheticFile, offset, element::class.java, true)
    }
}
