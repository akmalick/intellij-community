/*
 * Copyright 2003-2021 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.controlflow;

import com.intellij.codeInspection.ui.SingleCheckboxOptionsPanel;
import com.intellij.psi.*;
import com.intellij.psi.util.FileTypeUtils;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.fixes.DeleteUnnecessaryStatementFix;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class UnnecessaryContinueInspection extends BaseInspection {

  @SuppressWarnings("PublicField")
  public boolean ignoreInThenBranch = false;

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message("unnecessary.continue.problem.descriptor");
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Override
  public JComponent createOptionsPanel() {
    return new SingleCheckboxOptionsPanel(InspectionGadgetsBundle.message("unnecessary.return.option"), this, "ignoreInThenBranch");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new UnnecessaryContinueVisitor();
  }

  @Override
  public boolean shouldInspect(@NotNull PsiFile file) {
    return !FileTypeUtils.isInServerPageFile(file);
  }

  @Override
  public InspectionGadgetsFix buildFix(Object... infos) {
    return new DeleteUnnecessaryStatementFix("continue");
  }

  private class UnnecessaryContinueVisitor extends BaseInspectionVisitor {

    @Override
    public void visitContinueStatement(@NotNull PsiContinueStatement statement) {
      final PsiStatement continuedStatement = statement.findContinuedStatement();
      PsiStatement body = null;
      if (continuedStatement instanceof PsiLoopStatement) {
        final PsiLoopStatement loopStatement = (PsiLoopStatement)continuedStatement;
        body = loopStatement.getBody();
      }
      if (body == null) {
        return;
      }
      if (ignoreInThenBranch && UnnecessaryReturnInspection.isInThenBranch(statement)) {
        return;
      }
      if (body instanceof PsiBlockStatement) {
        final PsiBlockStatement blockStatement = (PsiBlockStatement)body;
        final PsiCodeBlock block = blockStatement.getCodeBlock();
        if (ControlFlowUtils.blockCompletesWithStatement(block, statement)) {
          registerError(statement.getFirstChild());
        }
      }
      else if (ControlFlowUtils.statementCompletesWithStatement(body, statement)) {
        registerError(statement.getFirstChild());
      }
    }
  }
}