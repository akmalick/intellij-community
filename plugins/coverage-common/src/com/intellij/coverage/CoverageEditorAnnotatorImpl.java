// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.coverage;

import com.intellij.history.FileRevisionTimestampComparator;
import com.intellij.history.LocalHistory;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.LineTokenizer;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.actions.VcsContextFactory;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsHistoryProvider;
import com.intellij.openapi.vcs.history.VcsHistorySession;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.reference.SoftReference;
import com.intellij.rt.coverage.data.ClassData;
import com.intellij.rt.coverage.data.LineCoverage;
import com.intellij.rt.coverage.data.LineData;
import com.intellij.rt.coverage.data.ProjectData;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.util.Alarm;
import com.intellij.util.Function;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import com.intellij.vcsUtil.VcsUtil;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

/**
 * @author ven
 */
public final class CoverageEditorAnnotatorImpl implements CoverageEditorAnnotator, Disposable {
  private static final Logger LOG = Logger.getInstance(CoverageEditorAnnotatorImpl.class);
  public static final Key<List<RangeHighlighter>> COVERAGE_HIGHLIGHTERS = Key.create("COVERAGE_HIGHLIGHTERS");
  private static final Key<DocumentListener> COVERAGE_DOCUMENT_LISTENER = Key.create("COVERAGE_DOCUMENT_LISTENER");
  public static final Key<Map<FileEditor, EditorNotificationPanel>> NOTIFICATION_PANELS = Key.create("NOTIFICATION_PANELS");

  private PsiFile myFile;
  private Editor myEditor;
  private Document myDocument;
  private final Project myProject;

  private SoftReference<Int2IntMap> myNewToOldLines;
  private SoftReference<Int2IntMap> myOldToNewLines;
  private SoftReference<byte[]> myOldContent;
  private final static Object LOCK = new Object();

  private final Alarm myUpdateAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);

  public CoverageEditorAnnotatorImpl(final PsiFile file, final Editor editor) {
    myFile = file;
    myEditor = editor;
    myProject = file.getProject();
    myDocument = myEditor.getDocument();
  }

  @Override
  public void hideCoverage() {
    Editor editor = myEditor;
    PsiFile file = myFile;
    Document document = myDocument;
    if (editor == null || editor.isDisposed() || file == null || document == null) return;
    final FileEditorManager fileEditorManager = FileEditorManager.getInstance(myProject);
    final List<RangeHighlighter> highlighters = editor.getUserData(COVERAGE_HIGHLIGHTERS);
    if (highlighters != null) {
      for (final RangeHighlighter highlighter : highlighters) {
        ApplicationManager.getApplication().invokeLater(() -> highlighter.dispose());
      }
      editor.putUserData(COVERAGE_HIGHLIGHTERS, null);
    }

    final Map<FileEditor, EditorNotificationPanel> map = file.getCopyableUserData(NOTIFICATION_PANELS);
    if (map != null) {
      final VirtualFile vFile = getVirtualFile(file);
      boolean freeAll = !fileEditorManager.isFileOpen(vFile);
      file.putCopyableUserData(NOTIFICATION_PANELS, null);
      for (FileEditor fileEditor : map.keySet()) {
        if (!freeAll && !isCurrentEditor(fileEditor)) {
          continue;
        }
        fileEditorManager.removeTopComponent(fileEditor, map.get(fileEditor));
      }
    }


    final DocumentListener documentListener = editor.getUserData(COVERAGE_DOCUMENT_LISTENER);
    if (documentListener != null) {
      document.removeDocumentListener(documentListener);
      editor.putUserData(COVERAGE_DOCUMENT_LISTENER, null);
    }
  }

  private static String @NotNull [] getCoveredLines(byte @NotNull [] oldContent, VirtualFile vFile) {
    final String text = LoadTextUtil.getTextByBinaryPresentation(oldContent, vFile, false, false).toString();
    return LineTokenizer.tokenize(text, false);
  }

  private static String @NotNull [] getUpToDateLines(final Document document) {
    final Ref<String[]> linesRef = new Ref<>();
    final Runnable runnable = () -> {
      final int lineCount = document.getLineCount();
      final String[] lines = new String[lineCount];
      final CharSequence chars = document.getCharsSequence();
      for (int i = 0; i < lineCount; i++) {
        lines[i] = chars.subSequence(document.getLineStartOffset(i), document.getLineEndOffset(i)).toString();
      }
      linesRef.set(lines);
    };
    ApplicationManager.getApplication().runReadAction(runnable);

    return linesRef.get();
  }

  private static Int2IntMap getCoverageVersionToCurrentLineMapping(Diff.Change change, int firstNLines) {
    Int2IntMap result = new Int2IntOpenHashMap();
    int prevLineInFirst = 0;
    int prevLineInSecond = 0;
    while (change != null) {

      for (int l = 0; l < change.line0 - prevLineInFirst; l++) {
        result.put(prevLineInFirst + l, prevLineInSecond + l);
      }

      prevLineInFirst = change.line0 + change.deleted;
      prevLineInSecond = change.line1 + change.inserted;

      change = change.link;
    }

    for (int i = prevLineInFirst; i < firstNLines; i++) {
      result.put(i, prevLineInSecond + i - prevLineInFirst);
    }

    return result;
  }

  @Nullable
  private Int2IntMap getOldToNewLineMapping(final long date, MyEditorBean editorBean) {
    if (myOldToNewLines == null) {
      myOldToNewLines = doGetLineMapping(date, true, editorBean);
      if (myOldToNewLines == null) return null;
    }
    return myOldToNewLines.get();
  }

  @Nullable
  private Int2IntMap getNewToOldLineMapping(final long date, MyEditorBean editorBean) {
    if (myNewToOldLines == null) {
      myNewToOldLines = doGetLineMapping(date, false, editorBean);
      if (myNewToOldLines == null) return null;
    }
    return myNewToOldLines.get();
  }

  private @Nullable SoftReference<Int2IntMap> doGetLineMapping(final long date, boolean oldToNew, MyEditorBean editorBean) {
    VirtualFile virtualFile = editorBean.getVFile();
    if (myOldContent == null && ApplicationManager.getApplication().isDispatchThread()) return null;
    final byte[] oldContent;
    synchronized (LOCK) {
      if (myOldContent == null) {
        final LocalHistory localHistory = LocalHistory.getInstance();
        byte[] byteContent = localHistory.getByteContent(virtualFile, new FileRevisionTimestampComparator() {
          @Override
          public boolean isSuitable(long revisionTimestamp) {
            return revisionTimestamp < date;
          }
        });

        if (byteContent == null && virtualFile.getTimeStamp() > date) {
          byteContent = loadFromVersionControl(date, virtualFile);
        }
        myOldContent = new SoftReference<>(byteContent);
      }
      oldContent = myOldContent.get();
    }

    if (oldContent == null) return null;
    String[] coveredLines = getCoveredLines(oldContent, virtualFile);
    final Document document = editorBean.getDocument();
    if (document == null) return null;
    String[] currentLines = getUpToDateLines(document);

    String[] oldLines = oldToNew ? coveredLines : currentLines;
    String[] newLines = oldToNew ? currentLines : coveredLines;

    Diff.Change change;
    try {
      change = Diff.buildChanges(oldLines, newLines);
    }
    catch (FilesTooBigForDiffException e) {
      LOG.info(e);
      return null;
    }
    return new SoftReference<>(getCoverageVersionToCurrentLineMapping(change, oldLines.length));
  }

  private byte @Nullable [] loadFromVersionControl(long date, VirtualFile f) {
    try {
      final AbstractVcs vcs = VcsUtil.getVcsFor(myProject, f);
      if (vcs == null) return null;

      final VcsHistoryProvider historyProvider = vcs.getVcsHistoryProvider();
      if (historyProvider == null) return null;

      final FilePath filePath = VcsContextFactory.getInstance().createFilePathOn(f);
      final VcsHistorySession session = historyProvider.createSessionFor(filePath);
      if (session == null) return null;

      final List<VcsFileRevision> list = session.getRevisionList();

      if (list != null) {
        for (VcsFileRevision revision : list) {
          final Date revisionDate = revision.getRevisionDate();
          if (revisionDate == null) {
            return null;
          }

          if (revisionDate.getTime() < date) {
            return revision.loadContent();
          }
        }
      }
    }
    catch (Exception e) {
      LOG.info(e);
      return null;
    }
    return null;
  }

  @Override
  public void showCoverage(final CoverageSuitesBundle suite) {
    // Store the values of myFile and myEditor in local variables to avoid an NPE after dispose() has been called in the EDT.
    final PsiFile psiFile = myFile;
    final Editor editor = myEditor;
    final Document document = myDocument;
    if (editor == null || psiFile == null || document == null) return;
    final VirtualFile file = getVirtualFile(psiFile);
    if (file == null || !file.isValid()) return;
    final MyEditorBean editorBean = new MyEditorBean(editor, file, document);
    final MarkupModel markupModel = DocumentMarkupModel.forDocument(document, myProject, true);
    final List<RangeHighlighter> highlighters = new ArrayList<>();
    final ProjectData data = suite.getCoverageData();
    if (data == null) {
      coverageDataNotFound(suite);
      return;
    }
    final CoverageEngine engine = suite.getCoverageEngine();
    final Set<String> qualifiedNames = engine.getQualifiedNames(psiFile);

    // let's find old content in local history and build mapping from old lines to new one
    // local history doesn't index libraries, so let's distinguish libraries content with other one
    final long fileTimeStamp = file.getTimeStamp();
    final long coverageTimeStamp = suite.getLastCoverageTimeStamp();
    final Int2IntMap oldToNewLineMapping;

    //do not show coverage info over cls
    if (engine.isInLibraryClasses(myProject, file)) {
      return;
    }
    // if in libraries content
    if (engine.isInLibrarySource(myProject, file)) {
      // compare file and coverage timestamps
      if (fileTimeStamp > coverageTimeStamp) {
        showEditorWarningMessage(CoverageBundle.message("coverage.data.outdated"));
        return;
      }
      oldToNewLineMapping = null;
    }
    else {
      // check local history
      oldToNewLineMapping = getOldToNewLineMapping(coverageTimeStamp, editorBean);
      if (oldToNewLineMapping == null) {

        // if history for file isn't available let's check timestamps
        if (fileTimeStamp > coverageTimeStamp && classesArePresentInCoverageData(data, qualifiedNames)) {
          showEditorWarningMessage(CoverageBundle.message("coverage.data.outdated"));
          return;
        }
      }
    }

    if (editor.getUserData(COVERAGE_HIGHLIGHTERS) != null) {
      //highlighters already collected - no need to do it twice
      return;
    }

    final Module module = ReadAction.compute(() -> ModuleUtilCore.findModuleForPsiElement(psiFile));
    if (module != null) {
      if (engine.recompileProjectAndRerunAction(module, suite, () -> CoverageDataManager.getInstance(myProject).chooseSuitesBundle(suite))) {
        return;
      }
    }

    // now if oldToNewLineMapping is null we should use f(x)=id(x) mapping

    // E.g. all *.class files for java source file with several classes
    final Set<File> outputFiles = engine.getCorrespondingOutputFiles(psiFile, module, suite);

    final boolean subCoverageActive = CoverageDataManager.getInstance(myProject).isSubCoverageActive();
    final boolean coverageByTestApplicable = suite.isCoverageByTestApplicable() && !(subCoverageActive && suite.isCoverageByTestEnabled());
    final TreeMap<Integer, LineData> executableLines = new TreeMap<>();
    final TreeMap<Integer, Object[]> classLines = new TreeMap<>();
    final TreeMap<Integer, String> classNames = new TreeMap<>();
    class HighlightersCollector {
      private void collect(File outputFile, final String qualifiedName) {
        final ClassData fileData = data.getClassData(qualifiedName);
        if (fileData != null) {
          final Object[] lines = fileData.getLines();
          if (lines != null) {
            final Object[] postProcessedLines = engine.postProcessExecutableLines(lines, editor);
            for (Object lineData : postProcessedLines) {
              if (lineData instanceof LineData) {
                final int line = ((LineData)lineData).getLineNumber() - 1;
                final int lineNumberInCurrent;
                if (oldToNewLineMapping != null) {
                  // use mapping based on local history
                  if (!oldToNewLineMapping.containsKey(line)) {
                    continue;
                  }
                  lineNumberInCurrent = oldToNewLineMapping.get(line);
                }
                else {
                  // use id mapping
                  lineNumberInCurrent = line;
                }
                if (engine.isGeneratedCode(myProject, qualifiedName, lineData)) continue;
                executableLines.put(line, (LineData)lineData);

                classLines.put(line, postProcessedLines);
                classNames.put(line, qualifiedName);

                ApplicationManager.getApplication().invokeLater(() -> {
                  if (lineNumberInCurrent >= document.getLineCount()) return;
                  if (editorBean.isDisposed()) return;
                  final RangeHighlighter highlighter =
                    createRangeHighlighter(suite.getLastCoverageTimeStamp(), markupModel, coverageByTestApplicable, executableLines,
                                           qualifiedName, line, lineNumberInCurrent, suite, postProcessedLines, editorBean);
                  highlighters.add(highlighter);
                });
              }
            }
          }
        }
        else if (outputFile != null &&
                 !subCoverageActive &&
                 engine.includeUntouchedFileInCoverage(qualifiedName, outputFile, psiFile, suite)) {
          collectNonCoveredFileInfo(outputFile, highlighters, markupModel, executableLines, coverageByTestApplicable, editorBean);
        }
      }
    }

    final HighlightersCollector collector = new HighlightersCollector();
    if (!outputFiles.isEmpty()) {
      for (File outputFile : outputFiles) {
        final String qualifiedName = engine.getQualifiedName(outputFile, psiFile);
        if (qualifiedName != null) {
          collector.collect(outputFile, qualifiedName);
        }
      }
    }
    else { //check non-compilable classes which present in ProjectData
      for (String qName : qualifiedNames) {
        collector.collect(null, qName);
      }
    }
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!editorBean.isDisposed() && highlighters.size() > 0) {
        editor.putUserData(COVERAGE_HIGHLIGHTERS, highlighters);
      }
    });

    final DocumentListener documentListener = new DocumentListener() {
      @Override
      public void documentChanged(@NotNull final DocumentEvent e) {
        myNewToOldLines = null;
        myOldToNewLines = null;
        List<RangeHighlighter> rangeHighlighters = editor.getUserData(COVERAGE_HIGHLIGHTERS);
        if (rangeHighlighters == null) rangeHighlighters = new ArrayList<>();
        int offset = e.getOffset();
        final int lineNumber = document.getLineNumber(offset);
        final int lastLineNumber = document.getLineNumber(offset + e.getNewLength());
        final TextRange changeRange =
          new TextRange(document.getLineStartOffset(lineNumber), document.getLineEndOffset(lastLineNumber));
        for (Iterator<RangeHighlighter> it = rangeHighlighters.iterator(); it.hasNext(); ) {
          final RangeHighlighter highlighter = it.next();
          if (!highlighter.isValid() || TextRange.create(highlighter).intersects(changeRange)) {
            highlighter.dispose();
            it.remove();
          }
        }
        final List<RangeHighlighter> highlighters = rangeHighlighters;
        myUpdateAlarm.cancelAllRequests();
        if (!myUpdateAlarm.isDisposed()) {
          myUpdateAlarm.addRequest(() -> {
            Int2IntMap newToOldLineMapping = getNewToOldLineMapping(suite.getLastCoverageTimeStamp(), editorBean);
            if (newToOldLineMapping != null) {
              ApplicationManager.getApplication().invokeLater(() -> {
                if (editorBean.isDisposed()) return;
                for (int line = lineNumber; line <= lastLineNumber; line++) {
                  final int oldLineNumber = newToOldLineMapping.get(line);
                  final LineData lineData = executableLines.get(oldLineNumber);
                  if (lineData != null && oldLineNumber < editorBean.getDocument().getLineCount()) {
                    RangeHighlighter rangeHighlighter =
                      createRangeHighlighter(suite.getLastCoverageTimeStamp(), markupModel, coverageByTestApplicable, executableLines,
                                             classNames.get(oldLineNumber), oldLineNumber, line, suite,
                                             classLines.get(oldLineNumber), editorBean);
                    highlighters.add(rangeHighlighter);
                  }
                }
                editor.putUserData(COVERAGE_HIGHLIGHTERS, highlighters.size() > 0 ? highlighters : null);
              });
            }
          }, 100);
        }
      }
    };
    document.addDocumentListener(documentListener);
    editor.putUserData(COVERAGE_DOCUMENT_LISTENER, documentListener);
  }

  private static boolean classesArePresentInCoverageData(ProjectData data, Set<String> qualifiedNames) {
    for (String qualifiedName : qualifiedNames) {
      if (data.getClassData(qualifiedName) != null) {
        return true;
      }
    }
    return false;
  }

  private RangeHighlighter createRangeHighlighter(final long date, final MarkupModel markupModel,
                                                  final boolean coverageByTestApplicable,
                                                  final TreeMap<Integer, LineData> executableLines, @Nullable final String className,
                                                  final int line,
                                                  final int lineNumberInCurrent,
                                                  @NotNull final CoverageSuitesBundle coverageSuite, Object[] lines,
                                                  @NotNull MyEditorBean editorBean) {
    EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
    final TextAttributesKey attributesKey = CoverageLineMarkerRenderer.getAttributesKey(line, executableLines);
    final TextAttributes attributes = scheme.getAttributes(attributesKey);
    TextAttributes textAttributes = null;
    if (attributes.getBackgroundColor() != null) {
      textAttributes = attributes;
    }
    Document document = editorBean.getDocument();
    Editor editor = editorBean.getEditor();
    final int startOffset = document.getLineStartOffset(lineNumberInCurrent);
    final int endOffset = document.getLineEndOffset(lineNumberInCurrent);
    final RangeHighlighter highlighter =
      markupModel.addRangeHighlighter(startOffset, endOffset, HighlighterLayer.SELECTION - 1, textAttributes, HighlighterTargetArea.LINES_IN_RANGE);
    final Function<Integer, Integer> newToOldConverter = newLine -> {
      if (editor == null) return -1;
      final Int2IntMap oldLineMapping = getNewToOldLineMapping(date, editorBean);
      return oldLineMapping != null ? oldLineMapping.get(newLine.intValue()) : newLine.intValue();
    };
    final Function<Integer, Integer> oldToNewConverter = newLine -> {
      if (editor == null) return -1;
      final Int2IntMap newLineMapping = getOldToNewLineMapping(date, editorBean);
      return newLineMapping != null ? newLineMapping.get(newLine.intValue()) : newLine.intValue();
    };
    final LineMarkerRendererWithErrorStripe markerRenderer = coverageSuite
      .getLineMarkerRenderer(line, className, executableLines, coverageByTestApplicable, coverageSuite, newToOldConverter,
                             oldToNewConverter, CoverageDataManager.getInstance(myProject).isSubCoverageActive());
    highlighter.setLineMarkerRenderer(markerRenderer);

    final LineData lineData = className != null ? executableLines.get(line) : null;
    if (lineData != null && lineData.getStatus() == LineCoverage.NONE) {
      highlighter.setErrorStripeMarkColor(markerRenderer.getErrorStripeColor(editor));
      highlighter.setThinErrorStripeMark(true);
      highlighter.setGreedyToLeft(true);
      highlighter.setGreedyToRight(true);
    }
    return highlighter;
  }

  private void showEditorWarningMessage(final @Nls String message) {
    Editor textEditor = myEditor;
    PsiFile file = myFile;
    ApplicationManager.getApplication().invokeLater(() -> {
      if (textEditor == null || textEditor.isDisposed() || file == null) return;
      final FileEditorManager fileEditorManager = FileEditorManager.getInstance(myProject);
      final VirtualFile vFile = file.getVirtualFile();
      assert vFile != null;
      Map<FileEditor, EditorNotificationPanel> map = file.getCopyableUserData(NOTIFICATION_PANELS);
      if (map == null) {
        map = new HashMap<>();
        file.putCopyableUserData(NOTIFICATION_PANELS, map);
      }

      final FileEditor[] editors = fileEditorManager.getAllEditors(vFile);
      for (final FileEditor editor : editors) {
        if (isCurrentEditor(editor)) {
          final EditorNotificationPanel panel = new EditorNotificationPanel(editor) {
            {
              myLabel.setIcon(AllIcons.General.ExclMark);
              myLabel.setText(message);
            }
          };
          panel.createActionLabel(CoverageBundle.message("link.label.close"), () -> fileEditorManager.removeTopComponent(editor, panel));
          map.put(editor, panel);
          fileEditorManager.addTopComponent(editor, panel);
          break;
        }
      }
    });
  }

  private boolean isCurrentEditor(FileEditor editor) {
    return editor instanceof TextEditor && ((TextEditor)editor).getEditor() == myEditor;
  }

  private void collectNonCoveredFileInfo(final File outputFile,
                                         final List<RangeHighlighter> highlighters, final MarkupModel markupModel,
                                         final TreeMap<Integer, LineData> executableLines,
                                         final boolean coverageByTestApplicable,
                                         @NotNull MyEditorBean editorBean) {
    final CoverageSuitesBundle coverageSuite = CoverageDataManager.getInstance(myProject).getCurrentSuitesBundle();
    if (coverageSuite == null) return;
    Document document = editorBean.getDocument();
    VirtualFile file = editorBean.getVFile();
    final Int2IntMap mapping;
    if (outputFile.lastModified() < file.getTimeStamp()) {
      mapping = getOldToNewLineMapping(outputFile.lastModified(), editorBean);
      if (mapping == null) return;
    }
    else {
      mapping = null;
    }


    final List<Integer> uncoveredLines = coverageSuite.getCoverageEngine().collectSrcLinesForUntouchedFile(outputFile, coverageSuite);

    final int lineCount = document.getLineCount();
    if (uncoveredLines == null) {
      for (int lineNumber = 0; lineNumber < lineCount; lineNumber++) {
        addHighlighter(outputFile, highlighters, markupModel, executableLines, coverageByTestApplicable, coverageSuite,
                       lineNumber, lineNumber, editorBean);
      }
    }
    else {
      for (int lineNumber : uncoveredLines) {
        if (lineNumber >= lineCount) {
          continue;
        }

        final int updatedLineNumber = mapping != null ? mapping.get(lineNumber) : lineNumber;

        addHighlighter(outputFile, highlighters, markupModel, executableLines, coverageByTestApplicable, coverageSuite,
                       lineNumber, updatedLineNumber, editorBean);
      }
    }
  }

  private void addHighlighter(final File outputFile,
                              final List<RangeHighlighter> highlighters,
                              final MarkupModel markupModel,
                              final TreeMap<Integer, LineData> executableLines,
                              final boolean coverageByTestApplicable,
                              final CoverageSuitesBundle coverageSuite,
                              final int lineNumber,
                              final int updatedLineNumber,
                              @NotNull MyEditorBean editorBean) {
    executableLines.put(updatedLineNumber, null);
    ApplicationManager.getApplication().invokeLater(() -> {
      if (editorBean.isDisposed()) return;
      if (lineNumber >= editorBean.getDocument().getLineCount()) return;
      final RangeHighlighter highlighter =
        createRangeHighlighter(outputFile.lastModified(), markupModel, coverageByTestApplicable, executableLines, null, lineNumber,
                               updatedLineNumber, coverageSuite, null, editorBean);
      highlighters.add(highlighter);
    });
  }

  private static VirtualFile getVirtualFile(PsiFile file) {
    final VirtualFile vFile = file.getVirtualFile();
    LOG.assertTrue(vFile != null);
    return vFile;
  }


  private void coverageDataNotFound(final CoverageSuitesBundle suite) {
    showEditorWarningMessage(CoverageBundle.message("coverage.data.not.found"));
    for (CoverageSuite coverageSuite : suite.getSuites()) {
      CoverageDataManager.getInstance(myProject).removeCoverageSuite(coverageSuite);
    }
  }

  @Override
  public void dispose() {
    hideCoverage();
    myEditor = null;
    myDocument = null;
    myFile = null;
  }

  static class MyEditorBean {
    private final Editor myEditor;
    private final VirtualFile myVFile;
    private final Document myDocument;

    MyEditorBean(Editor editor, VirtualFile VFile, Document document) {
      myEditor = editor;
      myVFile = VFile;
      myDocument = document;
    }

    public boolean isDisposed() {
      return myEditor == null ||
             myEditor.isDisposed() ||
             myVFile == null ||
             myDocument == null;
    }

    public Document getDocument() {
      return myDocument;
    }

    public Editor getEditor() {
      return myEditor;
    }

    public VirtualFile getVFile() {
      return myVFile;
    }
  }
}
