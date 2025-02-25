/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.siyeh.ig.bugs;

import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.testFramework.LightProjectDescriptor;
import com.siyeh.ig.LightJavaInspectionTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * @author Bas Leijdekkers
 */
@SuppressWarnings("SuspiciousToArrayCall")
public class SuspiciousToArrayCallInspectionTest extends LightJavaInspectionTestCase {

  public void testCast() {
    doMemberTest("public void testThis(java.util.List l) {" +
                 "  final String[][] ss = (String[][]) l.toArray(new Number[l.size()]);" +
                 "}");
  }

  public void testParameterized() {
    doMemberTest("public void testThis(java.util.List<String> l) {" +
                 "  l.toArray(/*Array of type 'java.lang.String[]' expected, 'java.lang.Number[]' found*/new Number[l.size()]/**/);" +
                 "}");
  }

  public void testGenerics() {
    doTest("import java.util.*;" +
           "class K<T extends Integer> {\n" +
           "    List<T> list = new ArrayList<>();\n" +
           "\n" +
           "    String[] m() {\n" +
           "        return list.toArray(/*Array of type 'java.lang.Integer[]' expected, 'java.lang.String[]' found*/new String[list.size()]/**/);\n" +
           "    }\n" +
           "}");
  }

  public void testQuestionMark() {
    doTest("import java.util.List;\n" +
           "\n" +
           "class Test {\n" +
           "  Integer[] test(List<?> list) {\n" +
           "    return list.toArray(/*Array of type 'java.lang.Object[]' expected, 'java.lang.Integer[]' found*/new Integer[0]/**/);\n" +
           "  }\n" +
           "}");
  }

  public void testWrongGeneric() {
    doTest("import java.util.*;\n" +
           "\n" +
           "class Test {\n" +
           "  static class X<T> extends ArrayList<Integer> {}\n" +
           "  Integer[] test(X<Double> x) {\n" +
           "    return x.toArray(new Integer[0]);\n" +
           "  }\n" +
           "\n" +
           "  Double[] test2(X<Double> x) {\n" +
           "    return x.toArray(/*Array of type 'java.lang.Integer[]' expected, 'java.lang.Double[]' found*/new Double[0]/**/);\n" +
           "  }\n" +
           "}");
  }
  
  public void testStreams() {
    doTest("import java.util.stream.Stream;\n" +
           "class Test {\n" +
           "    static {\n" +
           "        Stream.of(1.0, 2.0, 3.0).toArray(/*Array of type 'java.lang.Double[]' expected, 'java.lang.Integer[]' found*/Integer[]::new/**/);\n" +
           "    }\n" +
           "}");
  }
  
  public void testStreamsParens() {
    doTest("import java.util.stream.Stream;\n" +
           "class Test {\n" +
           "    static {\n" +
           "        Stream.of(1.0, 2.0, 3.0).toArray(((/*Array of type 'java.lang.Double[]' expected, 'java.lang.Integer[]' found*/Integer[]::new/**/)));\n" +
           "    }\n" +
           "}");
  }
  
  public void testStreamsIntersection() {
    doTest("import java.util.stream.Stream;\n" +
           "class Test {\n" +
           "    static {\n" +
           "        Stream.of(1, 2.0, 3.0).toArray(/*Array of type 'java.lang.Number[]' expected, 'java.lang.Integer[]' found*/Integer[]::new/**/);\n" +
           "    }\n" +
           "}");
  }
  
  public void testToArrayGeneric() {
    doTest("import java.util.*;\n" +
           "class Test {\n" +
           "  static <A extends CharSequence> A[] toArray(List<CharSequence> cs) {\n" +
           "    //noinspection unchecked\n" +
           "    return (A[]) cs.stream().filter(Objects::nonNull).toArray(CharSequence[]::new);\n" +
           "  }\n" +
           "}");
  }
  
  public void testToArrayGeneric2() {
    doTest("import java.util.*;\n" +
           "class Test {\n" +
           "\n" +
           "    static <A extends CharSequence> A[] toArray2(List<CharSequence> cs) {\n" +
           "        //noinspection unchecked\n" +
           "        return (A[]) cs.toArray(new CharSequence[0]);\n" +
           "    }\n" +
           "}");
  }
  
  public void testCastNonRaw() {
    doTest("import java.util.*;\n" +
           "\n" +
           "class Test {\n" +
           "  void test() {\n" +
           "    List<Foo> list = new ArrayList<>();\n" +
           "    Bar[] arr2 = (Bar[])list.toArray(new Foo[0]);\n" +
           "  }\n" +
           "}\n" +
           "\n" +
           "class Foo {}\n" +
           "class Bar extends Foo {}");
  }

  public void testTypeParameter() {
    doTest("import java.util.stream.*;\n" +
           "import java.util.function.*;\n" +
           "abstract class AbstractStreamWrapper<T extends Cloneable> implements Stream<T> {\n" +
           "    private final Stream<T> delegate;\n" +
           "\n" +
           "    AbstractStreamWrapper(Stream<T> delegate) {\n" +
           "        this.delegate = delegate;\n" +
           "    }\n" +
           "\n" +
           "    @Override\n" +
           "    public <A> A[] toArray(IntFunction<A[]> generator) {\n" +
           "        return delegate.toArray(generator);\n" +
           "    }\n" +
           "    public <A extends CharSequence> A[] toArray2(IntFunction<A[]> generator) {\n" +
           "        return delegate.toArray(/*Array of type 'java.lang.Cloneable[]' expected, 'A[]' found*/generator/**/);\n" +
           "    }\n" +
           "    public <A extends Cloneable> A[] toArray3(IntFunction<A[]> generator) {\n" +
           "        return delegate.toArray(generator);\n" +
           "    }\n" +
           "}");
  }

  public void testStreamFilter() {
    doTest("import java.util.Arrays;\n" +
           "class Parent {}\n" +
           "class Child extends Parent {}\n" +
           "class Test {\n" +
           "   void test(Parent[] parent) {\n" +
           "     Child[] children = Arrays.stream(parent)\n" +
           "       .filter(t -> t instanceof Child)\n" +
           "       .toArray(Child[]::new);\n" +
           "     Child[] children2 = Arrays.stream(parent)\n" +
           "       .filter(Child.class::isInstance)\n" +
           "       .toArray(Child[]::new);\n" +
           "     Child[] children3 = Arrays.stream(parent)\n" +
           "       .filter(Child.class::isInstance)\n" +
           "       .filter(c -> c.hashCode() > 0)\n" +
           "       .toArray(Child[]::new);\n" +
           "   }\n" +
           "}");
  }

  @NotNull
  @Override
  protected LightProjectDescriptor getProjectDescriptor() {
    return JAVA_8;
  }

  @Override
  protected InspectionProfileEntry getInspection() {
    return new SuspiciousToArrayCallInspection();
  }
}
