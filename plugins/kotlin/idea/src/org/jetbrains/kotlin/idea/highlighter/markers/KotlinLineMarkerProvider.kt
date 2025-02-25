// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.highlighter.markers

import com.intellij.codeInsight.daemon.*
import com.intellij.codeInsight.daemon.impl.LineMarkerNavigator
import com.intellij.codeInsight.daemon.impl.MarkerType
import com.intellij.codeInsight.daemon.impl.PsiElementListNavigator
import com.intellij.codeInsight.navigation.BackgroundUpdaterTask
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.classes.KtFakeLightMethod
import org.jetbrains.kotlin.asJava.toFakeLightClass
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.idea.base.projectStructure.RootKindFilter
import org.jetbrains.kotlin.idea.base.projectStructure.matches
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.core.isInheritable
import org.jetbrains.kotlin.idea.core.isOverridable
import org.jetbrains.kotlin.idea.editor.fixers.startLine
import org.jetbrains.kotlin.idea.search.declarationsSearch.toPossiblyFakeLightMethods
import org.jetbrains.kotlin.idea.util.hasAtLeastOneActual
import org.jetbrains.kotlin.idea.util.hasMatchingExpected
import org.jetbrains.kotlin.idea.util.isEffectivelyActual
import org.jetbrains.kotlin.idea.util.isExpectDeclaration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.getPrevSiblingIgnoringWhitespaceAndComments
import java.awt.event.MouseEvent
import javax.swing.ListCellRenderer

class KotlinLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName() = KotlinBundle.message("highlighter.name.kotlin.line.markers")

    override fun getOptions(): Array<Option> = KotlinLineMarkerOptions.options

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        if (DaemonCodeAnalyzerSettings.getInstance().SHOW_METHOD_SEPARATORS) {
            if (element.canHaveSeparator()) {
                val prevSibling = element.getPrevSiblingIgnoringWhitespaceAndComments()
                if (prevSibling.canHaveSeparator() &&
                    (element.wantsSeparator() || prevSibling?.wantsSeparator() == true)
                ) {
                    return createLineSeparatorByElement(element)
                }
            }
        }

        return null
    }

    private fun PsiElement?.canHaveSeparator() =
        this is KtFunction
                || this is KtClassInitializer
                || (this is KtProperty && !isLocal)
                || ((this is KtObjectDeclaration && this.isCompanion()))

    private fun PsiElement.wantsSeparator() = this is KtFunction || StringUtil.getLineBreakCount(text) > 0

    private fun createLineSeparatorByElement(element: PsiElement): LineMarkerInfo<PsiElement> {
        val anchor = PsiTreeUtil.getDeepestFirst(element)

        val info = LineMarkerInfo(anchor, anchor.textRange)
        info.separatorColor = EditorColorsManager.getInstance().globalScheme.getColor(CodeInsightColors.METHOD_SEPARATORS_COLOR)
        info.separatorPlacement = SeparatorPlacement.TOP
        return info
    }

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: LineMarkerInfos) {
        if (elements.isEmpty()) return
        if (KotlinLineMarkerOptions.options.none { option -> option.isEnabled }) return

        val first = elements.first()
        if (DumbService.getInstance(first.project).isDumb || !RootKindFilter.projectAndLibrarySources.matches(first)) return

        val functions = hashSetOf<KtNamedFunction>()
        val properties = hashSetOf<KtNamedDeclaration>()
        val declarations = hashSetOf<KtNamedDeclaration>()

        for (leaf in elements) {
            ProgressManager.checkCanceled()
            if (leaf !is PsiIdentifier && leaf.firstChild != null) continue
            val element = leaf.parent as? KtNamedDeclaration ?: continue
            if (!declarations.add(element)) continue

            when (element) {
                is KtClass -> {
                    collectInheritedClassMarker(element, result)
                    collectHighlightingColorsMarkers(element, result)
                }
                is KtNamedFunction -> {
                    functions.add(element)
                    collectSuperDeclarationMarkers(element, result)
                }
                is KtProperty -> {
                    properties.add(element)
                    collectSuperDeclarationMarkers(element, result)
                }
                is KtParameter -> {
                    if (element.hasValOrVar()) {
                        properties.add(element)
                        collectSuperDeclarationMarkers(element, result)
                    }
                }
            }
            collectMultiplatformMarkers(element, result)
        }

        collectOverriddenFunctions(functions, result)
        collectOverriddenPropertyAccessors(properties, result)
    }
}

data class NavigationPopupDescriptor(
    val targets: Collection<NavigatablePsiElement>,
    @NlsContexts.PopupTitle val title: String,
    @NlsContexts.TabTitle val findUsagesTitle: String,
    val renderer: ListCellRenderer<in NavigatablePsiElement>,
    val updater: BackgroundUpdaterTask? = null
) {
    fun showPopup(e: MouseEvent) {
        PsiElementListNavigator.openTargets(e, targets.toTypedArray(), title, findUsagesTitle, renderer, updater)
    }
}

interface TestableLineMarkerNavigator {
    fun getTargetsPopupDescriptor(element: PsiElement?): NavigationPopupDescriptor?
}

val SUBCLASSED_CLASS = object : MarkerType(
    "SUBCLASSED_CLASS",
    { getPsiClass(it)?.let(::getModuleSpecificSubclassedClassTooltip) },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent, element: PsiElement?) {
            buildNavigateToClassInheritorsPopup(e, element)?.showPopup(e)
        }
    }) {
    override fun getNavigationHandler(): GutterIconNavigationHandler<PsiElement> {
        val superHandler = super.getNavigationHandler()
        return object : GutterIconNavigationHandler<PsiElement>, TestableLineMarkerNavigator {
            override fun navigate(e: MouseEvent?, elt: PsiElement?) {
                superHandler.navigate(e, elt)
            }

            override fun getTargetsPopupDescriptor(element: PsiElement?) = buildNavigateToClassInheritorsPopup(null, element)
        }
    }
}

val OVERRIDDEN_FUNCTION = object : MarkerType(
    "OVERRIDDEN_FUNCTION",
    { getPsiMethod(it)?.let(::getOverriddenMethodTooltip) },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            e?.let { buildNavigateToOverriddenMethodPopup(e, element)?.showPopup(e) }
        }
    }) {

    override fun getNavigationHandler(): GutterIconNavigationHandler<PsiElement> {
        val superHandler = super.getNavigationHandler()
        return object : GutterIconNavigationHandler<PsiElement>, TestableLineMarkerNavigator {
            override fun navigate(e: MouseEvent?, elt: PsiElement?) {
                superHandler.navigate(e, elt)
            }

            override fun getTargetsPopupDescriptor(element: PsiElement?) = buildNavigateToOverriddenMethodPopup(null, element)
        }
    }
}

val OVERRIDDEN_PROPERTY = object : MarkerType(
    "OVERRIDDEN_PROPERTY",
    { it?.let { getOverriddenPropertyTooltip(it.parent as KtNamedDeclaration) } },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            e?.let { buildNavigateToPropertyOverriddenDeclarationsPopup(e, element)?.showPopup(e) }
        }
    }) {

    override fun getNavigationHandler(): GutterIconNavigationHandler<PsiElement> {
        val superHandler = super.getNavigationHandler()
        return object : GutterIconNavigationHandler<PsiElement>, TestableLineMarkerNavigator {
            override fun navigate(e: MouseEvent?, elt: PsiElement?) {
                superHandler.navigate(e, elt)
            }

            override fun getTargetsPopupDescriptor(element: PsiElement?) = buildNavigateToPropertyOverriddenDeclarationsPopup(null, element)
        }
    }
}

val PsiElement.markerDeclaration
    get() = (this as? KtDeclaration) ?: (parent as? KtDeclaration)

private val PLATFORM_ACTUAL = object : MarkerType(
    "PLATFORM_ACTUAL",
    { element -> element?.markerDeclaration?.let { getPlatformActualTooltip(it) } },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            e?.let { buildNavigateToActualDeclarationsPopup(element)?.showPopup(e) }
        }
    }) {
    override fun getNavigationHandler(): GutterIconNavigationHandler<PsiElement> {
        val superHandler = super.getNavigationHandler()
        return object : GutterIconNavigationHandler<PsiElement>, TestableLineMarkerNavigator {
            override fun navigate(e: MouseEvent?, elt: PsiElement?) {
                superHandler.navigate(e, elt)
            }

            override fun getTargetsPopupDescriptor(element: PsiElement?): NavigationPopupDescriptor? {
                return buildNavigateToActualDeclarationsPopup(element)
            }
        }
    }
}

private val EXPECTED_DECLARATION = object : MarkerType(
    "EXPECTED_DECLARATION",
    { element -> element?.markerDeclaration?.let { getExpectedDeclarationTooltip(it) } },
    object : LineMarkerNavigator() {
        override fun browse(e: MouseEvent?, element: PsiElement?) {
            e?.let { buildNavigateToExpectedDeclarationsPopup(element)?.showPopup(e) }
        }
    }) {
    override fun getNavigationHandler(): GutterIconNavigationHandler<PsiElement> {
        val superHandler = super.getNavigationHandler()
        return object : GutterIconNavigationHandler<PsiElement>, TestableLineMarkerNavigator {
            override fun navigate(e: MouseEvent?, elt: PsiElement?) {
                superHandler.navigate(e, elt)
            }

            override fun getTargetsPopupDescriptor(element: PsiElement?): NavigationPopupDescriptor? {
                return buildNavigateToExpectedDeclarationsPopup(element)
            }
        }
    }
}

private fun isImplementsAndNotOverrides(
    descriptor: CallableMemberDescriptor,
    overriddenMembers: Collection<CallableMemberDescriptor>
): Boolean = descriptor.modality != Modality.ABSTRACT && overriddenMembers.all { it.modality == Modality.ABSTRACT }

private fun collectSuperDeclarationMarkers(declaration: KtDeclaration, result: LineMarkerInfos) {
    if (!(KotlinLineMarkerOptions.implementingOption.isEnabled || KotlinLineMarkerOptions.overridingOption.isEnabled)) return

    assert(declaration is KtNamedFunction || declaration is KtProperty || declaration is KtParameter)
    declaration as KtNamedDeclaration // implied by assert

    if (!declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return

    val resolveWithParents = resolveDeclarationWithParents(declaration)
    if (resolveWithParents.overriddenDescriptors.isEmpty()) return

    val implements = isImplementsAndNotOverrides(resolveWithParents.descriptor!!, resolveWithParents.overriddenDescriptors)

    val anchor = declaration.nameAnchor()

    // NOTE: Don't store descriptors in line markers because line markers are not deleted while editing other files and this can prevent
    // clearing the whole BindingTrace.

    val gutter = if (implements) KotlinLineMarkerOptions.implementingOption else KotlinLineMarkerOptions.overridingOption
    val lineMarkerInfo = LineMarkerInfo(
        anchor,
        anchor.textRange,
        gutter.icon!!,
        SuperDeclarationMarkerTooltip,
        SuperDeclarationMarkerNavigationHandler(),
        GutterIconRenderer.Alignment.RIGHT,
    ) { gutter.name }

    NavigateAction.setNavigateAction(
        lineMarkerInfo,
        if (declaration is KtNamedFunction)
            KotlinBundle.message("highlighter.action.text.go.to.super.method")
        else
            KotlinBundle.message("highlighter.action.text.go.to.super.property"),
        IdeActions.ACTION_GOTO_SUPER
    )
    result.add(lineMarkerInfo)
}

private fun collectInheritedClassMarker(element: KtClass, result: LineMarkerInfos) {
    if (!(KotlinLineMarkerOptions.implementedOption.isEnabled || KotlinLineMarkerOptions.overriddenOption.isEnabled)) return

    if (!element.isInheritable()) {
        return
    }

    val lightClass = element.toLightClass() ?: element.toFakeLightClass()

    if (ClassInheritorsSearch.search(lightClass, false).findFirst() == null) return

    val anchor = element.nameIdentifier ?: element
    val gutter = if (element.isInterface()) KotlinLineMarkerOptions.implementedOption else KotlinLineMarkerOptions.overriddenOption
    val icon = gutter.icon ?: return
    val lineMarkerInfo = LineMarkerInfo(
        anchor,
        anchor.textRange,
        icon,
        SUBCLASSED_CLASS.tooltip,
        SUBCLASSED_CLASS.navigationHandler,
        GutterIconRenderer.Alignment.RIGHT
    ) { gutter.name }

    NavigateAction.setNavigateAction(
        lineMarkerInfo,
        if (element.isInterface())
            KotlinBundle.message("highlighter.action.text.go.to.implementations")
        else
            KotlinBundle.message("highlighter.action.text.go.to.subclasses"),
        IdeActions.ACTION_GOTO_IMPLEMENTATION
    )
    result.add(lineMarkerInfo)
}

private fun collectOverriddenPropertyAccessors(
    properties: Collection<KtNamedDeclaration>,
    result: LineMarkerInfos
) {
    if (!(KotlinLineMarkerOptions.implementedOption.isEnabled || KotlinLineMarkerOptions.overriddenOption.isEnabled)) return

    val mappingToJava = HashMap<PsiElement, KtNamedDeclaration>()
    for (property in properties) {
        if (property.isOverridable()) {
            property.toPossiblyFakeLightMethods().forEach { mappingToJava[it] = property }
            mappingToJava[property] = property
        }
    }

    val classes = collectContainingClasses(mappingToJava.keys.filterIsInstance<PsiMethod>())

    for (property in getOverriddenDeclarations(mappingToJava, classes)) {
        ProgressManager.checkCanceled()

        val anchor = (property as? PsiNameIdentifierOwner)?.nameIdentifier ?: property
        val gutter = if (isImplemented(property)) KotlinLineMarkerOptions.implementedOption else KotlinLineMarkerOptions.overriddenOption
        val lineMarkerInfo = LineMarkerInfo(
            anchor,
            anchor.textRange,
            gutter.icon!!,
            OVERRIDDEN_PROPERTY.tooltip,
            OVERRIDDEN_PROPERTY.navigationHandler,
            GutterIconRenderer.Alignment.RIGHT,
        ) { gutter.name }

        NavigateAction.setNavigateAction(
            lineMarkerInfo,
            KotlinBundle.message("highlighter.action.text.go.to.overridden.properties"),
            IdeActions.ACTION_GOTO_IMPLEMENTATION
        )

        result.add(lineMarkerInfo)
    }
}

private val KtNamedDeclaration.expectOrActualAnchor
    get() = nameIdentifier ?: when (this) {
        is KtConstructor<*> -> getConstructorKeyword() ?: valueParameterList?.leftParenthesis
        is KtObjectDeclaration -> getObjectKeyword()
        else -> null
    } ?: this

private fun collectMultiplatformMarkers(
    declaration: KtNamedDeclaration,
    result: LineMarkerInfos
) {
    if (KotlinLineMarkerOptions.actualOption.isEnabled) {
        if (declaration.isExpectDeclaration()) {
            collectActualMarkers(declaration, result)
            return
        }
    }

    if (KotlinLineMarkerOptions.expectOption.isEnabled) {
        if (!declaration.isExpectDeclaration() && declaration.isEffectivelyActual()) {
            collectExpectedMarkers(declaration, result)
            return
        }
    }
}

private fun Document.areAnchorsOnOneLine(
    first: KtNamedDeclaration,
    second: KtNamedDeclaration?
): Boolean {
    if (second == null) return false
    val firstAnchor = first.expectOrActualAnchor
    val secondAnchor = second.expectOrActualAnchor
    return firstAnchor.startLine(this) == secondAnchor.startLine(this)
}

private fun KtNamedDeclaration.requiresNoMarkers(
    document: Document? = PsiDocumentManager.getInstance(project).getDocument(containingFile)
): Boolean {
    when (this) {
        is KtPrimaryConstructor -> return true

        is KtParameter,
        is KtEnumEntry -> {
            if (document?.areAnchorsOnOneLine(this, containingClassOrObject) == true) {
                return true
            }
            if (this is KtEnumEntry) {
                val enumEntries = containingClassOrObject?.body?.enumEntries.orEmpty()
                val previousEnumEntry = enumEntries.getOrNull(enumEntries.indexOf(this) - 1)
                if (document?.areAnchorsOnOneLine(this, previousEnumEntry) == true) {
                    return true
                }
            }
            if (this is KtParameter && hasValOrVar()) {
                val parameters = containingClassOrObject?.primaryConstructorParameters.orEmpty()
                val previousParameter = parameters.getOrNull(parameters.indexOf(this) - 1)
                if (document?.areAnchorsOnOneLine(this, previousParameter) == true) {
                    return true
                }
            }
        }
    }
    return false
}

internal fun KtDeclaration.findMarkerBoundDeclarations(): Sequence<KtNamedDeclaration> {
    if (this !is KtClass && this !is KtParameter) return emptySequence()
    val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)

    fun <T : KtNamedDeclaration> Sequence<T>.takeBound(bound: KtNamedDeclaration) = takeWhile {
        document?.areAnchorsOnOneLine(bound, it) == true
    }

    return when (this) {
        is KtParameter -> {
            val propertyParameters = takeIf { hasValOrVar() }?.containingClassOrObject
                ?.primaryConstructorParameters
                ?: return emptySequence()

            propertyParameters.asSequence().dropWhile {
                it !== this
            }.drop(1).takeBound(this).filter { it.hasValOrVar() }
        }

        is KtEnumEntry -> {
            val enumEntries = containingClassOrObject?.body?.enumEntries ?: return emptySequence()
            enumEntries.asSequence().dropWhile { it !== this }.drop(1).takeBound(this)
        }

        is KtClass -> {
            val boundParameters = primaryConstructor?.valueParameters
                ?.asSequence()
                ?.takeBound(this)
                ?.filter { it.hasValOrVar() }
                .orEmpty()

            val boundEnumEntries = this.takeIf { isEnum() }?.body?.enumEntries?.asSequence()?.takeBound(this).orEmpty()
            boundParameters + boundEnumEntries
        }
        else -> emptySequence()
    }
}

private fun collectActualMarkers(
    declaration: KtNamedDeclaration,
    result: LineMarkerInfos
) {
    val gutter = KotlinLineMarkerOptions.actualOption
    if (!gutter.isEnabled) return
    if (declaration.requiresNoMarkers()) return
    if (!declaration.hasAtLeastOneActual()) return

    val anchor = declaration.expectOrActualAnchor

    val lineMarkerInfo = LineMarkerInfo(
        anchor,
        anchor.textRange,
        gutter.icon!!,
        PLATFORM_ACTUAL.tooltip,
        PLATFORM_ACTUAL.navigationHandler,
        GutterIconRenderer.Alignment.RIGHT,
    ) { gutter.name }

    NavigateAction.setNavigateAction(
        lineMarkerInfo,
        KotlinBundle.message("highlighter.action.text.go.to.actual.declarations"),
        IdeActions.ACTION_GOTO_IMPLEMENTATION
    )
    result.add(lineMarkerInfo)
}

private fun collectExpectedMarkers(
    declaration: KtNamedDeclaration,
    result: LineMarkerInfos
) {
    if (!KotlinLineMarkerOptions.expectOption.isEnabled) return

    if (declaration.requiresNoMarkers()) return
    if (!declaration.hasMatchingExpected()) return

    val anchor = declaration.expectOrActualAnchor
    val gutter = KotlinLineMarkerOptions.expectOption
    val lineMarkerInfo = LineMarkerInfo(
        anchor,
        anchor.textRange,
        gutter.icon!!,
        EXPECTED_DECLARATION.tooltip,
        EXPECTED_DECLARATION.navigationHandler,
        GutterIconRenderer.Alignment.RIGHT,
    ) { gutter.name }

    NavigateAction.setNavigateAction(
        lineMarkerInfo,
        KotlinBundle.message("highlighter.action.text.go.to.expected.declaration"),
        null
    )
    result.add(lineMarkerInfo)
}

private fun collectOverriddenFunctions(functions: Collection<KtNamedFunction>, result: LineMarkerInfos) {
    if (!(KotlinLineMarkerOptions.implementedOption.isEnabled || KotlinLineMarkerOptions.overriddenOption.isEnabled)) {
        return
    }

    val mappingToJava = HashMap<PsiElement, KtNamedFunction>()
    for (function in functions) {
        if (function.isOverridable()) {
            val method = LightClassUtil.getLightClassMethod(function) ?: KtFakeLightMethod.get(function)
            if (method != null) {
                mappingToJava[method] = function
            }
            mappingToJava[function] = function
        }
    }

    val classes = collectContainingClasses(mappingToJava.keys.filterIsInstance<PsiMethod>())

    for (function in getOverriddenDeclarations(mappingToJava, classes)) {
        ProgressManager.checkCanceled()

        val anchor = function.nameAnchor()
        val gutter = if (isImplemented(function)) KotlinLineMarkerOptions.implementedOption else KotlinLineMarkerOptions.overriddenOption
        val lineMarkerInfo = LineMarkerInfo(
            anchor,
            anchor.textRange,
            gutter.icon!!,
            OVERRIDDEN_FUNCTION.tooltip,
            OVERRIDDEN_FUNCTION.navigationHandler,
            GutterIconRenderer.Alignment.RIGHT,
        ) { gutter.name }

        NavigateAction.setNavigateAction(
            lineMarkerInfo,
            KotlinBundle.message("highlighter.action.text.go.to.overridden.methods"),
            IdeActions.ACTION_GOTO_IMPLEMENTATION
        )

        result.add(lineMarkerInfo)
    }
}

private fun KtNamedDeclaration.nameAnchor(): PsiElement = nameIdentifier ?: PsiTreeUtil.getDeepestVisibleFirst(this) ?: this