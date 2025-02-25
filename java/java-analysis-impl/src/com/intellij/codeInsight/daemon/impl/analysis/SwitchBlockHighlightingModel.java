// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.analysis;

import com.intellij.codeInsight.daemon.JavaErrorBundle;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.codeInsight.daemon.impl.actions.IntentionActionWithFixAllOption;
import com.intellij.codeInsight.daemon.impl.quickfix.QuickFixAction;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.QuickFixFactory;
import com.intellij.codeInsight.intention.impl.PriorityIntentionActionWrapper;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.DirectClassInheritorsSearch;
import com.intellij.psi.util.*;
import com.intellij.util.ObjectUtils;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.containers.SmartHashSet;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.SwitchUtils;
import com.siyeh.ig.psiutils.TypeUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.intellij.codeInsight.daemon.impl.analysis.SwitchBlockHighlightingModel.PatternsInSwitchBlockHighlightingModel.CompletenessResult.*;
import static com.intellij.psi.PsiModifier.ABSTRACT;
import static com.intellij.psi.PsiModifier.SEALED;

public class SwitchBlockHighlightingModel {
  @NotNull private final LanguageLevel myLevel;
  @NotNull final PsiSwitchBlock myBlock;
  @NotNull final PsiExpression mySelector;
  @NotNull final PsiType mySelectorType;
  @NotNull final PsiFile myFile;
  @NotNull final Object myDefaultValue = new Object();

  private SwitchBlockHighlightingModel(@NotNull LanguageLevel languageLevel,
                                       @NotNull PsiSwitchBlock switchBlock,
                                       @NotNull PsiFile psiFile) {
    myLevel = languageLevel;
    myBlock = switchBlock;
    mySelector = Objects.requireNonNull(myBlock.getExpression());
    mySelectorType = Objects.requireNonNull(mySelector.getType());
    myFile = psiFile;
  }

  @Nullable
  static SwitchBlockHighlightingModel createInstance(@NotNull LanguageLevel languageLevel,
                                                     @NotNull PsiSwitchBlock switchBlock,
                                                     @NotNull PsiFile psiFile) {
    PsiExpression selector = switchBlock.getExpression();
    if (selector == null) return null;
    PsiType selectorType = selector.getType();
    if (selectorType == null) return null;
    if (HighlightingFeature.PATTERNS_IN_SWITCH.isSufficient(languageLevel)) {
      return new PatternsInSwitchBlockHighlightingModel(languageLevel, switchBlock, psiFile);
    }
    return new SwitchBlockHighlightingModel(languageLevel, switchBlock, psiFile);
  }

  public static IntentionAction createAddDefaultFixIfNecessary(@NotNull PsiSwitchBlock block) {
    PsiFile file = block.getContainingFile();
    SwitchBlockHighlightingModel model = createInstance(PsiUtil.getLanguageLevel(file), block, file);
    if (model == null) return null;
    List<HighlightInfo> infos = model.checkSwitchLabelValues();
    if (infos.isEmpty()) return null;
    var templateFix = (IntentionActionWithFixAllOption)QuickFixFactory.getInstance().createAddSwitchDefaultFix(block, null);
    return StreamEx.of(infos).map(info -> info.getSameFamilyFix(templateFix)).nonNull().findFirst().orElse(null);
  }

  @NotNull
  List<HighlightInfo> checkSwitchBlockStatements() {
    PsiCodeBlock body = myBlock.getBody();
    if (body == null) return Collections.emptyList();
    PsiElement first = PsiTreeUtil.skipWhitespacesAndCommentsForward(body.getLBrace());
    if (first != null && !(first instanceof PsiSwitchLabelStatementBase) && !PsiUtil.isJavaToken(first, JavaTokenType.RBRACE)) {
      return Collections.singletonList(createError(first, JavaErrorBundle.message("statement.must.be.prepended.with.case.label")));
    }
    PsiElement element = first;
    PsiStatement alien = null;
    boolean classicLabels = false;
    boolean enhancedLabels = false;
    boolean levelChecked = false;
    while (element != null && !PsiUtil.isJavaToken(element, JavaTokenType.RBRACE)) {
      if (element instanceof PsiSwitchLabeledRuleStatement) {
        if (!levelChecked) {
          HighlightInfo info = HighlightUtil.checkFeature(element, HighlightingFeature.ENHANCED_SWITCH, myLevel, myFile);
          if (info != null) return Collections.singletonList(info);
          levelChecked = true;
        }
        if (classicLabels) {
          alien = (PsiStatement)element;
          break;
        }
        enhancedLabels = true;
      }
      else if (element instanceof PsiStatement) {
        if (enhancedLabels) {
          alien = (PsiStatement)element;
          break;
        }
        classicLabels = true;
      }

      if (!levelChecked && element instanceof PsiSwitchLabelStatementBase) {
        @Nullable PsiCaseLabelElementList values = ((PsiSwitchLabelStatementBase)element).getCaseLabelElementList();
        if (values != null && values.getElementCount() > 1) {
          HighlightInfo info = HighlightUtil.checkFeature(values, HighlightingFeature.ENHANCED_SWITCH, myLevel, myFile);
          if (info != null) return Collections.singletonList(info);
          levelChecked = true;
        }
      }

      element = PsiTreeUtil.skipWhitespacesAndCommentsForward(element);
    }
    if (alien == null) return Collections.emptyList();
    if (enhancedLabels && !(alien instanceof PsiSwitchLabelStatementBase)) {
      PsiSwitchLabeledRuleStatement previousRule = PsiTreeUtil.getPrevSiblingOfType(alien, PsiSwitchLabeledRuleStatement.class);
      HighlightInfo info = createError(alien, JavaErrorBundle.message("statement.must.be.prepended.with.case.label"));
      if (previousRule != null) {
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createWrapSwitchRuleStatementsIntoBlockFix(previousRule));
      }
      return Collections.singletonList(info);
    }
    return Collections.singletonList(createError(alien, JavaErrorBundle.message("different.case.kinds.in.switch")));
  }

  @NotNull
  List<HighlightInfo> checkSwitchSelectorType() {
    SelectorKind kind = getSwitchSelectorKind();
    if (kind == SelectorKind.INT) return Collections.emptyList();

    LanguageLevel requiredLevel = null;
    if (kind == SelectorKind.ENUM) requiredLevel = LanguageLevel.JDK_1_5;
    if (kind == SelectorKind.STRING) requiredLevel = LanguageLevel.JDK_1_7;

    if (kind == null || requiredLevel != null && !myLevel.isAtLeast(requiredLevel)) {
      boolean is7 = myLevel.isAtLeast(LanguageLevel.JDK_1_7);
      String expected = JavaErrorBundle.message(is7 ? "valid.switch.17.selector.types" : "valid.switch.selector.types");
      HighlightInfo info =
        createError(mySelector, JavaErrorBundle.message("incompatible.types", expected, JavaHighlightUtil.formatType(mySelectorType)));
      if (myBlock instanceof PsiSwitchStatement) {
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createConvertSwitchToIfIntention((PsiSwitchStatement)myBlock));
      }
      if (PsiType.LONG.equals(mySelectorType) || PsiType.FLOAT.equals(mySelectorType) || PsiType.DOUBLE.equals(mySelectorType)) {
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createAddTypeCastFix(PsiType.INT, mySelector));
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createWrapWithAdapterFix(PsiType.INT, mySelector));
      }
      if (requiredLevel != null) {
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createIncreaseLanguageLevelFix(requiredLevel));
      }
      return Collections.singletonList(info);
    }
    return checkIfAccessibleType();
  }

  @NotNull
  List<HighlightInfo> checkSwitchLabelValues() {
    PsiCodeBlock body = myBlock.getBody();
    if (body == null) return Collections.emptyList();

    MultiMap<Object, PsiElement> values = new MultiMap<>();
    List<HighlightInfo> results = new ArrayList<>();
    boolean hasDefaultCase = false;

    for (PsiStatement st : body.getStatements()) {
      if (!(st instanceof PsiSwitchLabelStatementBase)) continue;
      PsiSwitchLabelStatementBase labelStatement = (PsiSwitchLabelStatementBase)st;
      boolean defaultCase = labelStatement.isDefaultCase();
      if (defaultCase) {
        values.putValue(myDefaultValue, ObjectUtils.notNull(labelStatement.getFirstChild(), labelStatement));
        hasDefaultCase = true;
        continue;
      }
      PsiCaseLabelElementList labelElementList = labelStatement.getCaseLabelElementList();
      if (labelElementList == null) {
        continue;
      }
      for (PsiCaseLabelElement labelElement : labelElementList.getElements()) {
        PsiExpression expr = ObjectUtils.tryCast(labelElement, PsiExpression.class);
        // ignore patterns/case defaults. If they appear here, insufficient language level will be reported
        if (expr == null) continue;
        HighlightInfo result = HighlightUtil.checkAssignability(mySelectorType, expr.getType(), expr, expr);
        if (result != null) {
          results.add(result);
          continue;
        }
        Object value = null;
        if (expr instanceof PsiReferenceExpression) {
          String enumConstName = evaluateEnumConstantName((PsiReferenceExpression)expr);
          if (enumConstName != null) {
            value = enumConstName;
            HighlightInfo info = createQualifiedEnumConstantInfo((PsiReferenceExpression)expr);
            if (info != null) {
              results.add(info);
              continue;
            }
          }
        }
        if (value == null) {
          value = ConstantExpressionUtil.computeCastTo(expr, mySelectorType);
        }
        if (value == null) {
          results.add(createError(expr, JavaErrorBundle.message("constant.expression.required")));
          continue;
        }
        fillElementsToCheckDuplicates(values, expr);
      }
    }

    checkDuplicates(values, results);
    // todo replace with needToCheckCompleteness
    if (results.isEmpty() && myBlock instanceof PsiSwitchExpression && !hasDefaultCase) {
      PsiClass selectorClass = PsiUtil.resolveClassInClassTypeOnly(mySelectorType);
      if (selectorClass == null) {
        results.add(createCompletenessInfoForSwitch(!values.keySet().isEmpty()));
      }
      else {
        checkEnumCompleteness(selectorClass, ContainerUtil.map(values.keySet(), String::valueOf), results);
      }
    }

    return results;
  }

  @Nullable
  static String evaluateEnumConstantName(@NotNull PsiReferenceExpression expr) {
    PsiElement element = expr.resolve();
    if (element instanceof PsiEnumConstant) return ((PsiEnumConstant)element).getName();
    return null;
  }

  @Nullable
  static HighlightInfo createQualifiedEnumConstantInfo(@NotNull PsiReferenceExpression expr) {
    PsiElement qualifier = expr.getQualifier();
    if (qualifier == null) return null;
    HighlightInfo result = createError(expr, JavaErrorBundle.message("qualified.enum.constant.in.switch"));
    QuickFixAction.registerQuickFixAction(result, getFixFactory().createDeleteFix(qualifier, JavaErrorBundle.message(
      "qualified.enum.constant.in.switch.remove.fix")));
    return result;
  }

  static QuickFixFactory getFixFactory() {
    return QuickFixFactory.getInstance();
  }

  @NotNull
  List<HighlightInfo> checkIfAccessibleType() {
    PsiClass member = PsiUtil.resolveClassInClassTypeOnly(mySelectorType);
    if (member != null && !PsiUtil.isAccessible(member.getProject(), member, mySelector, null)) {
      String className = PsiFormatUtil.formatClass(member, PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_FQ_NAME);
      return Collections.singletonList(createError(mySelector, JavaErrorBundle.message("inaccessible.type", className)));
    }
    return Collections.emptyList();
  }

  void fillElementsToCheckDuplicates(@NotNull MultiMap<Object, PsiElement> elements, @NotNull PsiCaseLabelElement labelElement) {
    PsiExpression expr = ObjectUtils.tryCast(labelElement, PsiExpression.class);
    if (expr == null) return;
    if (expr instanceof PsiReferenceExpression) {
      String enumConstName = evaluateEnumConstantName((PsiReferenceExpression)expr);
      if (enumConstName != null) {
        elements.putValue(enumConstName,labelElement);
        return;
      }
    }
    Object value = ConstantExpressionUtil.computeCastTo(expr, mySelectorType);
    if (value != null) {
      elements.putValue(value, expr);
    }
  }

  final void checkDuplicates(@NotNull MultiMap<Object, PsiElement> values, @NotNull List<HighlightInfo> results) {
    for (Map.Entry<Object, Collection<PsiElement>> entry : values.entrySet()) {
      if (entry.getValue().size() <= 1) continue;
      Object duplicateKey = entry.getKey();
      for (PsiElement duplicateElement : entry.getValue()) {
        HighlightInfo info = createDuplicateInfo(duplicateKey, duplicateElement);
        results.add(info);
      }
    }
  }

  @Nullable
  HighlightInfo createDuplicateInfo(@Nullable Object duplicateKey, @NotNull PsiElement duplicateElement) {
    String description = duplicateKey == myDefaultValue ? JavaErrorBundle.message("duplicate.default.switch.label") :
                         JavaErrorBundle.message("duplicate.switch.label", duplicateKey);
    HighlightInfo info = createError(duplicateElement, description);
    PsiSwitchLabelStatementBase labelStatement = PsiTreeUtil.getParentOfType(duplicateElement, PsiSwitchLabelStatementBase.class);
    if (labelStatement != null && labelStatement.isDefaultCase()) {
      QuickFixAction.registerQuickFixAction(info, getFixFactory().createDeleteDefaultFix(myFile, info));
    }
    return info;
  }

  boolean needToCheckCompleteness(@NotNull List<PsiCaseLabelElement> elements) {
    return myBlock instanceof PsiSwitchExpression || myBlock instanceof PsiSwitchStatement && isEnhancedSwitch(elements);
  }

  private boolean isEnhancedSwitch(@NotNull List<PsiCaseLabelElement> labelElements) {
    if (getSwitchSelectorKind() == SelectorKind.CLASS_OR_ARRAY) return true;
    return ContainerUtil.exists(labelElements, st -> st instanceof PsiPattern || isNullType(st));
  }

  static boolean isNullType(@NotNull PsiElement element) {
    return element instanceof PsiExpression && TypeConversionUtil.isNullType(((PsiExpression)element).getType());
  }

  private static <T> List<T> dropFirst(List<T> list) {
    return list.subList(1, list.size());
  }

  void checkEnumCompleteness(@NotNull PsiClass selectorClass, @NotNull List<String> enumElements, @NotNull List<HighlightInfo> results) {
    LinkedHashSet<String> missingConstants =
      StreamEx.of(selectorClass.getFields()).select(PsiEnumConstant.class).map(PsiField::getName).toCollection(LinkedHashSet::new);
    if (!enumElements.isEmpty()) {
      enumElements.forEach(missingConstants::remove);
      if (missingConstants.isEmpty()) return;
    }
    HighlightInfo info = createCompletenessInfoForSwitch(!enumElements.isEmpty());
    if (!missingConstants.isEmpty()) {
      IntentionAction fix =
        PriorityIntentionActionWrapper.highPriority(getFixFactory().createAddMissingEnumBranchesFix(myBlock, missingConstants));
      QuickFixAction.registerQuickFixAction(info, fix);
    }
    results.add(info);
  }

  @Nullable
  HighlightInfo createCompletenessInfoForSwitch(boolean hasAnyCaseLabels) {
    String messageKey;
    boolean isSwitchExpr = myBlock instanceof PsiExpression;
    if (hasAnyCaseLabels) {
      messageKey = isSwitchExpr ? "switch.expr.incomplete" : "switch.statement.incomplete";
    }
    else {
      messageKey = isSwitchExpr ? "switch.expr.empty" : "switch.statement.empty";
    }
    HighlightInfo info = createError(mySelector, JavaErrorBundle.message(messageKey));
    QuickFixAction.registerQuickFixAction(info, getFixFactory().createAddSwitchDefaultFix(myBlock, null));
    return info;
  }

  @Nullable
  SelectorKind getSwitchSelectorKind() {
    if (TypeConversionUtil.getTypeRank(mySelectorType) <= TypeConversionUtil.INT_RANK) {
      return SelectorKind.INT;
    }
    PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(mySelectorType);
    if (psiClass != null) {
      if (psiClass.isEnum()) {
        return SelectorKind.ENUM;
      }
      if (Comparing.strEqual(psiClass.getQualifiedName(), CommonClassNames.JAVA_LANG_STRING)) {
        return SelectorKind.STRING;
      }
    }
    return null;
  }

  @Nullable
  private static HighlightInfo createError(@NotNull PsiElement range, @NlsSafe @NotNull String message) {
    return HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(range).descriptionAndTooltip(message).create();
  }

  private enum SelectorKind {INT, ENUM, STRING, CLASS_OR_ARRAY}

  private static @NotNull LinkedHashMap<PsiClass, PsiPattern> findPatternClasses(@NotNull List<? extends PsiCaseLabelElement> elements) {
    LinkedHashMap<PsiClass, PsiPattern> patternClasses = new LinkedHashMap<>();
    for (PsiCaseLabelElement element : elements) {
      PsiPattern patternLabelElement = ObjectUtils.tryCast(element, PsiPattern.class);
      if (patternLabelElement == null) continue;
      PsiClass patternClass = PsiUtil.resolveClassInClassTypeOnly(JavaPsiPatternUtil.getPatternType(element));
      if (patternClass != null) {
        patternClasses.put(patternClass, patternLabelElement);
      }
    }
    return patternClasses;
  }

  private static @NotNull Set<PsiClass> findMissedClasses(@NotNull PsiType selectorType,
                                                          LinkedHashMap<PsiClass, PsiPattern> patternClasses) {
    PsiClass selectorClass = PsiUtil.resolveClassInClassTypeOnly(selectorType);
    if (selectorClass == null) return Collections.emptySet();
    Queue<PsiClass> nonVisited = new ArrayDeque<>();
    nonVisited.add(selectorClass);
    Set<PsiClass> visited = new SmartHashSet<>();
    Set<PsiClass> missingClasses = new LinkedHashSet<>();
    while (!nonVisited.isEmpty()) {
      PsiClass psiClass = nonVisited.peek();
      if (psiClass.hasModifierProperty(SEALED) && (psiClass.hasModifierProperty(ABSTRACT) ||
                                                   psiClass.equals(selectorClass))) {
        for (PsiClass permittedClass : PatternsInSwitchBlockHighlightingModel.getPermittedClasses(psiClass)) {
          if (!visited.add(permittedClass)) continue;
          PsiPattern pattern = patternClasses.get(permittedClass);
          if (pattern == null && (PsiUtil.getLanguageLevel(permittedClass).isLessThan(LanguageLevel.JDK_18_PREVIEW) ||
                                  TypeConversionUtil.areTypesConvertible(selectorType, TypeUtils.getType(permittedClass))) ||
              pattern != null && !JavaPsiPatternUtil.isTotalForType(pattern, TypeUtils.getType(permittedClass), false)) {
            nonVisited.add(permittedClass);
          }
        }
      }
      else {
        visited.add(psiClass);
        missingClasses.add(psiClass);
      }
      nonVisited.poll();
    }
    if (!selectorClass.hasModifierProperty(ABSTRACT)) {
      missingClasses.add(selectorClass);
    }
    return missingClasses;
  }

  public static class PatternsInSwitchBlockHighlightingModel extends SwitchBlockHighlightingModel {
    private final Object myTotalPattern = new Object();

    PatternsInSwitchBlockHighlightingModel(@NotNull LanguageLevel languageLevel,
                                           @NotNull PsiSwitchBlock switchBlock,
                                           @NotNull PsiFile psiFile) {
      super(languageLevel, switchBlock, psiFile);
    }

    @NotNull
    @Override
    List<HighlightInfo> checkSwitchSelectorType() {
      SelectorKind kind = getSwitchSelectorKind();
      if (kind == SelectorKind.INT) return Collections.emptyList();
      if (kind == null) {
        HighlightInfo info = createError(mySelector, JavaErrorBundle.message("switch.invalid.selector.types",
                                                                             JavaHighlightUtil.formatType(mySelectorType)));
        if (myBlock instanceof PsiSwitchStatement) {
          QuickFixAction.registerQuickFixAction(info, getFixFactory().createConvertSwitchToIfIntention((PsiSwitchStatement)myBlock));
        }
        if (PsiType.LONG.equals(mySelectorType) || PsiType.FLOAT.equals(mySelectorType) || PsiType.DOUBLE.equals(mySelectorType)) {
          QuickFixAction.registerQuickFixAction(info, getFixFactory().createAddTypeCastFix(PsiType.INT, mySelector));
          QuickFixAction.registerQuickFixAction(info, getFixFactory().createWrapWithAdapterFix(PsiType.INT, mySelector));
        }
        return Collections.singletonList(info);
      }
      return checkIfAccessibleType();
    }

    @Override
    @Nullable
    SelectorKind getSwitchSelectorKind() {
      if (TypeConversionUtil.getTypeRank(mySelectorType) <= TypeConversionUtil.INT_RANK) return SelectorKind.INT;
      if (TypeConversionUtil.isPrimitiveAndNotNull(mySelectorType)) return null;
      PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(mySelectorType);
      if (psiClass != null) {
        if (psiClass.isEnum()) return SelectorKind.ENUM;
        String fqn = psiClass.getQualifiedName();
        if (Comparing.strEqual(fqn, CommonClassNames.JAVA_LANG_STRING)) return SelectorKind.STRING;
      }
      return SelectorKind.CLASS_OR_ARRAY;
    }

    @NotNull
    @Override
    List<HighlightInfo> checkSwitchLabelValues() {
      PsiCodeBlock body = myBlock.getBody();
      if (body == null) return Collections.emptyList();
      var elementsToCheckDuplicates = new MultiMap<Object, PsiElement>();
      List<List<PsiSwitchLabelStatementBase>> elementsToCheckFallThroughLegality = new SmartList<>();
      List<PsiCaseLabelElement> elementsToCheckDominance = new ArrayList<>();
      List<PsiCaseLabelElement> elementsToCheckCompleteness = new ArrayList<>();
      List<HighlightInfo> results = new SmartList<>();
      int switchBlockGroupCounter = 0;
      for (PsiStatement st : body.getStatements()) {
        if (!(st instanceof PsiSwitchLabelStatementBase)) continue;
        PsiSwitchLabelStatementBase labelStatement = (PsiSwitchLabelStatementBase)st;
        fillElementsToCheckFallThroughLegality(elementsToCheckFallThroughLegality, labelStatement, switchBlockGroupCounter);
        if (!(PsiTreeUtil.skipWhitespacesAndCommentsForward(labelStatement) instanceof PsiSwitchLabelStatement)) {
          switchBlockGroupCounter++;
        }
        if (labelStatement.isDefaultCase()) {
          elementsToCheckDuplicates.putValue(myDefaultValue, ObjectUtils.notNull(labelStatement.getFirstChild(), labelStatement));
          continue;
        }
        PsiCaseLabelElementList labelElementList = labelStatement.getCaseLabelElementList();
        if (labelElementList == null) continue;
        for (PsiCaseLabelElement labelElement : labelElementList.getElements()) {
          List<HighlightInfo> compatibilityInfo = checkLabelAndSelectorCompatibility(labelElement);
          if (!compatibilityInfo.isEmpty()) {
            results.addAll(compatibilityInfo);
            continue;
          }
          fillElementsToCheckDuplicates(elementsToCheckDuplicates, labelElement);
          fillElementsToCheckDominance(elementsToCheckDominance, labelElement);
          elementsToCheckCompleteness.add(labelElement);
        }
      }

      checkDuplicates(elementsToCheckDuplicates, results);
      if (!results.isEmpty()) return results;

      checkFallThroughFromToPattern(elementsToCheckFallThroughLegality, results);
      if (!results.isEmpty()) return results;

      checkDominance(elementsToCheckDominance, results);
      if (!results.isEmpty()) return results;

      if (needToCheckCompleteness(elementsToCheckCompleteness)) {
        checkCompleteness(elementsToCheckCompleteness, results, true);
      }
      return results;
    }

    private @NotNull List<HighlightInfo> checkLabelAndSelectorCompatibility(@NotNull PsiCaseLabelElement label) {
      if (label instanceof PsiDefaultCaseLabelElement) return Collections.emptyList();
      if (isNullType(label)) {
        if (mySelectorType instanceof PsiPrimitiveType && !isNullType(mySelector)) {
          HighlightInfo error = createError(label, JavaErrorBundle.message("incompatible.switch.null.type", "null",
                                                                           JavaHighlightUtil.formatType(mySelectorType)));
          return ContainerUtil.packNullables(error);
        }
        return Collections.emptyList();
      }
      else if (label instanceof PsiPatternGuard) {
        PsiPattern pattern = ((PsiPatternGuard)label).getPattern();
        return checkLabelAndSelectorCompatibility(pattern);
      }
      else if (label instanceof PsiPattern) {
        PsiPattern elementToReport = JavaPsiPatternUtil.getTypedPattern(label);
        if (elementToReport == null) return Collections.emptyList();
        PsiTypeElement typeElement = JavaPsiPatternUtil.getPatternTypeElement(elementToReport);
        if (typeElement == null) return Collections.emptyList();
        PsiType patternType = typeElement.getType();
        if (!(patternType instanceof PsiClassType) && !(patternType instanceof PsiArrayType)) {
          String expectedTypes = JavaErrorBundle.message("switch.class.or.array.type.expected");
          String message = JavaErrorBundle.message("unexpected.type", expectedTypes, JavaHighlightUtil.formatType(patternType));
          HighlightInfo info = createError(elementToReport, message);
          PsiPrimitiveType primitiveType = ObjectUtils.tryCast(patternType, PsiPrimitiveType.class);
          if (primitiveType != null) {
            IntentionAction fix = getFixFactory().createReplacePrimitiveWithBoxedTypeAction(mySelectorType, typeElement);
            QuickFixAction.registerQuickFixAction(info, fix);
          }
          return ContainerUtil.packNullables(info);
        }
        if (!TypeConversionUtil.areTypesConvertible(mySelectorType, patternType)) {
          HighlightInfo error =
            HighlightUtil.createIncompatibleTypeHighlightInfo(mySelectorType, patternType, elementToReport.getTextRange(), 0);
          return ContainerUtil.packNullables(error);
        }
        else if (JavaGenericsUtil.isUncheckedCast(patternType, mySelectorType)) {
          String message = JavaErrorBundle.message("unsafe.cast.in.instanceof", JavaHighlightUtil.formatType(mySelectorType),
                                                   JavaHighlightUtil.formatType(patternType));
          return ContainerUtil.packNullables(createError(elementToReport, message));
        }
        PsiDeconstructionPattern deconstructionPattern = JavaPsiPatternUtil.findDeconstructionPattern(elementToReport);
        return PatternHighlightingModel.createDeconstructionErrors(deconstructionPattern);
      }
      else if (label instanceof PsiExpression) {
        PsiExpression expr = (PsiExpression)label;
        HighlightInfo info = HighlightUtil.checkAssignability(mySelectorType, expr.getType(), expr, expr);
        if (info != null) return List.of(info);
        if (label instanceof PsiReferenceExpression) {
          String enumConstName = evaluateEnumConstantName((PsiReferenceExpression)label);
          if (enumConstName != null) {
            HighlightInfo error = createQualifiedEnumConstantInfo((PsiReferenceExpression)label);
            return ContainerUtil.packNullables(error);
          }
        }
        Object constValue = evaluateConstant(expr);
        if (constValue == null) {
          HighlightInfo error = createError(expr, JavaErrorBundle.message("constant.expression.required"));
          return ContainerUtil.packNullables(error);
        }
        if (ConstantExpressionUtil.computeCastTo(constValue, mySelectorType) == null) {
          HighlightInfo error = HighlightUtil.createIncompatibleTypeHighlightInfo(mySelectorType, expr.getType(), label.getTextRange(), 0);
          return ContainerUtil.packNullables(error);
        }
        return Collections.emptyList();
      }
      HighlightInfo error = createError(label, JavaErrorBundle.message("switch.constant.expression.required"));
      return ContainerUtil.packNullables(error);
    }

    @Override
    void fillElementsToCheckDuplicates(@NotNull MultiMap<Object, PsiElement> elements, @NotNull PsiCaseLabelElement labelElement) {
      if (labelElement instanceof PsiDefaultCaseLabelElement) {
        elements.putValue(myDefaultValue, labelElement);
      }
      else if (labelElement instanceof PsiExpression) {
        if (labelElement instanceof PsiReferenceExpression) {
          String enumConstName = evaluateEnumConstantName((PsiReferenceExpression)labelElement);
          if (enumConstName != null) {
            elements.putValue(enumConstName, labelElement);
            return;
          }
        }
        elements.putValue(evaluateConstant(labelElement), labelElement);
      }
      else if (labelElement instanceof PsiPattern && JavaPsiPatternUtil.isTotalForType(labelElement, mySelectorType)) {
        elements.putValue(myTotalPattern, labelElement);
      }
    }

    private static void fillElementsToCheckFallThroughLegality(@NotNull List<List<PsiSwitchLabelStatementBase>> elements,
                                                               @NotNull PsiSwitchLabelStatementBase labelStatement,
                                                               int switchBlockGroupCounter) {
      List<PsiSwitchLabelStatementBase> switchLabels;
      if (switchBlockGroupCounter < elements.size()) {
        switchLabels = elements.get(switchBlockGroupCounter);
      }
      else {
        switchLabels = new SmartList<>();
        elements.add(switchLabels);
      }
      switchLabels.add(labelStatement);
    }

    @NotNull
    private Map<PsiCaseLabelElement, PsiCaseLabelElement> findDominatedLabels(@NotNull List<PsiCaseLabelElement> switchLabels) {
      Map<PsiCaseLabelElement, PsiCaseLabelElement> result = new HashMap<>();
      for (int i = 0; i < switchLabels.size() - 1; i++) {
        PsiCaseLabelElement current = switchLabels.get(i);
        if (result.containsKey(current)) continue;
        for (int j = i + 1; j < switchLabels.size(); j++) {
          PsiCaseLabelElement next = switchLabels.get(j);
          if (isConstantLabelElement(next)) {
            PsiExpression constExpr = ObjectUtils.tryCast(next, PsiExpression.class);
            assert constExpr != null;
            if ((PsiUtil.getLanguageLevel(constExpr).isAtLeast(LanguageLevel.JDK_18_PREVIEW) ||
                 JavaPsiPatternUtil.isTotalForType(current, mySelectorType)) &&
                JavaPsiPatternUtil.dominates(current, constExpr.getType())) {
              result.put(next, current);
            }
            continue;
          }
          if (isNullType(next) && JavaPsiPatternUtil.isTotalForType(current, mySelectorType)
              && (PsiUtil.getLanguageLevel(next).isLessThan(LanguageLevel.JDK_19_PREVIEW))) {
            result.put(next, current);
            continue;
          }
          if (JavaPsiPatternUtil.dominates(current, next)) {
            result.put(next, current);
          }
        }
      }
      return result;
    }

    @Override
    @Nullable
    HighlightInfo createDuplicateInfo(@Nullable Object duplicateKey, @NotNull PsiElement duplicateElement) {
      String description;
      if (duplicateKey == myDefaultValue) {
        description = JavaErrorBundle.message("duplicate.default.switch.label");
      }
      else if (duplicateKey == myTotalPattern) {
        description = JavaErrorBundle.message("duplicate.total.pattern.label");
      }
      else {
        description = JavaErrorBundle.message("duplicate.switch.label", duplicateKey);
      }
      HighlightInfo info = createError(duplicateElement, description);
      PsiSwitchLabelStatementBase labelStatement = PsiTreeUtil.getParentOfType(duplicateElement, PsiSwitchLabelStatementBase.class);
      if (labelStatement != null && labelStatement.isDefaultCase()) {
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createDeleteDefaultFix(myFile, info));
      }
      else {
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createDeleteSwitchLabelFix((PsiCaseLabelElement)duplicateElement));
      }
      return info;
    }

    /**
     * 14.11.1 Switch Blocks
     * <ul>
     * To ensure safe initialization of pattern variables fall through rules in common provide the restrictions
     *  of using different type of case label switchLabel:
     * <li>patterns with patterns</li>
     * <li>patterns with constants</li>
     * <li>patterns with default</li>
     * </ul>
     */
    private static void checkFallThroughFromToPattern(@NotNull List<List<PsiSwitchLabelStatementBase>> switchBlockGroup,
                                                      @NotNull List<HighlightInfo> results) {
      if (switchBlockGroup.isEmpty()) return;
      Set<PsiElement> alreadyFallThroughElements = new HashSet<>();
      for (var switchLabel : switchBlockGroup) {
        boolean existPattern = false, existsTypeTestPattern = false, existsConst = false, existsNull = false, existsDefault = false;
        for (PsiSwitchLabelStatementBase switchLabelElement : switchLabel) {
          if (switchLabelElement.isDefaultCase()) {
            if (existPattern) {
              PsiElement defaultKeyword = switchLabelElement.getFirstChild();
              alreadyFallThroughElements.add(defaultKeyword);
              results.add(createError(defaultKeyword, JavaErrorBundle.message("switch.illegal.fall.through.from")));
            }
            existsDefault = true;
            continue;
          }
          PsiCaseLabelElementList labelElementList = switchLabelElement.getCaseLabelElementList();
          if (labelElementList == null) continue;
          for (PsiCaseLabelElement currentElement : labelElementList.getElements()) {
            if (currentElement instanceof PsiPattern || currentElement instanceof PsiPatternGuard) {
              if (currentElement instanceof PsiTypeTestPattern) {
                existsTypeTestPattern = true;
              }
              if (existPattern || existsConst || (existsNull && !existsTypeTestPattern) || existsDefault) {
                alreadyFallThroughElements.add(currentElement);
                results.add(createError(currentElement, JavaErrorBundle.message("switch.illegal.fall.through.to")));
              }
              existPattern = true;
            }
            else if (isNullType(currentElement)) {
              if (existPattern && !existsTypeTestPattern) {
                alreadyFallThroughElements.add(currentElement);
                results.add(createError(currentElement, JavaErrorBundle.message("switch.illegal.fall.through.from")));
              }
              existsNull = true;
            }
            else if (isConstantLabelElement(currentElement)) {
              if (existPattern) {
                alreadyFallThroughElements.add(currentElement);
                results.add(createError(currentElement, JavaErrorBundle.message("switch.illegal.fall.through.from")));
              }
              existsConst = true;
            }
            else if (currentElement instanceof PsiDefaultCaseLabelElement) {
              if (existPattern) {
                alreadyFallThroughElements.add(currentElement);
                results.add(createError(currentElement, JavaErrorBundle.message("switch.illegal.fall.through.from")));
              }
              existsDefault = true;
            }
          }
        }
      }
      checkFallThroughInSwitchLabels(switchBlockGroup, results, alreadyFallThroughElements);
    }

    private static void checkFallThroughInSwitchLabels(@NotNull List<List<PsiSwitchLabelStatementBase>> switchBlockGroup,
                                                       @NotNull List<HighlightInfo> results,
                                                       @NotNull Set<PsiElement> alreadyFallThroughElements) {
      for (int i = 1; i < switchBlockGroup.size(); i++) {
        List<PsiSwitchLabelStatementBase> switchLabels = switchBlockGroup.get(i);
        PsiSwitchLabelStatementBase firstSwitchLabelInGroup = switchLabels.get(0);
        for (PsiSwitchLabelStatementBase switchLabel : switchLabels) {
          if (!(switchLabel instanceof PsiSwitchLabelStatement)) return;
          PsiCaseLabelElementList labelElementList = switchLabel.getCaseLabelElementList();
          if (labelElementList == null) continue;
          var patternElements = ContainerUtil.filter(labelElementList.getElements(), labelElement -> labelElement instanceof PsiPattern);
          if (patternElements.isEmpty()) continue;
          PsiStatement prevStatement = PsiTreeUtil.getPrevSiblingOfType(firstSwitchLabelInGroup, PsiStatement.class);
          if (prevStatement == null) continue;
          if (ControlFlowUtils.statementMayCompleteNormally(prevStatement)) {
            patternElements.stream().filter(patternElement -> !alreadyFallThroughElements.contains(patternElement)).forEach(
              patternElement -> results.add(createError(patternElement, JavaErrorBundle.message("switch.illegal.fall.through.to"))));
          }
        }
      }
    }

    /**
     * 14.11.1 Switch Blocks
     * To ensure the absence of unreachable statements, domination rules provide a possible order
     * of different case label elements.
     * <p>
     * The dominance is based on pattern totality and dominance (14.30.3).
     *
     * @see JavaPsiPatternUtil#isTotalForType(PsiCaseLabelElement, PsiType)
     * @see JavaPsiPatternUtil#dominates(PsiCaseLabelElement, PsiCaseLabelElement)
     */
    private void checkDominance(@NotNull List<PsiCaseLabelElement> switchLabels, @NotNull List<HighlightInfo> results) {
      Map<PsiCaseLabelElement, PsiCaseLabelElement> dominatedLabels = findDominatedLabels(switchLabels);
      dominatedLabels.forEach((overWhom, who) -> {
        HighlightInfo info = createError(overWhom, JavaErrorBundle.message("switch.dominance.of.preceding.label", who.getText()));
        PsiPattern overWhomPattern = ObjectUtils.tryCast(overWhom, PsiPattern.class);
        PsiPattern whoPattern = ObjectUtils.tryCast(who, PsiPattern.class);
        if (whoPattern == null || !JavaPsiPatternUtil.dominates(overWhomPattern, whoPattern)) {
          QuickFixAction.registerQuickFixAction(info, getFixFactory().createMoveSwitchBranchUpFix(who, overWhom));
        }
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createDeleteSwitchLabelFix(overWhom));
        results.add(info);
      });
    }

    /**
     * 14.11.1 Switch Blocks
     * To ensure completeness and the absence of undescribed statements, different rules are provided
     * for enums, sealed and plain classes.
     * <p>
     * The completeness is based on pattern totality (14.30.3).
     *
     * @see JavaPsiPatternUtil#isTotalForType(PsiCaseLabelElement, PsiType)
     */
    private void checkCompleteness(@NotNull List<PsiCaseLabelElement> elements, @NotNull List<HighlightInfo> results,
                                   boolean inclusiveTotalAndDefault) {
      if (inclusiveTotalAndDefault) {
        PsiCaseLabelElement elementCoversType = findTotalPatternForType(elements, mySelectorType);
        PsiElement defaultElement = SwitchUtils.findDefaultElement(myBlock);
        if (defaultElement != null && elementCoversType != null) {
          HighlightInfo defaultInfo =
            createError(defaultElement.getFirstChild(), JavaErrorBundle.message("switch.total.pattern.and.default.exist"));
          registerDeleteFixForDefaultElement(defaultInfo, defaultElement);
          results.add(defaultInfo);
          HighlightInfo patternInfo = createError(elementCoversType, JavaErrorBundle.message("switch.total.pattern.and.default.exist"));
          QuickFixAction.registerQuickFixAction(patternInfo, getFixFactory().createDeleteSwitchLabelFix(elementCoversType));
          results.add(patternInfo);
          return;
        }
        if (defaultElement != null || elementCoversType != null) return;
      }
      PsiClass selectorClass = PsiUtil.resolveClassInClassTypeOnly(mySelectorType);
      if (selectorClass != null && getSwitchSelectorKind() == SelectorKind.ENUM) {
        List<String> enumElements = new SmartList<>();
        for (PsiCaseLabelElement labelElement : elements) {
          if (labelElement instanceof PsiReferenceExpression) {
            String enumConstName = evaluateEnumConstantName((PsiReferenceExpression)labelElement);
            if (enumConstName != null) {
              enumElements.add(enumConstName);
            }
          }
          else {
            enumElements.add(labelElement.getText());
          }
        }
        checkEnumCompleteness(selectorClass, enumElements, results);
      }
      else if (selectorClass != null && selectorClass.hasModifierProperty(SEALED)) {
        HighlightInfo info = checkSealedClassCompleteness(mySelectorType, elements);
        if (info != null) {
          results.add(info);
        }
        if (!checkRecordExhaustiveness(elements)) {
          results.add(createCompletenessInfoForSwitch(!elements.isEmpty()));
        }
      }
      else if (selectorClass != null && selectorClass.isRecord()) {
        if (!checkRecordExhaustiveness(elements)) {
          results.add(createCompletenessInfoForSwitch(!elements.isEmpty()));
        }
      }
      else {
        results.add(createCompletenessInfoForSwitch(!elements.isEmpty()));
      }
    }

    private static void fillElementsToCheckDominance(@NotNull List<PsiCaseLabelElement> elements,
                                                     @NotNull PsiCaseLabelElement labelElement) {
      if (labelElement instanceof PsiPattern || labelElement instanceof PsiPatternGuard) {
        elements.add(labelElement);
      }
      else if (labelElement instanceof PsiExpression) {
        if (isNullType(labelElement) || isConstantLabelElement(labelElement)) {
          elements.add(labelElement);
        }
      }
    }

    private void registerDeleteFixForDefaultElement(HighlightInfo info, PsiElement defaultElement) {
      if (defaultElement instanceof PsiCaseLabelElement) {
        QuickFixAction.registerQuickFixAction(info, getFixFactory().createDeleteSwitchLabelFix((PsiCaseLabelElement)defaultElement));
        return;
      }
      QuickFixAction.registerQuickFixAction(info, getFixFactory().createDeleteDefaultFix(myFile, info));
    }

    @Nullable
    private HighlightInfo checkSealedClassCompleteness(@NotNull PsiType selectorType,
                                                       @NotNull List<PsiCaseLabelElement> elements) {
      LinkedHashMap<PsiClass, PsiPattern> patternClasses = findPatternClasses(elements);
      Set<PsiClass> missingClasses = findMissedClasses(selectorType, patternClasses);
      if (missingClasses.isEmpty()) return null;
      HighlightInfo info = createCompletenessInfoForSwitch(!elements.isEmpty());
      List<String> allNames = collectLabelElementNames(elements, missingClasses, patternClasses);
      Set<String> missingCases = ContainerUtil.map2LinkedSet(missingClasses, PsiClass::getQualifiedName);
      IntentionAction fix = getFixFactory().createAddMissingSealedClassBranchesFix(myBlock, missingCases, allNames);
      QuickFixAction.registerQuickFixAction(info, fix);
      return info;
    }

    private static boolean checkRecordExhaustiveness(@NotNull List<? extends PsiCaseLabelElement> caseElements) {
      List<PsiDeconstructionPattern> deconstructions =
        ContainerUtil.mapNotNull(caseElements, element -> findUnconditionalDeconstruction(element));
      MultiMap<PsiType, PsiDeconstructionPattern> deconstructionGroups =
        ContainerUtil.groupBy(deconstructions, deconstruction -> deconstruction.getTypeElement().getType());

      for (Map.Entry<PsiType, Collection<PsiDeconstructionPattern>> entry : deconstructionGroups.entrySet()) {
        PsiType type = entry.getKey();

        PsiClassType.ClassResolveResult resolve = PsiUtil.resolveGenericsClassInType(type);
        PsiClass selectorClass = resolve.getElement();
        PsiSubstitutor substitutor = resolve.getSubstitutor();
        if (selectorClass == null) continue;
        List<PsiType> recordTypes =
          ContainerUtil.map(selectorClass.getRecordComponents(), component -> substitutor.substitute(component.getType()));

        List<List<PsiPattern>> deconstructionComponentsGroup = ContainerUtil.map(entry.getValue(), deconstruction -> {
          return Arrays.asList(deconstruction.getDeconstructionList().getDeconstructionComponents());
        });
        if (ContainerUtil.exists(deconstructionComponentsGroup, group -> group.size() != recordTypes.size())) {
          return true;
        }
        if (!isExhaustiveInGroup(recordTypes, deconstructionComponentsGroup)) { //todo check exhaustiveness only on completed
          return false;
        }
      }
      return true;
    }

    private static @Nullable PsiDeconstructionPattern findUnconditionalDeconstruction(PsiCaseLabelElement caseElement) {
      if (caseElement instanceof PsiParenthesizedPattern) {
        return findUnconditionalDeconstruction(((PsiParenthesizedPattern)caseElement).getPattern());
      }
      else if (caseElement instanceof PsiPatternGuard) {
        PsiPatternGuard guarded = (PsiPatternGuard)caseElement;
        Object constVal = ExpressionUtils.computeConstantExpression(guarded.getGuardingExpression());
        if (!Boolean.TRUE.equals(constVal)) return null;
        return findUnconditionalDeconstruction(((PsiPatternGuard)caseElement).getPattern());
      }
      else if (caseElement instanceof PsiDeconstructionPattern) {
        return ((PsiDeconstructionPattern)caseElement);
      }
      else {
        return null;
      }
    }

    private static boolean isExhaustiveInGroup(List<PsiType> recordTypes, List<List<PsiPattern>> deconstructions) {
      if (recordTypes.isEmpty()) return true;
      PsiType typeToCheck = recordTypes.get(0);

      MultiMap<PsiType, List<PsiPattern>> deconstructionGroups = ContainerUtil.groupBy(deconstructions, deconstructionComponents -> {
        return JavaPsiPatternUtil.getPatternType(deconstructionComponents.get(0));
      });

      List<Map.Entry<PsiType, Collection<List<PsiPattern>>>> exhaustiveGroups =
        ContainerUtil.filter(deconstructionGroups.entrySet(), deconstructionGroup -> {
          List<PsiPattern> firstElements = ContainerUtil.map(deconstructionGroup.getValue(), it -> it.get(0));
          if (ContainerUtil.exists(firstElements, pattern -> pattern instanceof PsiDeconstructionPattern)) {
            if (!checkRecordExhaustiveness(firstElements)) return false;
          }
          return isExhaustiveInGroup(
            SwitchBlockHighlightingModel.dropFirst(recordTypes),
            ContainerUtil.map(deconstructionGroup.getValue(), SwitchBlockHighlightingModel::dropFirst)
          );
        });

      if (exhaustiveGroups.isEmpty()) return false;
      List<PsiPattern> patterns = ContainerUtil.map(exhaustiveGroups, it -> it.getValue().iterator().next().get(0));
      if (ContainerUtil.exists(patterns, (pattern) -> JavaPsiPatternUtil.isTotalForType(pattern, typeToCheck, false))) {
        return true;
      }
      LinkedHashMap<PsiClass, PsiPattern> patternClasses = SwitchBlockHighlightingModel.findPatternClasses(patterns);
      return SwitchBlockHighlightingModel.findMissedClasses(typeToCheck, patternClasses).isEmpty();
    }

    @NotNull
    private static List<String> collectLabelElementNames(@NotNull List<PsiCaseLabelElement> elements,
                                                         @NotNull Set<PsiClass> missingClasses,
                                                         @NotNull LinkedHashMap<PsiClass, PsiPattern> patternClasses) {
      List<String> result = new ArrayList<>(ContainerUtil.map(elements, PsiElement::getText));
      for (PsiClass aClass : missingClasses) {
        String className = aClass.getQualifiedName();
        PsiPattern pattern = patternClasses.get(aClass);
        if (pattern != null) {
          result.add(result.lastIndexOf(pattern.getText()) + 1, className);
        }
        else {
          pattern = ContainerUtil.find(patternClasses.values(), who -> JavaPsiPatternUtil.isTotalForType(who, TypeUtils.getType(aClass)));
          if (pattern != null) {
            result.add(result.indexOf(pattern.getText()), aClass.getQualifiedName());
          }
          else {
            result.add(aClass.getQualifiedName());
          }
        }
      }
      return StreamEx.of(result).distinct().toList();
    }

    @NotNull
    private static Collection<PsiClass> getPermittedClasses(@NotNull PsiClass psiClass) {
      PsiReferenceList permitsList = psiClass.getPermitsList();
      if (permitsList == null) {
        TreeSet<PsiClass> result = new TreeSet<>(Comparator.comparing(aClass -> aClass.getName()));
        GlobalSearchScope fileScope = GlobalSearchScope.fileScope(psiClass.getContainingFile());
        result.addAll(DirectClassInheritorsSearch.search(psiClass, fileScope).findAll());
        return result;
      }
      return Stream.of(permitsList.getReferencedTypes()).map(type -> type.resolve()).filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Nullable
    private static PsiCaseLabelElement findTotalPatternForType(@NotNull List<PsiCaseLabelElement> labelElements, @NotNull PsiType type) {
      return ContainerUtil.find(labelElements, element ->
        element instanceof PsiPattern && JavaPsiPatternUtil.isTotalForType(element, type));
    }

    private static boolean isConstantLabelElement(@NotNull PsiCaseLabelElement labelElement) {
      return evaluateConstant(labelElement) != null || isEnumConstant(labelElement);
    }

    private static boolean isEnumConstant(@NotNull PsiCaseLabelElement element) {
      if (element instanceof PsiReferenceExpression) {
        PsiElement resolved = ((PsiReferenceExpression)element).resolve();
        return resolved instanceof PsiEnumConstant;
      }
      return false;
    }

    @Nullable
    private static Object evaluateConstant(@NotNull PsiCaseLabelElement constant) {
      return JavaPsiFacade.getInstance(constant.getProject()).getConstantEvaluationHelper().computeConstantExpression(constant, false);
    }

    /**
     * @return {@link CompletenessResult#UNEVALUATED}, if switch is incomplete and it produces a compilation error
     * (this is already covered by highlighting)
     * <p>{@link CompletenessResult#INCOMPLETE}, if selector type is not enum or reference type(except boxing primitives and String) or switch is incomplete
     * <p>{@link CompletenessResult#COMPLETE_WITH_TOTAL}, if switch is complete because a total pattern exists
     * <p>{@link CompletenessResult#COMPLETE_WITHOUT_TOTAL}, if switch is complete and doesn't contain a total pattern
     */
    @NotNull
    public static CompletenessResult evaluateSwitchCompleteness(@NotNull PsiSwitchBlock switchBlock) {
      SwitchBlockHighlightingModel switchModel = SwitchBlockHighlightingModel.createInstance(
        PsiUtil.getLanguageLevel(switchBlock), switchBlock, switchBlock.getContainingFile());
      if (switchModel == null) return UNEVALUATED;
      PsiCodeBlock switchBody = switchModel.myBlock.getBody();
      if (switchBody == null) return UNEVALUATED;
      List<PsiCaseLabelElement> labelElements = StreamEx.of(SwitchUtils.getSwitchBranches(switchBlock)).select(PsiCaseLabelElement.class)
        .filter(element -> !(element instanceof PsiDefaultCaseLabelElement)).toList();
      if (labelElements.isEmpty()) return UNEVALUATED;
      List<HighlightInfo> results = new SmartList<>();
      boolean needToCheckCompleteness = switchModel.needToCheckCompleteness(labelElements);
      boolean isEnumSelector = switchModel.getSwitchSelectorKind() == SelectorKind.ENUM;
      if (switchModel instanceof PatternsInSwitchBlockHighlightingModel) {
        if (findTotalPatternForType(labelElements, switchModel.mySelectorType) != null) return COMPLETE_WITH_TOTAL;
        if (!needToCheckCompleteness && !isEnumSelector) return INCOMPLETE;
        ((PatternsInSwitchBlockHighlightingModel)switchModel).checkCompleteness(labelElements, results, false);
      }
      else {
        if (!needToCheckCompleteness && !isEnumSelector) return INCOMPLETE;
        PsiClass selectorClass = PsiUtil.resolveClassInClassTypeOnly(switchModel.mySelector.getType());
        if (selectorClass == null || !selectorClass.isEnum()) return UNEVALUATED;
        List<PsiSwitchLabelStatementBase> labels =
          PsiTreeUtil.getChildrenOfTypeAsList(switchBlock.getBody(), PsiSwitchLabelStatementBase.class);
        List<String> enumConstants = StreamEx.of(labels).flatCollection(SwitchUtils::findEnumConstants).map(PsiField::getName).toList();
        switchModel.checkEnumCompleteness(selectorClass, enumConstants, results);
      }
      // if switch block is needed to check completeness and switch is incomplete, we let highlighting to inform about it as it's a compilation error
      if (needToCheckCompleteness) return results.isEmpty() ? COMPLETE_WITHOUT_TOTAL : UNEVALUATED;
      return results.isEmpty() ? COMPLETE_WITHOUT_TOTAL : INCOMPLETE;
    }

    public enum CompletenessResult {
      UNEVALUATED,
      INCOMPLETE,
      COMPLETE_WITH_TOTAL,
      COMPLETE_WITHOUT_TOTAL
    }
  }

  /**
   * @param switchBlock switch statement/expression to check
   * @return a set of label elements that are duplicates. If a switch block contains patterns,
   * then dominated label elements will be also included in the result set.
   */
  public static @NotNull Set<PsiElement> findSuspiciousLabelElements(@NotNull PsiSwitchBlock switchBlock) {
    var switchModel = createInstance(PsiUtil.getLanguageLevel(switchBlock), switchBlock, switchBlock.getContainingFile());
    if (switchModel == null) return Collections.emptySet();
    var labelElements = StreamEx.of(SwitchUtils.getSwitchBranches(switchBlock)).select(PsiCaseLabelElement.class).toList();
    if (labelElements.isEmpty()) return Collections.emptySet();
    MultiMap<Object, PsiElement> duplicateCandidates = new MultiMap<>();
    labelElements.forEach(branch -> switchModel.fillElementsToCheckDuplicates(duplicateCandidates, branch));

    Set<PsiElement> result = new SmartHashSet<>();

    for (Map.Entry<Object, Collection<PsiElement>> entry : duplicateCandidates.entrySet()) {
      if (entry.getValue().size() <= 1) continue;
      result.addAll(entry.getValue());
    }

    var patternInSwitchModel = ObjectUtils.tryCast(switchModel, PatternsInSwitchBlockHighlightingModel.class);
    if (patternInSwitchModel == null) return result;
    List<PsiCaseLabelElement> dominanceCheckingCandidates = new SmartList<>();
    labelElements.forEach(label -> PatternsInSwitchBlockHighlightingModel.fillElementsToCheckDominance(dominanceCheckingCandidates, label));
    if (dominanceCheckingCandidates.isEmpty()) return result;
    var dominatedPatterns = StreamEx.ofKeys(
      patternInSwitchModel.findDominatedLabels(dominanceCheckingCandidates), value -> value instanceof PsiPattern).toSet();
    result.addAll(dominatedPatterns);

    return result;
  }
}
