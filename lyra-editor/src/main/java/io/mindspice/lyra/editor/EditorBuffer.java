package io.mindspice.lyra.editor;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.regex.PatternSyntaxException;

/** One document with native editing, undo, search, line gutter and compiler styles. */
final class EditorBuffer extends BorderPane {
    final CodeArea area = new CodeArea();
    WorkspaceFiles.Document document;
    final Tab tab = new Tab();
    LanguageService.Analysis analysis;
    long version;
    boolean conflict;
    private Set<Integer> breakpoints = Set.of();
    private int executionLine = -1;
    private final Consumer<Integer> toggleBreakpoint;
    private final HBox findBar = new HBox(6);
    private final TextField query = new TextField(), replacement = new TextField();
    private final CheckBox matchCase = new CheckBox("Aa"), regex = new CheckBox(".*");
    private final Label found = new Label();
    private int lastMatch = -1;
    private final Map<Integer, Integer> delimiterPairs = new HashMap<>();
    private final Set<Integer> markedDelimiters = new HashSet<>();

    EditorBuffer(WorkspaceFiles.Document document, BiConsumer<String, String> changed, Runnable caretChanged, Consumer<Integer> toggleBreakpoint) {
        this.document = document;
        this.toggleBreakpoint = toggleBreakpoint;
        area.setId("source-editor");
        area.getStyleClass().add("source-editor");
        area.setAccessibleText("Lyra source editor: " + document.path().getFileName());
        area.setWrapText(false);
        area.replaceText(document.text());
        area.getUndoManager().forgetHistory();
        area.setParagraphGraphicFactory(this::gutter);
        setCenter(new VirtualizedScrollPane<>(area));
        tab.setContent(this);
        tab.setTooltip(new Tooltip(document.path().toString()));
        title();
        area.textProperty().addListener((obs, oldText, newText) -> {
            version++; lastMatch = -1; title(); changed.accept(oldText, newText);
        });
        area.caretPositionProperty().addListener((obs, oldPosition, newPosition) -> { matchDelimiters(); caretChanged.run(); });
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!area.isEditable()) return;
            if (event.getCode() == KeyCode.TAB && !event.isControlDown() && !event.isMetaDown()) {
                indent(event.isShiftDown()); event.consume();
            } else if (event.getCode() == KeyCode.ENTER && !event.isShortcutDown() && !event.isAltDown()) {
                int start = area.getText().lastIndexOf('\n', area.getCaretPosition() - 1) + 1;
                String before = area.getText(start, area.getCaretPosition());
                String whitespace = before.substring(0, before.length() - before.stripLeading().length());
                String extra = before.stripTrailing().endsWith("{") || before.stripTrailing().endsWith("[")
                        || before.stripTrailing().endsWith("(") ? "    " : "";
                area.replaceSelection("\n" + whitespace + extra); event.consume();
            }
        });
        buildSearch();
    }
    Path path() { return document.path(); }
    boolean dirty() { return !area.getText().equals(document.text()); }
    void title() { tab.setText((dirty() ? "● " : "") + path().getFileName() + (conflict ? " ⚠" : "")); }

    private javafx.scene.Node gutter(int paragraph) {
        int line = paragraph + 1;
        Label mark = new Label(executionLine == line ? "▶" : breakpoints.contains(line) ? "●" : " ");
        mark.getStyleClass().add(executionLine == line ? "execution-marker" : "breakpoint-marker");
        mark.setMinWidth(18);
        mark.setAccessibleText("Toggle breakpoint on line " + line);
        Tooltip.install(mark, new Tooltip("Click to toggle breakpoint · line " + line));
        HBox gutter = new HBox(mark, LineNumberFactory.get(area).apply(paragraph));
        gutter.getStyleClass().add("editor-gutter");
        gutter.setOnMouseClicked(event -> { if (event.getButton() == MouseButton.PRIMARY) toggleBreakpoint.accept(line); });
        return gutter;
    }
    void setBreakpoints(Set<Integer> lines) { breakpoints = Set.copyOf(lines); area.setParagraphGraphicFactory(this::gutter); }
    void setExecutionLine(int line) {
        if (executionLine > 0 && executionLine <= area.getParagraphs().size()) area.setParagraphStyle(executionLine - 1, List.of());
        executionLine = line;
        if (line > 0 && line <= area.getParagraphs().size()) area.setParagraphStyle(line - 1, List.of("execution-line"));
        area.setParagraphGraphicFactory(this::gutter);
    }

    void applyAnalysis(LanguageService.Analysis value) {
        if (!area.getText().equals(value.text())) return;
        analysis = value;
        if (value.text().isEmpty()) return;
        record Change(String css, int delta) { }
        TreeMap<Integer, List<Change>> changes = new TreeMap<>();
        changes.put(0, new ArrayList<>()); changes.put(value.text().length(), new ArrayList<>());
        BiConsumer<LanguageService.Style, String> add = (span, css) -> {
            int start = Math.clamp(span.start(), 0, value.text().length()), end = Math.clamp(span.end(), start, value.text().length());
            if (start == end) return;
            changes.computeIfAbsent(start, ignored -> new ArrayList<>()).add(new Change(css, 1));
            changes.computeIfAbsent(end, ignored -> new ArrayList<>()).add(new Change(css, -1));
        };
        value.styles().forEach(style -> add.accept(style, style.css()));
        value.diagnostics().stream().filter(d -> d.primarySpan().sourceId().value().equals(path().toUri().toString()))
                .forEach(d -> add.accept(new LanguageService.Style(d.primarySpan().startOffset(),
                        Math.max(d.primarySpan().endOffset(), d.primarySpan().startOffset() + 1), ""), "syntax-error"));
        var builder = new StyleSpansBuilder<Collection<String>>();
        Map<String, Integer> active = new TreeMap<>();
        int cursor = 0;
        for (var entry : changes.entrySet()) {
            if (entry.getKey() > cursor) builder.add(List.copyOf(active.keySet()), entry.getKey() - cursor);
            entry.getValue().forEach(change -> {
                int count = active.getOrDefault(change.css(), 0) + change.delta();
                if (count == 0) active.remove(change.css()); else active.put(change.css(), count);
            });
            cursor = entry.getKey();
        }
        area.setStyleSpans(0, builder.create());
        delimiterPairs.clear(); markedDelimiters.clear();
        Deque<Integer> openings = new ArrayDeque<>();
        for (var style : value.styles()) {
            if (!style.css().equals("syntax-punctuation") || style.end() - style.start() != 1) continue;
            char c = value.text().charAt(style.start());
            if ("([{ ".indexOf(c) >= 0 && c != ' ') openings.push(style.start());
            else if (")] }".indexOf(c) >= 0 && c != ' ' && !openings.isEmpty()) {
                int open = openings.pop(); char left = value.text().charAt(open);
                if ((left == '(' && c == ')') || (left == '[' && c == ']') || (left == '{' && c == '}')) {
                    delimiterPairs.put(open, style.start()); delimiterPairs.put(style.start(), open);
                } else openings.clear();
            }
        }
        matchDelimiters();
    }
    private void matchDelimiters() {
        for (int offset : markedDelimiters) if (offset < area.getLength()) {
            var style = new ArrayList<>(area.getStyleOfChar(offset)); style.remove("matching-delimiter"); area.setStyle(offset, offset + 1, style);
        }
        markedDelimiters.clear();
        if (analysis == null || !analysis.text().equals(area.getText())) return;
        int caret = area.getCaretPosition();
        int offset = delimiterPairs.containsKey(caret) ? caret : caret - 1;
        Integer other = delimiterPairs.get(offset); if (other == null) return;
        for (int match : List.of(offset, other)) {
            var style = new ArrayList<>(area.getStyleOfChar(match)); style.add("matching-delimiter"); area.setStyle(match, match + 1, style); markedDelimiters.add(match);
        }
    }

    void select(int start, int end) {
        area.selectRange(Math.clamp(start, 0, area.getLength()), Math.clamp(end, 0, area.getLength()));
        area.requestFollowCaret(); area.requestFocus();
    }
    void goToLine(int line) { int offset = TextEdits.offsetAt(area.getText(), line); select(offset, offset); }
    void showFind(boolean replace) {
        replacement.setVisible(replace); replacement.setManaged(replace);
        if (!area.getSelectedText().isEmpty() && !area.getSelectedText().contains("\n")) query.setText(area.getSelectedText());
        setTop(findBar); query.requestFocus(); query.selectAll();
    }
    private void buildSearch() {
        query.setPromptText("Find in file"); replacement.setPromptText("Replace with");
        query.setId("find-query"); replacement.setId("replace-text");
        Button previous = new Button("↑"), next = new Button("↓"), replace = new Button("Replace"), all = new Button("All"), close = new Button("×");
        previous.setOnAction(e -> find(-1)); next.setOnAction(e -> find(1));
        query.setOnAction(e -> find(1));
        query.textProperty().addListener((o, a, b) -> { lastMatch = -1; updateCount(); });
        matchCase.setOnAction(e -> updateCount()); regex.setOnAction(e -> updateCount());
        replace.setOnAction(e -> {
            if (!area.isEditable()) return;
            List<TextEdits.Match> matches = matches();
            var match = matches.stream().filter(m -> m.start() == area.getSelection().getStart() && m.end() == area.getSelection().getEnd()).findFirst();
            if (match.isPresent()) area.replaceSelection(replacement.getText());
            find(1);
        });
        all.setOnAction(e -> { if (area.isEditable()) area.replaceText(TextEdits.replaceAll(area.getText(), matches(), replacement.getText())); });
        close.setOnAction(e -> { setTop(null); area.requestFocus(); });
        findBar.getChildren().addAll(query, matchCase, regex, previous, next, replacement, replace, all, found, close);
        findBar.setPadding(new Insets(8)); findBar.getStyleClass().add("find-bar");
        findBar.addEventFilter(KeyEvent.KEY_PRESSED, e -> { if (e.getCode() == KeyCode.ESCAPE) { setTop(null); area.requestFocus(); e.consume(); } });
    }
    private List<TextEdits.Match> matches() {
        try { return TextEdits.matches(area.getText(), query.getText(), regex.isSelected(), matchCase.isSelected()); }
        catch (PatternSyntaxException invalid) { found.setText("Invalid pattern"); return List.of(); }
    }
    private void updateCount() { found.setText(matches().size() + " matches"); }
    private void find(int direction) {
        List<TextEdits.Match> matches = matches();
        if (matches.isEmpty()) { found.setText("No matches"); return; }
        int index;
        if (lastMatch >= 0) index = Math.floorMod(lastMatch + direction, matches.size());
        else {
            index = 0;
            while (index < matches.size() && matches.get(index).start() < area.getCaretPosition()) index++;
            index = Math.floorMod(direction > 0 ? index : index - 1, matches.size());
        }
        var match = matches.get(index); lastMatch = index;
        select(match.start(), match.end()); found.setText((index + 1) + " / " + matches.size());
    }
    void comment() { if (area.isEditable()) editLines(TextEdits::toggleComment); }
    private void indent(boolean outdent) {
        if (area.getSelectedText().isEmpty() && !outdent) { area.replaceSelection("    "); return; }
        editLines(text -> String.join("\n", Arrays.stream(text.split("\n", -1)).map(line -> outdent
                ? line.startsWith("    ") ? line.substring(4) : line.startsWith("\t") ? line.substring(1) : line
                : "    " + line).toList()));
    }
    private void editLines(UnaryOperator<String> edit) {
        String text = area.getText();
        int start = text.lastIndexOf('\n', Math.max(-1, area.getSelection().getStart() - 1)) + 1;
        int selectionEnd = area.getSelection().getEnd();
        if (selectionEnd > start && text.charAt(selectionEnd - 1) == '\n') selectionEnd--;
        int end = text.indexOf('\n', selectionEnd);
        if (end < 0) end = text.length();
        String replacement = edit.apply(text.substring(start, end));
        area.replaceText(start, end, replacement); select(start, start + replacement.length());
    }
}
