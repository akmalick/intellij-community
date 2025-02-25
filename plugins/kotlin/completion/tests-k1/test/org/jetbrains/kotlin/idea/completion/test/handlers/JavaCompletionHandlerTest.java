// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.completion.test.handlers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.idea.completion.test.CompletionTestUtilKt;
import org.jetbrains.kotlin.idea.completion.test.KotlinCompletionTestCase;
import org.junit.internal.runners.JUnit38ClassRunner;
import org.junit.runner.RunWith;

import java.io.File;
@RunWith(JUnit38ClassRunner.class)
public class JavaCompletionHandlerTest extends KotlinCompletionTestCase {
    public void testClassAutoImport() {
        doTest();
    }

    public void doTest() {
        String fileName = getTestName(false);
        try {
            configureByFiles(null, fileName + ".java", fileName + ".kt");
            complete(2);
            checkResultByFile(fileName + ".after.java");
        } catch (@SuppressWarnings("CaughtExceptionImmediatelyRethrown") AssertionError assertionError) {
            throw assertionError;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @NotNull
    @Override
    protected File getTestDataDirectory() {
        return new File(CompletionTestUtilKt.COMPLETION_TEST_DATA_BASE, "/handlers/injava");
    }
}
