// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.structureView

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.Queryable
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithVisibility
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KtSymbolPointer
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.idea.structureView.AbstractKotlinStructureViewElement
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.psi.*
import javax.swing.Icon
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class KotlinFirStructureViewElement(
  val nElement: NavigatablePsiElement,
  ktElement : KtElement,
  private val isInherited: Boolean = false
) : PsiTreeElementBase<NavigatablePsiElement>(nElement), AbstractKotlinStructureViewElement, Queryable {

    private var kotlinPresentation
            by AssignableLazyProperty {
                KotlinFirStructureElementPresentation(isInherited, element, ktElement, countDescriptor())
            }

    var visibility
            by AssignableLazyProperty {
                analyze(ktElement) {
                    Visibility(countDescriptor()?.restoreSymbol())
                }
            }
        private set

    constructor(element: NavigatablePsiElement, inheritElement: KtElement, descriptor: KtSymbolPointer<*>, isInherited: Boolean) : this(element, inheritElement, isInherited) {
        if (element !is KtElement) {
            // Avoid storing descriptor in fields
            kotlinPresentation = KotlinFirStructureElementPresentation(isInherited, element, inheritElement, descriptor)
            analyze(inheritElement) {
                visibility = Visibility(descriptor.restoreSymbol())
            }

        }
    }

    override val accessLevel: Int?
        get() = visibility.accessLevel
    override val isPublic: Boolean
        get() = visibility.isPublic
    override val element: NavigatablePsiElement
        get() = nElement

    override fun getPresentation(): ItemPresentation = kotlinPresentation
    override fun getLocationString(): String? = kotlinPresentation.locationString
    override fun getIcon(open: Boolean): Icon? = kotlinPresentation.getIcon(open)
    override fun getPresentableText(): String? = kotlinPresentation.presentableText

    @TestOnly
    override fun putInfo(info: MutableMap<in String, in String?>) {
        // Sanity check for API consistency
        assert(presentation.presentableText == presentableText) { "Two different ways of getting presentableText" }
        assert(presentation.locationString == locationString) { "Two different ways of getting locationString" }

        info["text"] = presentableText
        info["location"] = locationString
    }

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        val children = when (val element = element) {
            is KtFile -> element.declarations
            is KtClass -> element.getStructureDeclarations()
            is KtClassOrObject -> element.declarations
            is KtFunction, is KtClassInitializer, is KtProperty -> element.collectLocalDeclarations()
            else -> emptyList()
        }

        return children.map { KotlinFirStructureViewElement(it, it, false) }
    }

    private fun PsiElement.collectLocalDeclarations(): List<KtDeclaration> {
        val result = mutableListOf<KtDeclaration>()

        acceptChildren(object : KtTreeVisitorVoid() {
            override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                result.add(classOrObject)
            }

            override fun visitNamedFunction(function: KtNamedFunction) {
                result.add(function)
            }
        })

        return result
    }

    private fun countDescriptor(): KtSymbolPointer<*>? {
        val element = element
        return when {
            !element.isValid -> null
            element !is KtDeclaration -> null
            element is KtAnonymousInitializer -> null
            else -> runReadAction {
              if (!DumbService.isDumb(element.getProject())) {
                analyze(element) {
                  element.getSymbol().createPointer()
                } 
              }
              else null
            }
        }
    }

    class Visibility(descriptor: KtSymbol?) {
        private val visibility = (descriptor as? KtSymbolWithVisibility)?.visibility

        val isPublic: Boolean
            get() = visibility == Visibilities.Public

        val accessLevel: Int?
            get() = when {
                visibility == Visibilities.Public -> 1
                visibility == Visibilities.Internal -> 2
                visibility == Visibilities.Protected -> 3
                visibility?.let { Visibilities.isPrivate(it) } == true -> 4
                else -> null
            }
    }
}
private class AssignableLazyProperty<in R, T : Any>(val init: () -> T) : ReadWriteProperty<R, T> {
    private var _value: T? = null

    override fun getValue(thisRef: R, property: KProperty<*>): T {
        return _value ?: init().also { _value = it }
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: T) {
        _value = value
    }
}

fun KtClassOrObject.getStructureDeclarations() =
    (primaryConstructor?.let { listOf(it) } ?: emptyList()) +
            primaryConstructorParameters.filter { it.hasValOrVar() } +
            declarations
