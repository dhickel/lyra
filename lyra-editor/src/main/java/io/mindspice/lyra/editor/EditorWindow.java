package io.mindspice.lyra.editor;

import com.sun.jdi.request.StepRequest;
import io.mindspice.lyra.compiler.api.CompileRequest;
import io.mindspice.lyra.compiler.api.CompileResult;
import io.mindspice.lyra.compiler.api.JarMode;
import io.mindspice.lyra.compiler.api.LyraCompiler;
import io.mindspice.lyra.compiler.api.WriteOptions;
import io.mindspice.lyra.compiler.diagnostic.Diagnostic;
import io.mindspice.lyra.repl.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.prefs.Preferences;

/** JavaFX presentation. Compilation, filesystem traversal and execution have separate worker queues. */
public final class EditorWindow {
    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final TabPane editors = new TabPane(), bottom = new TabPane();
    private final TreeView<Path> files = new TreeView<>();
    private final ListView<LanguageService.Definition> outline = new ListView<>();
    private final TextField symbolFilter = new TextField();
    private final CheckBox projectSymbols = new CheckBox("All files");
    private final Map<Path, LanguageService.Analysis> symbolIndex = new HashMap<>();
    private Future<?> symbolIndexTask;
    private final ListView<String> bindings = new ListView<>();
    private final ListView<Diagnostic> problems = new ListView<>();
    private final ListView<WorkspaceIndex.SearchHit> searchResults = new ListView<>();
    private final ListView<Debugger.Frame> frames = new ListView<>();
    private final ListView<String> variables = new ListView<>();
    private final TextArea transcript = new TextArea(), replInput = new TextArea();
    private final TextField programInput = new TextField();
    private final Label status = new Label("Ready"), position = new Label("UTF-8  ·  Lyra"), projectName = new Label("No project open"), entryName = new Label("Choose an entry point");
    private final BooleanProperty busy = new SimpleBooleanProperty(), paused = new SimpleBooleanProperty(), debugLocked = new SimpleBooleanProperty();
    private final CheckMenuItem showGenerated = new CheckMenuItem("Show hidden and generated files"), wrap = new CheckMenuItem("Wrap long lines"), light = new CheckMenuItem("Light theme");
    private final Map<Path, EditorBuffer> buffers = new LinkedHashMap<>();
    private final Map<Path, Future<?>> pendingChecks = new HashMap<>();
    private final Map<Path, LanguageService.Analysis> analyses = new HashMap<>();
    private final Set<Debugger.Breakpoint> breakpoints = new LinkedHashSet<>();
    private final Set<Path> opening = new HashSet<>();
    private final List<String> history = new ArrayList<>();
    private final Map<String, String> functionArguments = new HashMap<>();
    private int historyCursor;
    private String historyDraft = "";
    private final LanguageService language = new LanguageService();
    private final ScheduledThreadPoolExecutor checks = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "lyra-editor-checks"));
    private final ExecutorService disk = Executors.newSingleThreadExecutor(r -> daemon(r, "lyra-editor-files"));
    private final ExecutorService execution = Executors.newSingleThreadExecutor(r -> daemon(r, "lyra-editor-evaluation"));
    private final ExecutorService controls = Executors.newCachedThreadPool(r -> daemon(r, "lyra-editor-controls"));
    private final Preferences preferences = Preferences.userNodeForPackage(EditorWindow.class);
    private final StringBuilder pendingOutput = new StringBuilder();
    private final Timeline outputTimer, recoveryTimer;
    private final Tab replTab, problemsTab, searchTab, debugTab;
    private WorkspaceSettings workspace;
    private RecoveryStore recovery;
    private WorkspaceWatcher watcher;
    private final Object runtimeLifecycle = new Object();
    private volatile EditorRuntime runtime;
    private volatile long runtimeGeneration;
    private long workspaceGeneration, editRevision;
    private volatile long operationId;
    private volatile boolean disposed;
    private boolean closing;
    private double fontSize = 14;

    public EditorWindow(Stage stage) {
        this.stage = stage;
        checks.setRemoveOnCancelPolicy(true);
        stage.setTitle("Lyra Editor");
        stage.setMinWidth(1000); stage.setMinHeight(700);
        root.getStyleClass().add("workbench");
        root.setTop(new VBox(menu(), toolbar()));
        root.setBottom(statusBar());
        editors.setId("editor-tabs");
        editors.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        editors.getSelectionModel().selectedItemProperty().addListener((o, previous, selected) -> updateActive());
        StackPane center = new StackPane(welcome(), editors);
        editors.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(editors.getTabs()));
        SplitPane horizontal = new SplitPane(explorer(), center, symbols());
        horizontal.setDividerPositions(.19, .80);
        replTab = fixedTab("REPL", repl());
        problemsTab = fixedTab("Problems", problems);
        searchTab = fixedTab("Search", searchResults);
        debugTab = fixedTab("Debugger", debuggerPane());
        bottom.getTabs().addAll(replTab, problemsTab, searchTab, debugTab);
        bottom.setId("bottom-tabs"); bottom.setMinHeight(170);
        SplitPane vertical = new SplitPane(horizontal, bottom);
        vertical.setOrientation(Orientation.VERTICAL); vertical.setDividerPositions(.69);
        root.setCenter(vertical);
        configureProblems();
        Scene scene = new Scene(root, preferences.getDouble("width", 1440), preferences.getDouble("height", 950));
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("editor.css")).toExternalForm());
        stage.setScene(scene);
        light.setSelected(preferences.getBoolean("light", false)); applyTheme();
        stage.setOnCloseRequest(event -> { event.consume(); requestClose(); });
        stage.focusedProperty().addListener((o, wasFocused, focused) -> { if (focused) checkExternalChanges(); });
        outputTimer = new Timeline(new KeyFrame(Duration.millis(50), event -> flushOutput()));
        outputTimer.setCycleCount(Animation.INDEFINITE); outputTimer.play();
        recoveryTimer = new Timeline(new KeyFrame(Duration.seconds(8), event -> writeRecovery()));
        recoveryTimer.setCycleCount(Animation.INDEFINITE); recoveryTimer.play();
        appendOutput("Lyra REPL\nOpen a project, then enter an expression. Ctrl+Enter evaluates a selection; \\help lists REPL commands.\n\n");
    }

    private MenuBar menu() {
        Menu file = new Menu("File"), edit = new Menu("Edit"), run = new Menu("Run"), view = new Menu("View"), help = new Menu("Help");
        file.getItems().addAll(item("Open directory…", "Shortcut+Shift+O", this::chooseDirectory),
                item("Open file…", "Shortcut+O", this::chooseFile), item("New file…", "Shortcut+N", () -> newPath(false)),
                item("New directory…", null, () -> newPath(true)), new SeparatorMenuItem(),
                item("Save", "Shortcut+S", () -> saveCurrent(() -> {})), item("Save all", "Shortcut+Shift+S", () -> saveAll(() -> {})),
                item("Reload from disk", null, this::reloadCurrent), item("Rename file…", null, this::renameCurrent),
                item("Close file", "Shortcut+W", () -> { if (active() != null) closeBuffer(active(), () -> {}); }),
                new SeparatorMenuItem(), item("Source roots…", null, this::configureRoots), item("Exit", "Shortcut+Q", this::requestClose));
        edit.getItems().addAll(item("Undo", "Shortcut+Z", () -> withEditableBuffer(b -> b.area.undo())),
                item("Redo", "Shortcut+Shift+Z", () -> withEditableBuffer(b -> b.area.redo())), new SeparatorMenuItem(),
                item("Cut", null, () -> withEditableBuffer(b -> b.area.cut())), item("Copy", null, () -> withBuffer(b -> b.area.copy())),
                item("Paste", null, () -> withEditableBuffer(b -> b.area.paste())), item("Select all", null, () -> withBuffer(b -> b.area.selectAll())),
                new SeparatorMenuItem(), item("Find…", "Shortcut+F", () -> withBuffer(b -> b.showFind(false))),
                item("Replace…", "Shortcut+H", () -> withBuffer(b -> b.showFind(true))),
                item("Find in project…", "Shortcut+Shift+F", this::findInProject), item("Quick open…", "Shortcut+P", this::quickOpen),
                item("Go to line…", "Shortcut+L", this::goToLine), item("Find definition…", "F12", this::findDefinition),
                item("Complete symbol", "Ctrl+Space", this::complete), item("Toggle line comment", "Shortcut+Slash", () -> withBuffer(EditorBuffer::comment)));
        run.getItems().addAll(item("Run entry point", "F5", () -> runEntry(false)), item("Debug entry point", "Shortcut+F5", () -> runEntry(true)),
                item("Run selected function…", "F6", () -> runFunction(false)), item("Debug selected function…", "Shortcut+F6", () -> runFunction(true)),
                item("Set selected function as entry…", null, this::setEntry), new SeparatorMenuItem(),
                item("Evaluate selection / form", "Shortcut+Enter", this::evaluateSelection), item("Load file into REPL", "Shortcut+Shift+Enter", this::loadCurrent),
                item("Check project", "Shortcut+Shift+B", this::checkProject), item("Build application JAR…", "Shortcut+B", this::buildJar),
                item("Attach to running application…", null, this::attach),
                new SeparatorMenuItem(), item("Step into", "F7", () -> step(StepRequest.STEP_INTO)), item("Step over", "F8", () -> step(StepRequest.STEP_OVER)),
                item("Step out", "Shift+F8", () -> step(StepRequest.STEP_OUT)), item("Continue", "F9", this::resume),
                item("Toggle breakpoint", "Shortcut+F8", () -> withBuffer(b -> toggleBreakpoint(b.path(), b.area.getCurrentParagraph() + 1))),
                item("Interrupt evaluation", "Shortcut+Period", this::cancel), item("Stop process", "Shift+F5", this::stop));
        wrap.setOnAction(e -> buffers.values().forEach(b -> b.area.setWrapText(wrap.isSelected())));
        light.setOnAction(e -> applyTheme()); showGenerated.setOnAction(e -> refreshFiles());
        view.getItems().addAll(wrap, light, showGenerated, item("Refresh files", null, this::refreshFiles),
                item("Larger text", "Shortcut+Equals", () -> zoom(1)), item("Smaller text", "Shortcut+Minus", () -> zoom(-1)),
                item("Focus REPL", "Shortcut+BackQuote", () -> { bottom.getSelectionModel().select(replTab); replInput.requestFocus(); }),
                item("Command palette…", "Shortcut+Shift+P", this::commandPalette));
        help.getItems().addAll(item("Keyboard shortcuts", null, () -> info("Keyboard shortcuts", "F5 Run entry · Ctrl+F5 Debug entry\nF6 Run function · Ctrl+F6 Debug function\nF7 Into · F8 Over · Shift+F8 Out · F9 Continue\nShift+F5 Stop · Ctrl+. Interrupt\nCtrl+Enter Evaluate selection/form · Ctrl+Shift+Enter Load file\nCtrl+P Quick open · F12 Find definition · Ctrl+Space Complete\nCtrl+F Find · Ctrl+H Replace · Ctrl+Shift+F Project search\nCtrl+/ Toggle comment · Ctrl+L Go to line\nUse Command in place of Ctrl for standard macOS shortcuts.")),
                item("About Lyra Editor", null, () -> info("Lyra Editor", "A JavaFX development environment for Lyra.\nJava 25 · Compiler-backed checks · Persistent REPL · JVM debugger\n\nRun starts a fresh process. REPL evaluations reuse initialized definitions.\nSee docs/editor.md for the development workflow and packaging instructions.")));
        return new MenuBar(file, edit, run, view, help);
    }
    private Node toolbar() {
        Label brand = new Label("λ  LYRA"); brand.getStyleClass().add("brand");
        projectName.getStyleClass().add("project-name");
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        Button start = button("▶  Run", "run-entry", () -> runEntry(false)); start.getStyleClass().add("primary-button"); start.disableProperty().bind(busy);
        Button debug = button("Debug", "debug-entry", () -> runEntry(true)); debug.disableProperty().bind(busy);
        Button into = button("Into", "step-into", () -> step(StepRequest.STEP_INTO)); into.disableProperty().bind(paused.not());
        Button over = button("Over", "step-over", () -> step(StepRequest.STEP_OVER)); over.disableProperty().bind(paused.not());
        Button out = button("Out", "step-out", () -> step(StepRequest.STEP_OUT)); out.disableProperty().bind(paused.not());
        Button resume = button("Continue", "continue", this::resume); resume.disableProperty().bind(paused.not());
        Button stop = button("■  Stop", "stop-process", this::stop);
        HBox bar = new HBox(10, brand, new Separator(Orientation.VERTICAL), projectName, space, entryName, start, debug, into, over, out, resume, stop);
        bar.setAlignment(Pos.CENTER_LEFT); bar.getStyleClass().add("main-toolbar"); return bar;
    }
    private Node statusBar() {
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        status.setId("status-label");
        HBox bar = new HBox(12, status, spacer, position); bar.getStyleClass().add("status-bar"); return bar;
    }
    private Node explorer() {
        files.setId("project-files"); files.setShowRoot(true);
        files.setCellFactory(view -> new TreeCell<>() {
            @Override protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                setText(empty || path == null ? null : (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "▸  " : "◇  ") + path.getFileName());
                setTooltip(empty || path == null ? null : new Tooltip(path.toString()));
            }
        });
        files.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelectedFile(); });
        files.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelectedFile(); });
        ContextMenu menu = new ContextMenu(item("Open", null, this::openSelectedFile), item("New file…", null, () -> newPath(false)),
                item("New directory…", null, () -> newPath(true)), item("Refresh", null, this::refreshFiles));
        files.setContextMenu(menu);
        VBox panel = new VBox(panelHeading("PROJECT", button("+", "new-file", () -> newPath(false)), button("↻", "refresh-files", this::refreshFiles)), files);
        VBox.setVgrow(files, Priority.ALWAYS); panel.getStyleClass().add("side-panel"); panel.setMinWidth(160); return panel;
    }
    private Node symbols() {
        symbolFilter.setPromptText("Filter definitions…"); symbolFilter.setId("symbol-filter");
        symbolFilter.textProperty().addListener((o, a, b) -> updateOutline());
        outline.setId("function-list"); outline.setPlaceholder(new Label("Functions and definitions appear here"));
        outline.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(LanguageService.Definition value, boolean empty) {
                super.updateItem(value, empty); setText(empty || value == null ? null : value.label() + (projectSymbols.isSelected() ? "  ·  " + value.file().getFileName() : ""));
            }
        });
        outline.setOnMouseClicked(event -> { if (event.getClickCount() == 2) navigateDefinition(outline.getSelectionModel().getSelectedItem()); });
        outline.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) navigateDefinition(outline.getSelectionModel().getSelectedItem()); });
        outline.setContextMenu(new ContextMenu(item("Go to definition", null, () -> navigateDefinition(selectedFunction())),
                item("Run function…", null, () -> runFunction(false)), item("Debug function…", null, () -> runFunction(true)),
                item("Set as entry…", null, this::setEntry), item("Evaluate definition", null, this::evaluateDefinition)));
        Button run = button("▶ Run function", "run-function", () -> runFunction(false)); run.disableProperty().bind(busy);
        Button debug = button("Debug", "debug-function", () -> runFunction(true)); debug.disableProperty().bind(busy);
        FlowPane actions = new FlowPane(6, 6, run, debug, button("Set entry", "set-entry", this::setEntry)); actions.setPadding(new Insets(8));
        bindings.setPlaceholder(new Label("Evaluate source to see live bindings")); bindings.setId("live-bindings");
        projectSymbols.setOnAction(event -> { updateOutline(); if (projectSymbols.isSelected()) indexSymbols(); });
        VBox definitionPanel = new VBox(panelHeading("DEFINITIONS", projectSymbols), symbolFilter, outline, actions);
        VBox.setVgrow(outline, Priority.ALWAYS);
        VBox bindingPanel = new VBox(panelHeading("LIVE BINDINGS", button("↻", "refresh-bindings", this::queryBindings)), bindings);
        VBox.setVgrow(bindings, Priority.ALWAYS);
        SplitPane split = new SplitPane(definitionPanel, bindingPanel); split.setOrientation(Orientation.VERTICAL); split.setDividerPositions(.70);
        split.getStyleClass().add("side-panel"); split.setMinWidth(210); return split;
    }
    private Node repl() {
        transcript.setId("repl-output"); transcript.setEditable(false); transcript.setWrapText(true);
        transcript.getStyleClass().add("console-output");
        replInput.setId("repl-input"); replInput.setPromptText("Lyra expression or \\help   ·   Enter submits a complete form; Shift+Enter adds a line");
        replInput.setPrefRowCount(2); replInput.getStyleClass().add("console-input");
        replInput.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()
                    && (event.isShortcutDown() || isCommandLine(replInput.getText())
                    || LexicalCompleteness.inspect(replInput.getText()).complete())) {
                submitRepl(); event.consume();
            } else if (event.getCode() == KeyCode.UP && !history.isEmpty() && replInput.getCaretPosition() == 0) {
                if (historyCursor == history.size()) historyDraft = replInput.getText();
                historyCursor = Math.max(0, historyCursor - 1); replInput.setText(history.get(historyCursor)); replInput.positionCaret(0); event.consume();
            } else if (event.getCode() == KeyCode.DOWN && !history.isEmpty() && replInput.getCaretPosition() == replInput.getLength()) {
                historyCursor = Math.min(history.size(), historyCursor + 1);
                replInput.setText(historyCursor == history.size() ? historyDraft : history.get(historyCursor));
                replInput.positionCaret(replInput.getLength()); event.consume();
            }
        });
        Button evaluate = button("Evaluate", "evaluate-repl", this::submitRepl); evaluate.disableProperty().bind(busy);
        HBox buttons = new HBox(8, new Label("λ"), evaluate, button("Interrupt", "interrupt", this::cancel),
                button("Bindings", "query-bindings", this::queryBindings), button("Type", "query-type", this::queryType),
                button("Reset", "reset-repl", () -> execute("Reset REPL", this::resetRepl)),
                button("Clear output", "clear-output", () -> transcript.clear()));
        buttons.setAlignment(Pos.CENTER_LEFT); buttons.setPadding(new Insets(6, 10, 6, 10));
        programInput.setId("program-input"); programInput.setPromptText("Program input for std->io readLine");
        programInput.setOnAction(e -> sendProgramInput()); HBox.setHgrow(programInput, Priority.ALWAYS);
        HBox stdin = new HBox(8, new Label("stdin"), programInput, button("Send", "send-input", this::sendProgramInput),
                button("EOF", "input-eof", () -> control(engine -> engine.endInput())));
        stdin.setAlignment(Pos.CENTER_LEFT); stdin.setPadding(new Insets(6, 10, 8, 10));
        VBox pane = new VBox(transcript, buttons, replInput, stdin); VBox.setVgrow(transcript, Priority.ALWAYS); return pane;
    }
    private Node debuggerPane() {
        frames.setId("debug-frames"); variables.setId("debug-variables");
        frames.setPlaceholder(new Label("Debug a function or entry point to stop at its first executable line."));
        variables.setPlaceholder(new Label("Arguments, available locals and captured fields appear when paused."));
        frames.setCellFactory(list -> new ListCell<>() { @Override protected void updateItem(Debugger.Frame frame, boolean empty) {
            super.updateItem(frame, empty); setText(empty || frame == null ? null : frame.label());
        }});
        frames.getSelectionModel().selectedItemProperty().addListener((o, previous, frame) -> {
            if (frame == null) return;
            variables.getItems().setAll(frame.variables().entrySet().stream().map(value -> value.getKey() + " = " + value.getValue()).toList());
            openFile(frame.file(), buffer -> { buffer.setExecutionLine(frame.line()); buffer.goToLine(frame.line()); });
        });
        SplitPane split = new SplitPane(frames, variables); split.setDividerPositions(.42); return split;
    }
    private Node welcome() {
        Label mark = new Label("λ"); mark.getStyleClass().add("welcome-mark");
        Label heading = new Label("Think in functions."); heading.getStyleClass().add("welcome-heading");
        Label description = new Label("Write, evaluate, and explore your Lyra programs.\nYour source and a live REPL, in one workspace.");
        description.getStyleClass().add("welcome-description"); description.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        Button open = button("Open a project directory", "open-project", this::chooseDirectory); open.getStyleClass().add("primary-button");
        Label shortcuts = new Label("Ctrl+P  Find a file     ·     Ctrl+Enter  Evaluate     ·     F6  Run a function");
        shortcuts.getStyleClass().add("welcome-shortcuts");
        VBox content = new VBox(18, mark, heading, description, open, shortcuts); content.setAlignment(Pos.CENTER); return content;
    }

    public void openWorkspace(Path path) { saveBeforeLeaving(() -> background(() -> WorkspaceSettings.open(path), this::installWorkspace)); }
    private void installWorkspace(WorkspaceSettings value) {
        stop(); workspaceGeneration++; editRevision++;
        closeWatcher();
        pendingChecks.values().forEach(future -> future.cancel(false)); pendingChecks.clear();
        buffers.clear(); opening.clear(); analyses.clear(); symbolIndex.clear(); breakpoints.clear(); editors.getTabs().clear(); problems.getItems().clear();
        workspace = value; recovery = new RecoveryStore(value.root());
        long epoch = workspaceGeneration;
        background(() -> new WorkspaceWatcher(value.root(), changed -> fx(() -> {
            if (epoch == workspaceGeneration) checkExternalChanges();
        })), created -> {
            if (epoch != workspaceGeneration) { try { created.close(); } catch (IOException ignored) { } }
            else watcher = created;
        });
        String directoryName = value.root().getFileName() == null ? value.root().toString() : value.root().getFileName().toString();
        projectName.setText(directoryName);
        projectName.setTooltip(new Tooltip(value.root().toString()));
        stage.setTitle(directoryName + " — Lyra Editor"); updateEntry(); refreshFiles();
        status.setText("Project open · source roots configured · REPL starts on first evaluation");
        Path initial = value.entry().map(WorkspaceSettings.RunTarget::file).orElse(value.root().resolve("main.lyra"));
        if (Files.isRegularFile(initial)) openFile(initial, ignored -> {});
        if (projectSymbols.isSelected()) indexSymbols();
    }
    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Open Lyra project");
        if (workspace != null) chooser.setInitialDirectory(workspace.root().toFile());
        java.io.File chosen = chooser.showDialog(stage); if (chosen != null) openWorkspace(chosen.toPath());
    }
    private void chooseFile() {
        FileChooser chooser = fileChooser("Open file"); java.io.File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            if (workspace == null) background(() -> WorkspaceSettings.open(selected.toPath().getParent()), value -> { installWorkspace(value); openFile(selected.toPath(), ignored -> {}); });
            else openFile(selected.toPath(), ignored -> {});
        }
    }
    private FileChooser fileChooser(String title) {
        FileChooser chooser = new FileChooser(); chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Lyra source", "*.lyra"), new FileChooser.ExtensionFilter("All files", "*"));
        if (workspace != null) chooser.setInitialDirectory(workspace.root().toFile()); return chooser;
    }
    private void openSelectedFile() {
        TreeItem<Path> item = files.getSelectionModel().getSelectedItem();
        if (item != null && item.getValue() != null && !Files.isDirectory(item.getValue())) openFile(item.getValue(), ignored -> {});
    }
    void openFile(Path file, Consumer<EditorBuffer> after) {
        Path key = file.toAbsolutePath().normalize();
        EditorBuffer existing = buffers.get(key);
        if (existing != null) { editors.getSelectionModel().select(existing.tab); after.accept(existing); return; }
        if (!opening.add(key)) return;
        long epoch = workspaceGeneration;
        background(() -> new WorkspaceFiles.Document(key), document -> {
            opening.remove(key); if (epoch != workspaceGeneration) return;
            EditorBuffer duplicate = buffers.get(document.path());
            if (duplicate != null) { editors.getSelectionModel().select(duplicate.tab); after.accept(duplicate); return; }
            EditorBuffer buffer = new EditorBuffer(document, (oldText, newText) -> documentChanged(document.path(), oldText, newText), this::caretChanged,
                    line -> toggleBreakpoint(document.path(), line));
            buffer.area.setStyle("-fx-font-size: " + fontSize + "px;"); buffer.area.setWrapText(wrap.isSelected());
            buffer.area.editableProperty().bind(debugLocked.not());
            buffer.tab.setOnCloseRequest(event -> { event.consume(); closeBuffer(buffer, () -> {}); });
            buffers.put(document.path(), buffer); editors.getTabs().add(buffer.tab); editors.getSelectionModel().select(buffer.tab);
            applyBreakpoints(buffer); scheduleChecks(); after.accept(buffer);
            if (recovery != null) {
                RecoveryStore store = recovery;
                background(() -> store.read(document.path()), recovered -> {
                    if (epoch != workspaceGeneration || !buffers.containsValue(buffer)) return;
                    if (recovered.filter(text -> !text.equals(document.text())).isPresent()
                            && confirm("Recover unsaved edits?", "A recovery copy exists for " + document.path().getFileName() + ". Restore it into this editor tab?"))
                        buffer.area.replaceText(recovered.orElseThrow());
                });
            }
        }, failure -> { opening.remove(key); error(failure); });
    }
    private void refreshFiles() {
        if (workspace == null) return;
        if (files.getRoot() != null && files.getRoot().getValue().equals(workspace.root())) { refreshExpanded(files.getRoot()); return; }
        TreeItem<Path> tree = directoryItem(workspace.root()); files.setRoot(tree); tree.setExpanded(true);
    }
    private void refreshExpanded(TreeItem<Path> item) { if (item.isExpanded()) { item.getChildren().forEach(this::refreshExpanded); loadChildren(item); } }
    private TreeItem<Path> directoryItem(Path path) {
        TreeItem<Path> item = new TreeItem<>(path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            item.getChildren().add(new TreeItem<>());
            item.expandedProperty().addListener((obs, old, expanded) -> { if (expanded) loadChildren(item); });
        }
        return item;
    }
    private void loadChildren(TreeItem<Path> item) {
        long epoch = workspaceGeneration; boolean all = showGenerated.isSelected();
        background(() -> {
            try (var entries = Files.list(item.getValue())) {
                return entries.filter(path -> all || (!path.getFileName().toString().startsWith(".") && !WorkspaceIndex.GENERATED.contains(path.getFileName().toString())))
                        .sorted(Comparator.<Path, Boolean>comparing(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                                .thenComparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))).limit(10001).toList();
            }
        }, children -> {
            if (epoch != workspaceGeneration) return;
            Map<Path, TreeItem<Path>> existing = new HashMap<>(); item.getChildren().forEach(child -> existing.put(child.getValue(), child));
            item.getChildren().setAll(children.stream().limit(10000).map(path -> existing.containsKey(path) ? existing.get(path) : directoryItem(path)).toList());
            if (children.size() > 10000) status.setText("Directory view limited to 10,000 entries");
        });
    }
    private void newPath(boolean directory) {
        if (!requireWorkspace()) return;
        Path parent = workspace.root(); TreeItem<Path> selected = files.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null) parent = Files.isDirectory(selected.getValue()) ? selected.getValue() : selected.getValue().getParent();
        Path base = parent;
        ask(directory ? "New directory" : "New file", "Name relative to " + base.getFileName(), directory ? "src" : "untitled.lyra").ifPresent(name -> {
            Path target = base.resolve(name).normalize();
            if (name.isBlank() || !target.startsWith(base)) { error(new IOException("Choose a name inside the selected directory")); return; }
            background(() -> {
                if (directory) Files.createDirectory(target); else Files.writeString(target, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return target;
            }, created -> { refreshFiles(); if (!directory) openFile(created, ignored -> {}); });
        });
    }
    private void renameCurrent() {
        EditorBuffer buffer = active(); if (buffer == null || debugLocked.get()) return;
        ask("Rename file", "New file name", buffer.path().getFileName().toString()).ifPresent(name -> {
            Path old = buffer.path(), target = old.resolveSibling(name).normalize();
            if (name.isBlank() || !target.getParent().equals(old.getParent())) { error(new IOException("Enter a file name without a directory")); return; }
            if (old.equals(target)) return;
            saveBuffer(buffer, () -> background(() -> WorkspaceFiles.rename(old, target), moved -> {
                removeBuffer(buffer);
                if (workspace.entry().filter(entry -> entry.file().equals(old)).isPresent()) {
                    var entry = workspace.entry().orElseThrow(); workspace = workspace.withEntry(new WorkspaceSettings.RunTarget(moved, entry.function(), entry.arguments())); persistSettings();
                }
                refreshFiles(); openFile(moved, ignored -> {});
            }));
        });
    }

    private void documentChanged(Path file, String before, String after) {
        Set<Debugger.Breakpoint> rebased = new LinkedHashSet<>();
        for (var point : breakpoints) {
            int line = point.file().equals(file) ? TextEdits.rebaseLine(before, after, point.line()) : point.line();
            if (line > 0) rebased.add(new Debugger.Breakpoint(point.file(), line));
        }
        breakpoints.clear(); breakpoints.addAll(rebased);
        EditorBuffer buffer = buffers.get(file); if (buffer != null) applyBreakpoints(buffer);
        editRevision++; updateOutline(); scheduleChecks(); caretChanged();
    }
    private Map<Path, String> captureBuffers() {
        Map<Path, String> sources = new HashMap<>(); buffers.forEach((path, buffer) -> sources.put(path, buffer.area.getText())); return Map.copyOf(sources);
    }
    private void scheduleChecks() {
        if (workspace == null || disposed) return;
        long epoch = workspaceGeneration, revision = editRevision;
        Map<Path, String> sources = captureBuffers(); List<Path> roots = workspace.sourceRoots();
        for (EditorBuffer buffer : buffers.values()) {
            Future<?> previous = pendingChecks.remove(buffer.path()); if (previous != null) previous.cancel(false);
            if (!buffer.path().toString().endsWith(".lyra")) continue;
            String text = sources.get(buffer.path());
            pendingChecks.put(buffer.path(), checks.schedule(() -> {
                try {
                    var result = language.analyze(buffer.path(), text, roots, sources, true);
                    fx(() -> {
                        if (epoch != workspaceGeneration || revision != editRevision || !buffers.containsValue(buffer)) return;
                        buffer.applyAnalysis(result); analyses.put(buffer.path(), result); symbolIndex.put(buffer.path(), result); updateOutline(); updateProblems();
                        if (!busy.get()) status.setText(result.valid() ? "Checked · syntax, imports and types" : "Check found " + result.diagnostics().size() + " problem(s)");
                    });
                } catch (Exception failure) { fx(() -> { if (epoch == workspaceGeneration && revision == editRevision) error(failure); }); }
            }, 450, TimeUnit.MILLISECONDS));
        }
    }
    private void updateProblems() {
        List<Diagnostic> values = analyses.values().stream().flatMap(result -> result.diagnostics().stream()).distinct()
                .sorted(Comparator.comparing((Diagnostic d) -> d.primarySpan().sourceId().value()).thenComparingInt(d -> d.primarySpan().startOffset())).toList();
        problems.getItems().setAll(values); problemsTab.setText("Problems" + (values.isEmpty() ? "" : " (" + values.size() + ")"));
    }
    private void configureProblems() {
        problems.setId("problems-list"); problems.setPlaceholder(new Label("No problems in checked files"));
        problems.setCellFactory(view -> new ListCell<>() { @Override protected void updateItem(Diagnostic value, boolean empty) {
            super.updateItem(value, empty);
            setText(empty || value == null ? null : value.severity() + "  " + value.code() + "  " + value.summary() + "  ·  " + diagnosticLocation(value));
            setTooltip(empty || value == null ? null : new Tooltip(value.render()));
        }});
        problems.setOnMouseClicked(event -> { if (event.getClickCount() == 2) navigateDiagnostic(problems.getSelectionModel().getSelectedItem()); });
        problems.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) navigateDiagnostic(problems.getSelectionModel().getSelectedItem()); });
        searchResults.setCellFactory(view -> new ListCell<>() { @Override protected void updateItem(WorkspaceIndex.SearchHit hit, boolean empty) {
            super.updateItem(hit, empty); setText(empty || hit == null || workspace == null ? null : hit.label(workspace.root()));
        }});
        searchResults.setOnMouseClicked(event -> {
            var hit = searchResults.getSelectionModel().getSelectedItem();
            if (event.getClickCount() == 2 && hit != null) openFile(hit.file(), buffer -> buffer.select(hit.start(), hit.end()));
        });
    }
    private Path sourcePath(String identity) {
        try { return identity.startsWith("file:") ? Path.of(java.net.URI.create(identity)) : workspace.root().resolve(identity).normalize(); }
        catch (RuntimeException invalid) { return null; }
    }
    private String diagnosticLocation(Diagnostic diagnostic) {
        Path path = sourcePath(diagnostic.primarySpan().sourceId().value());
        EditorBuffer buffer = buffers.get(path);
        return (path == null ? diagnostic.primarySpan().sourceId().value() : path.getFileName())
                + (buffer == null ? " @" + diagnostic.primarySpan().startOffset() : ":" + TextEdits.lineAt(buffer.area.getText(), diagnostic.primarySpan().startOffset()));
    }
    private void navigateDiagnostic(Diagnostic diagnostic) {
        if (diagnostic == null) return; Path path = sourcePath(diagnostic.primarySpan().sourceId().value());
        if (path != null) openFile(path, buffer -> buffer.select(diagnostic.primarySpan().startOffset(), diagnostic.primarySpan().endOffset()));
    }
    private void updateActive() { updateOutline(); caretChanged(); }
    private void updateOutline() {
        EditorBuffer buffer = active(); var selection = outline.getSelectionModel().getSelectedItem();
        List<LanguageService.Definition> definitions = buffer == null || buffer.analysis == null || !buffer.analysis.text().equals(buffer.area.getText())
                ? List.of() : buffer.analysis.definitions();
        if (projectSymbols.isSelected()) definitions = symbolIndex.values().stream()
                .filter(result -> !buffers.containsKey(result.file()) || buffers.get(result.file()).area.getText().equals(result.text()))
                .sorted(Comparator.comparing(LanguageService.Analysis::file)).flatMap(result -> result.definitions().stream()).toList();
        outline.getItems().setAll(definitions.stream().filter(definition -> definition.name().toLowerCase(Locale.ROOT)
                .contains(symbolFilter.getText().toLowerCase(Locale.ROOT))).toList());
        if (selection != null) outline.getSelectionModel().select(outline.getItems().stream().filter(d -> d.file().equals(selection.file())
                && d.name().equals(selection.name()) && d.start() == selection.start()).findFirst().orElse(null));
    }
    private void caretChanged() {
        EditorBuffer buffer = active(); if (buffer == null) { position.setText("UTF-8  ·  Lyra"); return; }
        position.setText("Ln " + (buffer.area.getCurrentParagraph() + 1) + ", Col " + (buffer.area.getCaretColumn() + 1) + "  ·  UTF-8  ·  " + (debugLocked.get() ? "Debug source (read only)" : "Lyra"));
    }
    private LanguageService.Definition selectedFunction() {
        var selected = outline.getSelectionModel().getSelectedItem(); if (selected != null) return selected;
        EditorBuffer buffer = active(); if (buffer == null || buffer.analysis == null || !buffer.analysis.text().equals(buffer.area.getText())) return null;
        int caret = buffer.area.getCaretPosition();
        return buffer.analysis.definitions().stream().filter(d -> d.start() <= caret && caret <= d.end())
                .min(Comparator.comparingInt(d -> d.end() - d.start())).orElse(null);
    }
    private void navigateDefinition(LanguageService.Definition definition) {
        if (definition != null) openFile(definition.file(), buffer -> buffer.select(definition.nameOffset(), definition.nameOffset() + definition.name().length()));
    }

    private void saveCurrent(Runnable after) { if (active() != null) saveBuffer(active(), after); }
    private void saveBuffer(EditorBuffer buffer, Runnable after) {
        if (!buffer.dirty()) {
            if (buffer.conflict) { status.setText("Reload " + buffer.path().getFileName() + " to resolve its external change before continuing"); return; }
            after.run(); return;
        }
        String text = buffer.area.getText(); RecoveryStore store = recovery; long epoch = workspaceGeneration;
        boolean overwrite = buffer.conflict && confirm("Overwrite external changes?", "The file changed on disk. Replace that version with the current editor text?\n" + buffer.path());
        if (buffer.conflict && !overwrite) return;
        background(() -> {
            buffer.document.save(text, overwrite); if (store != null) store.remove(buffer.path()); return text;
        }, saved -> {
            if (epoch != workspaceGeneration || !buffers.containsValue(buffer)) return;
            buffer.conflict = false; buffer.title(); status.setText("Saved " + buffer.path().getFileName());
            if (buffer.dirty()) saveBuffer(buffer, after); else after.run();
        }, failure -> {
            if (epoch != workspaceGeneration || !buffers.containsValue(buffer)) return;
            if (failure instanceof FileSystemException) buffer.conflict = true;
            buffer.title(); error(failure);
        });
    }
    private void saveAll(Runnable after) { saveSequential(new ArrayList<>(buffers.values()), 0, after); }
    private void saveSequential(List<EditorBuffer> values, int index, Runnable after) {
        if (index == values.size()) {
            if (buffers.values().stream().anyMatch(EditorBuffer::dirty)) { status.setText("Edits changed during save · saving latest buffers…"); saveAll(after); }
            else after.run();
            return;
        }
        saveBuffer(values.get(index), () -> saveSequential(values, index + 1, after));
    }
    private void reloadCurrent() {
        EditorBuffer buffer = active(); if (buffer == null || debugLocked.get()) return;
        if (buffer.dirty() && !confirm("Discard unsaved edits?", "Reload " + buffer.path().getFileName() + " from disk?")) return;
        background(() -> new WorkspaceFiles.Document(buffer.path()), document -> {
            buffer.document = document; buffer.conflict = false; buffer.area.replaceText(document.text()); buffer.area.getUndoManager().forgetHistory(); buffer.title();
        });
    }
    private void checkExternalChanges() {
        if (workspace == null || disposed) return;
        long epoch = workspaceGeneration; List<EditorBuffer> captured = new ArrayList<>(buffers.values());
        background(() -> {
            List<EditorBuffer> changed = new ArrayList<>();
            for (EditorBuffer buffer : captured) if (buffer.document.changedOnDisk()) changed.add(buffer);
            return changed;
        }, changed -> {
            if (epoch != workspaceGeneration) return;
            changed.forEach(buffer -> { buffer.conflict = true; buffer.title(); });
            if (!changed.isEmpty()) status.setText("Files changed outside the editor · reload or resolve before saving");
            scheduleChecks(); refreshFiles(); if (projectSymbols.isSelected()) indexSymbols();
        });
    }
    private void closeBuffer(EditorBuffer buffer, Runnable after) {
        if (!buffer.dirty()) { removeBuffer(buffer); after.run(); return; }
        Optional<ButtonType> answer = saveQuestion("Save changes to " + buffer.path().getFileName() + "?");
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) return;
        if (answer.get() == ButtonType.YES) saveBuffer(buffer, () -> { removeBuffer(buffer); after.run(); });
        else { RecoveryStore store = recovery; if (store != null) background(() -> { store.remove(buffer.path()); return true; }, ignored -> {}); removeBuffer(buffer); after.run(); }
    }
    private void removeBuffer(EditorBuffer buffer) {
        buffers.remove(buffer.path()); analyses.remove(buffer.path()); editors.getTabs().remove(buffer.tab);
        Future<?> pending = pendingChecks.remove(buffer.path()); if (pending != null) pending.cancel(false); updateProblems();
    }
    private void saveBeforeLeaving(Runnable after) {
        if (buffers.values().stream().noneMatch(EditorBuffer::dirty)) { after.run(); return; }
        Optional<ButtonType> answer = saveQuestion("Save unsaved files before leaving this workspace?");
        if (answer.isEmpty() || answer.get() == ButtonType.CANCEL) return;
        if (answer.get() == ButtonType.YES) saveAll(after);
        else {
            RecoveryStore store = recovery; List<Path> paths = buffers.keySet().stream().toList();
            background(() -> { if (store != null) for (Path path : paths) store.remove(path); return true; }, ignored -> after.run());
        }
    }
    private void writeRecovery() {
        if (recovery == null || disposed) return;
        RecoveryStore store = recovery; Map<Path, String> dirty = new HashMap<>();
        buffers.values().stream().filter(EditorBuffer::dirty).forEach(buffer -> dirty.put(buffer.path(), buffer.area.getText()));
        if (!dirty.isEmpty()) background(() -> { for (var entry : dirty.entrySet()) store.write(entry.getKey(), entry.getValue()); return true; }, ignored -> {});
    }

    private void runFunction(boolean debug) {
        if (busy.get() || !requireWorkspace()) return;
        var definition = selectedFunction();
        if (definition == null || !definition.function()) { status.setText("Select a function in the definitions pane, or place the caret inside one"); return; }
        if (!definition.topLevel()) { info("Local function", "This function needs its enclosing lexical scope. Debug the enclosing top-level function, or evaluate a form that supplies that scope."); return; }
        chooseTarget(definition).ifPresent(target -> saveAll(() -> launchTarget(target, debug, definition)));
    }
    private Optional<WorkspaceSettings.RunTarget> chooseTarget(LanguageService.Definition definition) {
        String key = definition.file() + "#" + definition.name();
        String initial = functionArguments.getOrDefault(key, definition.name().equals("main") && definition.signature().replace(" ", "").contains("Array<String>") ? "Array<String>[]" : "");
        Optional<String> arguments = definition.parameters().isEmpty() && !definition.signature().contains("Fn<") ? Optional.of("")
                : definition.parameters().isEmpty() && definition.signature().replace(" ", "").contains("Fn<;") ? Optional.of("")
                : ask("Arguments for " + definition.name(), "Enter Lyra expressions, separated by commas.\n" + definition.signature(), initial);
        arguments.ifPresent(value -> functionArguments.put(key, value));
        return arguments.map(value -> new WorkspaceSettings.RunTarget(definition.file(), definition.name(), value));
    }
    private void setEntry() {
        if (!requireWorkspace()) return;
        var definition = selectedFunction();
        if (definition == null || !definition.function() || !definition.topLevel()) { status.setText("Select a top-level function to set the entry point"); return; }
        chooseTarget(definition).ifPresent(target -> { workspace = workspace.withEntry(target); persistSettings(); });
    }
    private void updateEntry() { entryName.setText(workspace == null ? "Choose an entry point" : workspace.entry().map(target -> target.file().getFileName() + " · " + target.function()).orElse("Select function → Set as entry")); }
    private void persistSettings() { WorkspaceSettings captured = workspace; background(() -> { captured.save(); return true; }, ignored -> { updateEntry(); status.setText("Project settings saved"); }); }
    private void runEntry(boolean debug) {
        if (busy.get() || !requireWorkspace()) return;
        if (workspace.entry().isEmpty()) {
            EditorBuffer buffer = active();
            var main = buffer == null || buffer.analysis == null ? Optional.<LanguageService.Definition>empty()
                    : buffer.analysis.definitions().stream().filter(d -> d.topLevel() && d.name().equals("main") && d.function()).findFirst();
            if (main.isEmpty()) { status.setText("Choose a function in Definitions, then use Set as entry"); return; }
            chooseTarget(main.get()).ifPresent(target -> { workspace = workspace.withEntry(target); persistSettings(); saveAll(() -> launchTarget(target, debug, main.get())); });
        } else saveAll(() -> launchTarget(workspace.entry().orElseThrow(), debug, null));
    }
    private void launchTarget(WorkspaceSettings.RunTarget target, boolean debug, LanguageService.Definition known) {
        if (busy.get()) return;
        WorkspaceSettings captured = workspace; Map<Path, String> sources = captureBuffers();
        final long generation, operation;
        final EditorRuntime previous;
        synchronized (runtimeLifecycle) {
            if (disposed) return;
            generation = ++runtimeGeneration; operation = ++operationId;
            previous = runtime; runtime = null;
        }
        busy.set(true); paused.set(false); debugLocked.set(debug);
        buffers.values().forEach(buffer -> buffer.setExecutionLine(-1)); frames.getItems().clear(); variables.getItems().clear();
        status.setText(debug ? "Starting debugger…" : "Starting program…"); bottom.getSelectionModel().select(replTab);
        appendOutput("\n── " + (debug ? "Debug" : "Run") + " " + target.file().getFileName() + " · " + target.invocation() + " ──\n");
        Set<Debugger.Breakpoint> points = Set.copyOf(breakpoints);
        execution.submit(() -> {
            try {
                if (previous != null) previous.close();
                if (operation != operationId || disposed) return;
                String source = WorkspaceFiles.decode(WorkspaceFiles.readBytes(target.file()));
                if (source.startsWith("\uFEFF")) source = source.substring(1);
                var checked = language.analyze(target.file(), source, captured.sourceRoots(), sources, true);
                if (!checked.valid()) {
                    fx(() -> { if (operation != operationId) return; analyses.put(target.file(), checked); updateProblems(); bottom.getSelectionModel().select(problemsTab); });
                    throw new IOException("Program has compiler errors. See Problems.");
                }
                var definition = checked.definitions().stream().filter(d -> d.topLevel() && d.function() && d.name().equals(target.function())).findFirst()
                        .orElseThrow(() -> new IOException("Entry function no longer exists: " + target.function()));
                EditorRuntime engine = startRuntime(captured, debug, generation);
                if (!publishRuntime(engine, operation, generation)) { engine.close(); return; }
                engine.debugger().ifPresent(controller -> controller.setBreakpoints(points));
                var loaded = engine.load(target.file()); present(loaded);
                if (loaded.status() == ConsoleSession.EvaluationStatus.SUCCESS) {
                    String text = source;
                    engine.debugger().ifPresent(controller -> {
                        if (definition.bodyOffset() == definition.start()) controller.stopAtNextFunction();
                        else controller.stopAtEntry(target.file(), TextEdits.lineAt(text, definition.bodyOffset()), TextEdits.lineAt(text, definition.end()));
                    });
                    var evaluated = engine.evaluate(target.invocation()); present(evaluated);
                    refreshBindings(engine, generation);
                }
                fx(() -> { if (operation == operationId) { busy.set(false); paused.set(false); status.setText("Evaluation finished · REPL retains this program's initialized definitions"); clearExecutionLines(); } });
            } catch (Exception failure) { finishFailure(operation, failure); }
        });
    }
    private EditorRuntime startRuntime(WorkspaceSettings captured, boolean debug, long generation) throws Exception {
        return EditorRuntime.start(captured, debug,
                text -> { if (generation == runtimeGeneration) appendOutput(text); },
                pause -> fx(() -> { if (generation == runtimeGeneration) onPause(pause); }),
                message -> fx(() -> { if (generation == runtimeGeneration) { status.setText(message); if (message.equals("Running")) { paused.set(false); clearExecutionLines(); } } }));
    }
    private void execute(String label, EngineAction action) {
        if (busy.get() || !requireWorkspace()) return;
        if (paused.get()) { status.setText("Continue or step the paused function before evaluating REPL source"); return; }
        WorkspaceSettings captured = workspace;
        final long operation;
        synchronized (runtimeLifecycle) { if (disposed) return; operation = ++operationId; }
        busy.set(true); status.setText(label + "…");
        bottom.getSelectionModel().select(replTab);
        execution.submit(() -> {
            try {
                EditorRuntime engine; long generation; boolean start;
                synchronized (runtimeLifecycle) {
                    if (operation != operationId || disposed) return;
                    engine = runtime; generation = runtimeGeneration;
                    start = engine == null || !engine.isAlive();
                    if (start) { runtime = null; generation = ++runtimeGeneration; }
                }
                if (start) {
                    if (engine != null) engine.close();
                    engine = startRuntime(captured, false, generation);
                    if (!publishRuntime(engine, operation, generation)) { engine.close(); return; }
                }
                Object result = action.run(engine);
                if (result instanceof ConsoleSession.Evaluation evaluation) present(evaluation);
                else if (result instanceof ConsoleSession.Query query) presentQuery(query);
                else if (result != null) appendOutput(result + "\n");
                refreshBindings(engine, generation);
                fx(() -> { if (operation == operationId) { busy.set(false); status.setText("Ready · REPL revision " + label); } });
            } catch (Exception failure) { finishFailure(operation, failure); }
        });
    }
    private boolean publishRuntime(EditorRuntime engine, long operation, long generation) {
        synchronized (runtimeLifecycle) {
            if (disposed || operation != operationId || generation != runtimeGeneration) return false;
            runtime = engine;
            return true;
        }
    }
    private void finishFailure(long operation, Exception failure) { fx(() -> { if (operation == operationId) { busy.set(false); paused.set(false); if (runtime == null) debugLocked.set(false); error(failure); } }); }
    private void present(ConsoleSession.Evaluation evaluation) {
        evaluation.diagnostics().forEach(diagnostic -> appendOutput(diagnostic.render() + "\n"));
        evaluation.value().ifPresent(value -> appendOutput("⇒ " + value.display() + "  : " + value.canonicalType() + "\n"));
        evaluation.detail().ifPresent(detail -> appendOutput(detail + "\n"));
        if (evaluation.status() != ConsoleSession.EvaluationStatus.SUCCESS) appendOutput("[" + evaluation.status() + "]\n");
    }
    private void presentQuery(ConsoleSession.Query query) {
        query.bindings().forEach(binding -> appendOutput(binding.name() + " : " + binding.canonicalType() + (binding.mutable() ? "  @mut" : "") + "\n"));
        query.inferredType().ifPresent(type -> appendOutput(type + "\n")); query.detail().ifPresent(detail -> appendOutput(detail + "\n"));
    }
    private void refreshBindings(EditorRuntime engine, long generation) {
        var result = engine.bindings(); fx(() -> { if (generation == runtimeGeneration) bindings.getItems().setAll(result.bindings().stream()
                .map(binding -> binding.name() + " : " + binding.canonicalType()).toList()); });
    }
    private void submitRepl() {
        if (busy.get()) { status.setText("An evaluation is active. Use program stdin, Interrupt, or debugger controls."); return; }
        String text = replInput.getText(); if (text.isBlank()) return;
        replInput.clear(); appendOutput("λ " + text + "\n");
        if (!isCommandLine(text)) {
            remember(text);
            execute("Evaluate", engine -> engine.evaluate(text));
            return;
        }
        String line = text.stripLeading();
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
        String command = line.substring(0, end);
        String argument = line.substring(end).stripLeading();
        switch (command) {
            case "\\help" -> {
                if (!argument.isBlank()) usage(command, 0);
                else appendOutput(PlainConsole.HELP_TEXT);
            }
            case "\\bindings" -> {
                if (!argument.isBlank()) usage(command, 0);
                else queryBindings();
            }
            case "\\type" -> {
                if (argument.isBlank()) appendOutput("LYR-REPL-USAGE: \\type expects a source argument\n");
                else execute("Type (no execution)", engine -> engine.type(argument));
            }
            case "\\load" -> {
                String path = oneCommandArgument(argument);
                if (path == null) usage(command, 1);
                else if (requireWorkspace()) {
                    Path target = workspace.root().resolve(path); execute("Load file", engine -> engine.load(target));
                }
            }
            case "\\reload" -> {
                String module = oneCommandArgument(argument);
                if (module == null) usage(command, 1);
                else execute("Reload module", engine -> engine.reload(module));
            }
            case "\\reset" -> {
                if (!argument.isBlank()) usage(command, 0);
                else execute("Reset", this::resetRepl);
            }
            case "\\history" -> {
                if (!argument.isBlank()) usage(command, 0);
                else for (int i = 0; i < history.size(); i++) appendOutput((i + 1) + "  " + history.get(i) + "\n");
            }
            case "\\quit" -> {
                if (!argument.isBlank()) usage(command, 0);
                else stop();
            }
            default -> appendOutput("LYR-REPL-USAGE: unknown command: " + command + "\n");
        }
    }
    private static boolean isCommandLine(String source) {
        return source.stripLeading().startsWith("\\");
    }
    private void usage(String command, int arguments) {
        appendOutput("LYR-REPL-USAGE: " + command + " expects " + arguments
                + " argument" + (arguments == 1 ? "" : "s") + "\n");
    }
    private static String oneCommandArgument(String text) {
        StringBuilder value = new StringBuilder();
        char quote = 0;
        boolean escaped = false, token = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) { value.append(character); escaped = false; token = true; continue; }
            if (character == 92) { escaped = true; token = true; continue; }
            if (quote != 0) {
                if (character == quote) quote = 0; else value.append(character);
                token = true; continue;
            }
            if (character == '"' || character == 39) { quote = character; token = true; continue; }
            if (Character.isWhitespace(character)) {
                if (token) return index == text.stripTrailing().length() ? value.toString() : null;
            } else { value.append(character); token = true; }
        }
        return escaped || quote != 0 || !token ? null : value.toString();
    }
    private ConsoleSession.ControlStatus resetRepl(EditorRuntime engine) {
        ConsoleSession.Control reset = engine.reset();
        if (reset.status() == ConsoleSession.ControlStatus.OK) {
            fx(this::clearSourceHistory);
        }
        return reset.status();
    }
    private void clearSourceHistory() {
        history.clear();
        historyCursor = 0;
        historyDraft = "";
    }
    private void remember(String text) { if (history.isEmpty() || !history.getLast().equals(text)) history.add(text); if (history.size() > 500) history.removeFirst(); historyCursor = history.size(); }
    private void evaluateSelection() {
        if (replInput.isFocused()) { submitRepl(); return; }
        EditorBuffer buffer = active(); if (buffer == null) return;
        int start = buffer.area.getSelection().getStart(), end = buffer.area.getSelection().getEnd();
        if (start == end && buffer.analysis != null && buffer.analysis.text().equals(buffer.area.getText()) && buffer.analysis.syntax().isPresent()) {
            int caret = start;
            var form = buffer.analysis.syntax().orElseThrow().forms().stream().filter(node -> node.span().startOffset() <= caret && caret <= node.span().endOffset()).findFirst();
            if (form.isPresent()) { start = form.get().span().startOffset(); end = form.get().span().endOffset(); }
        }
        if (start == end) { status.setText("Select source to evaluate, or place the caret inside a complete top-level form"); return; }
        evaluateRange(buffer, start, end);
    }
    private void evaluateDefinition() {
        var definition = selectedFunction(); if (definition == null) return;
        openFile(definition.file(), buffer -> {
            String text = buffer.area.getText(); long version = buffer.version;
            List<Path> roots = workspace.sourceRoots(); Map<Path, String> sources = captureBuffers();
            checks.submit(() -> {
                var fresh = language.analyze(buffer.path(), text, roots, sources, false);
                fx(() -> {
                    if (buffer.version != version) { status.setText("Source changed · select the definition again"); return; }
                    fresh.definitions().stream().filter(d -> d.name().equals(definition.name()) && d.topLevel() == definition.topLevel())
                            .min(Comparator.comparingInt(d -> Math.abs(d.start() - definition.start())))
                            .ifPresent(d -> evaluateRange(buffer, d.start(), d.end()));
                });
            });
        });
    }
    private void evaluateRange(EditorBuffer buffer, int start, int end) {
        String text = buffer.area.getText(start, end); remember(text); appendOutput("λ " + text + "\n");
        EvaluationSource source = new EvaluationSource(new SourceOrigin(buffer.path().getFileName().toString(), Optional.of(buffer.path().toUri()), Optional.of(buffer.version), start, end), text);
        execute("Evaluate selection", engine -> engine.evaluate(source));
    }
    private void loadCurrent() { EditorBuffer buffer = active(); if (buffer != null) saveAll(() -> execute("Load " + buffer.path().getFileName(), engine -> engine.load(buffer.path()))); }
    private void queryBindings() { execute("Bindings", EditorRuntime::bindings); }
    private void queryType() {
        String text = !replInput.getText().isBlank() ? replInput.getText() : active() == null ? "" : active().area.getSelectedText();
        if (text.isBlank()) { status.setText("Select an expression or enter one in the REPL to inspect its type"); return; }
        execute("Type (no execution)", engine -> engine.type(text));
    }
    private void cancel() { control(EditorRuntime::cancel); status.setText("Interrupt requested; Stop terminates the process"); }
    private void sendProgramInput() { String text = programInput.getText(); control(engine -> engine.input(text)); programInput.clear(); }
    private void control(EngineControl action) {
        EditorRuntime engine = runtime; if (engine == null) { status.setText("No running process"); return; }
        controls.submit(() -> { try { action.run(engine); } catch (Exception failure) { fx(() -> error(failure)); } });
    }
    private void step(int depth) { control(engine -> engine.debugger().ifPresent(controller -> controller.step(depth))); }
    private void resume() { control(engine -> engine.debugger().ifPresent(Debugger::resume)); }
    private void onPause(Debugger.Pause pause) {
        paused.set(true); status.setText(pause.reason() + " · F7 Into · F8 Over · F9 Continue");
        frames.getItems().setAll(pause.frames().stream().map(frame -> {
            var analysis = analyses.get(frame.file()); if (analysis == null) return frame;
            var definition = analysis.definitions().stream().filter(d -> d.function() && TextEdits.lineAt(analysis.text(), d.start()) <= frame.line()
                    && frame.line() <= TextEdits.lineAt(analysis.text(), d.end())).min(Comparator.comparingInt(d -> d.end() - d.start()));
            if (definition.isEmpty()) return frame;
            var selected = definition.orElseThrow(); Map<String, String> values = new LinkedHashMap<>(frame.variables());
            for (int i = 0; i < selected.parameters().size(); i++) {
                String value = values.remove("argument " + (i + 1)); if (value != null) values.put(selected.parameters().get(i), value);
            }
            return new Debugger.Frame(frame.file(), frame.line(), selected.name(), values);
        }).toList());
        bottom.getSelectionModel().select(debugTab); frames.getSelectionModel().selectFirst();
    }
    private void toggleBreakpoint(Path file, int line) {
        Debugger.Breakpoint point = new Debugger.Breakpoint(file, line);
        if (!breakpoints.remove(point)) breakpoints.add(point);
        EditorBuffer buffer = buffers.get(file); if (buffer != null) applyBreakpoints(buffer);
        Set<Debugger.Breakpoint> values = Set.copyOf(breakpoints);
        EditorRuntime engine = runtime; if (engine != null) controls.submit(() -> engine.debugger().ifPresent(controller -> controller.setBreakpoints(values)));
        status.setText("Breakpoint " + (breakpoints.contains(point) ? "set" : "removed") + " at " + file.getFileName() + ":" + line + " · takes effect on executable lines");
    }
    private void applyBreakpoints(EditorBuffer buffer) { buffer.setBreakpoints(breakpoints.stream().filter(point -> point.file().equals(buffer.path())).map(Debugger.Breakpoint::line).collect(java.util.stream.Collectors.toSet())); }
    private void clearExecutionLines() { buffers.values().forEach(buffer -> buffer.setExecutionLine(-1)); }
    private void stop() {
        final EditorRuntime engine;
        synchronized (runtimeLifecycle) {
            runtimeGeneration++; operationId++; engine = runtime; runtime = null;
        }
        if (engine != null) controls.submit(engine::close);
        busy.set(false); paused.set(false); debugLocked.set(false); clearExecutionLines(); frames.getItems().clear(); variables.getItems().clear(); bindings.getItems().clear();
        status.setText("Process stopped · editor buffers preserved");
    }
    private void attach() {
        if (busy.get() || !requireWorkspace()) return;
        ask("Attach to application REPL", "Loopback endpoint printed by lyra run --repl or your Java host", "127.0.0.1:").ifPresent(address -> {
            final java.net.URI endpoint;
            try {
                endpoint = java.net.URI.create("tcp://" + address.strip());
                if (endpoint.getHost() == null || endpoint.getPort() < 1) throw new IllegalArgumentException("Enter a loopback host and port");
            } catch (IllegalArgumentException invalid) { error(invalid); return; }
            stop();
            final long operation, generation;
            synchronized (runtimeLifecycle) { if (disposed) return; operation = ++operationId; generation = runtimeGeneration; }
            busy.set(true);
            execution.submit(() -> {
                try {
                    EditorRuntime engine = EditorRuntime.attach(endpoint.getHost(), endpoint.getPort());
                    if (!publishRuntime(engine, operation, generation)) { engine.close(); return; }
                    refreshBindings(engine, generation);
                    fx(() -> { if (operation == operationId) { busy.set(false); status.setText("Attached to " + address + " · Stop detaches"); appendOutput("Attached to " + engine.endpoint().display() + "\n"); } });
                } catch (Exception failure) { finishFailure(operation, failure); }
            });
        });
    }

    private void quickOpen() {
        if (!requireWorkspace()) return; Path root = workspace.root();
        background(() -> WorkspaceIndex.sources(root), paths -> chooseSearchable("Quick open", paths, path -> root.relativize(path).toString(), path -> openFile(path, ignored -> {})));
    }
    private void indexSymbols() {
        if (workspace == null) return;
        if (symbolIndexTask != null) symbolIndexTask.cancel(false);
        WorkspaceSettings captured = workspace; Map<Path, String> sources = captureBuffers(); long epoch = workspaceGeneration, revision = editRevision;
        symbolIndexTask = checks.submit(() -> {
            try {
                Map<Path, LanguageService.Analysis> results = new LinkedHashMap<>();
                for (Path path : WorkspaceIndex.sources(captured.root())) {
                    if (Thread.currentThread().isInterrupted()) return;
                    String text = sources.containsKey(path) ? sources.get(path) : WorkspaceFiles.decode(WorkspaceFiles.readBytes(path));
                    results.put(path, language.analyze(path, text, captured.sourceRoots(), sources, false));
                }
                fx(() -> { if (epoch == workspaceGeneration && revision == editRevision) {
                    symbolIndex.clear(); symbolIndex.putAll(results); analyses.forEach((path, checked) -> { if (results.containsKey(path) && results.get(path).text().equals(checked.text())) symbolIndex.put(path, checked); }); updateOutline();
                }});
            } catch (Exception failure) { fx(() -> error(failure)); }
        });
    }
    private void findInProject() {
        if (!requireWorkspace()) return;
        ask("Find in project", "Search Lyra files (up to 1,000 results)", "").filter(query -> !query.isEmpty()).ifPresent(query -> {
            Path root = workspace.root(); Map<Path, String> sources = captureBuffers(); long epoch = workspaceGeneration;
            background(() -> WorkspaceIndex.search(root, query, sources), results -> {
                if (epoch != workspaceGeneration) return;
                searchResults.getItems().setAll(results); searchTab.setText("Search (" + results.size() + ")"); bottom.getSelectionModel().select(searchTab);
            });
        });
    }
    private void findDefinition() {
        EditorBuffer buffer = active(); if (buffer == null || !requireWorkspace()) return;
        if (buffer.analysis != null && buffer.analysis.text().equals(buffer.area.getText())) {
            int caret = buffer.area.getCaretPosition();
            var exact = buffer.analysis.navigation().stream().filter(link -> link.start() <= caret && caret <= link.end())
                    .min(Comparator.comparingInt(link -> link.end() - link.start()));
            if (exact.isPresent()) { var link = exact.orElseThrow(); openFile(link.target(), target -> target.select(link.targetStart(), link.targetEnd())); return; }
        }
        String word = TextEdits.wordAt(buffer.area.getText(), buffer.area.getCaretPosition());
        Map<Path, String> sources = captureBuffers(); WorkspaceSettings captured = workspace;
        background(() -> {
            List<LanguageService.Definition> definitions = new ArrayList<>();
            for (Path path : WorkspaceIndex.sources(captured.root())) {
                String text = sources.containsKey(path) ? sources.get(path) : WorkspaceFiles.decode(WorkspaceFiles.readBytes(path));
                definitions.addAll(language.analyze(path, text, captured.sourceRoots(), sources, false).definitions());
            }
            return definitions.stream().filter(definition -> word.isEmpty() || definition.name().equals(word)).toList();
        }, definitions -> {
            if (definitions.size() == 1) navigateDefinition(definitions.getFirst());
            else chooseSearchable("Find definition" + (word.isEmpty() ? "" : " · " + word), definitions,
                    definition -> definition.label() + "  ·  " + captured.root().relativize(definition.file()), this::navigateDefinition);
        });
    }
    private void complete() {
        EditorBuffer buffer = active(); if (buffer == null || !buffer.area.isEditable()) return;
        int caret = buffer.area.getCaretPosition(), start = TextEdits.wordStart(buffer.area.getText(), caret);
        String prefix = buffer.area.getText(start, caret);
        TreeSet<String> names = new TreeSet<>(List.of("let", "import", "as", "Array", "Tuple", "Fn", "String", "I32", "I64", "F32", "F64", "Bool", "Char", "Unit", "@pub", "@mut", "@nil"));
        if (buffer.analysis != null) buffer.analysis.definitions().forEach(definition -> { names.add(definition.name()); names.addAll(definition.parameters()); });
        bindings.getItems().forEach(binding -> names.add(binding.split(" : ", 2)[0]));
        ContextMenu popup = new ContextMenu();
        names.stream().filter(name -> name.startsWith(prefix) && !name.equals(prefix)).limit(40).forEach(name -> {
            MenuItem item = new MenuItem(name); item.setOnAction(e -> buffer.area.replaceText(start, caret, name)); popup.getItems().add(item);
        });
        if (popup.getItems().isEmpty()) { status.setText("No matching symbols"); return; }
        buffer.area.getCaretBounds().ifPresent(bounds -> popup.show(buffer.area, bounds.getMinX(), bounds.getMaxY()));
    }
    private void goToLine() { withBuffer(buffer -> ask("Go to line", "Line number", "1").ifPresent(text -> {
        try { int line = Integer.parseInt(text.strip()); if (line < 1) throw new NumberFormatException(); buffer.goToLine(line); }
        catch (NumberFormatException invalid) { status.setText("Enter a positive line number"); }
    })); }
    private void checkProject() {
        if (!requireWorkspace()) return;
        WorkspaceSettings captured = workspace; Map<Path, String> sources = captureBuffers(); long epoch = workspaceGeneration, revision = editRevision;
        status.setText("Checking project…");
        checks.submit(() -> {
            try {
                Map<Path, LanguageService.Analysis> results = new LinkedHashMap<>();
                for (Path path : WorkspaceIndex.sources(captured.root())) {
                    if (Thread.currentThread().isInterrupted()) return;
                    String source = sources.containsKey(path) ? sources.get(path) : WorkspaceFiles.decode(WorkspaceFiles.readBytes(path));
                    results.put(path, language.analyze(path, source, captured.sourceRoots(), sources, true));
                }
                fx(() -> { if (epoch != workspaceGeneration || revision != editRevision) return; analyses.putAll(results); updateProblems();
                    status.setText("Checked " + results.size() + " source files · " + problems.getItems().size() + " problems"); bottom.getSelectionModel().select(problemsTab); });
            } catch (Exception failure) { fx(() -> error(failure)); }
        });
    }
    private void buildJar() {
        if (!requireWorkspace()) return;
        Path source = workspace.entry().map(WorkspaceSettings.RunTarget::file).orElseGet(() -> active() == null ? null : active().path());
        if (source == null) { status.setText("Open a source file or configure an entry point"); return; }
        FileChooser chooser = new FileChooser(); chooser.setTitle("Build standalone application JAR"); chooser.setInitialFileName("app.jar");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java archive", "*.jar"));
        java.io.File chosen = chooser.showSaveDialog(stage); if (chosen == null) return;
        Path output = chosen.toPath(); boolean replace = Files.exists(output);
        if (replace && !confirm("Replace JAR?", "Replace the existing file at " + output + "?")) return;
        List<Path> roots = workspace.sourceRoots();
        saveAll(() -> background(() -> {
            CompileResult result = LyraCompiler.compile(CompileRequest.builder().root(source).sourceRoots(roots).includeSources(true).build());
            if (result instanceof CompileResult.Failure failure) throw new IOException(String.join("\n", failure.diagnostics().stream().map(Diagnostic::render).toList()));
            ((CompileResult.Success) result).artifact().writeJar(output, JarMode.BUNDLED, new WriteOptions(replace));
            return output;
        }, built -> { status.setText("Built " + built); appendOutput("Built " + built + "\nRun with Java 25: java --enable-preview -jar \"" + built + "\"\n"); }));
    }
    private void configureRoots() {
        if (!requireWorkspace()) return;
        Dialog<String> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("Source roots");
        TextArea input = new TextArea(String.join("\n", workspace.sourceRoots().stream().map(Path::toString).toList()));
        input.setPrefRowCount(6); dialog.getDialogPane().setContent(new VBox(10, new Label("One directory per line. Relative paths use the project directory."), input));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); dialog.setResultConverter(button -> button == ButtonType.OK ? input.getText() : null);
        dialog.showAndWait().ifPresent(text -> {
            WorkspaceSettings captured = workspace;
            background(() -> {
                List<Path> roots = new ArrayList<>();
                for (String line : text.split("\n")) if (!line.isBlank()) { Path path = captured.root().resolve(line.strip()).toRealPath(); if (!Files.isDirectory(path)) throw new IOException("Not a directory: " + path); roots.add(path); }
                if (roots.isEmpty()) roots.add(captured.root());
                WorkspaceSettings next = captured.withRoots(roots.stream().distinct().toList()); next.save(); return next;
            }, next -> { workspace = next; stop(); editRevision++; scheduleChecks(); status.setText("Source roots updated · next evaluation starts a new REPL"); });
        });
    }
    private void commandPalette() {
        record Command(String label, Runnable action) { }
        var commands = List.of(new Command("Open directory", this::chooseDirectory), new Command("Quick open file", this::quickOpen),
                new Command("Find definition", this::findDefinition), new Command("Run entry", () -> runEntry(false)),
                new Command("Debug entry", () -> runEntry(true)), new Command("Run function", () -> runFunction(false)),
                new Command("Set entry point", this::setEntry), new Command("Load file into REPL", this::loadCurrent),
                new Command("Check project", this::checkProject), new Command("Build application JAR", this::buildJar),
                new Command("Source roots", this::configureRoots), new Command("Stop process", this::stop));
        chooseSearchable("Commands", commands, Command::label, command -> command.action().run());
    }
    private <T> void chooseSearchable(String title, List<T> values, Function<T, String> label, Consumer<T> chosen) {
        Dialog<T> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle(title); dialog.setResizable(true);
        TextField filter = new TextField(); filter.setPromptText("Type to filter…");
        ListView<T> list = new ListView<>(FXCollections.observableArrayList(values)); list.setPrefSize(720, 400);
        list.setCellFactory(view -> new ListCell<>() { @Override protected void updateItem(T value, boolean empty) { super.updateItem(value, empty); setText(empty || value == null ? null : label.apply(value)); } });
        filter.textProperty().addListener((o, previous, text) -> { list.getItems().setAll(values.stream().filter(value -> label.apply(value).toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))).toList()); list.getSelectionModel().selectFirst(); });
        list.getSelectionModel().selectFirst(); dialog.getDialogPane().setContent(new VBox(8, filter, list));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? list.getSelectionModel().getSelectedItem() : null);
        list.setOnMouseClicked(event -> { if (event.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) { dialog.setResult(list.getSelectionModel().getSelectedItem()); dialog.close(); } });
        dialog.setOnShown(event -> filter.requestFocus()); dialog.showAndWait().ifPresent(chosen);
    }

    private void applyTheme() { root.getStyleClass().remove("light"); if (light.isSelected()) root.getStyleClass().add("light"); preferences.putBoolean("light", light.isSelected()); }
    private void zoom(int delta) { fontSize = Math.clamp(fontSize + delta, 10, 28); buffers.values().forEach(buffer -> buffer.area.setStyle("-fx-font-size: " + fontSize + "px;")); }
    private boolean requireWorkspace() { if (workspace != null) return true; status.setText("Open a project directory first"); return false; }
    private EditorBuffer active() { Tab tab = editors.getSelectionModel().getSelectedItem(); return tab != null && tab.getContent() instanceof EditorBuffer buffer ? buffer : null; }
    private void withBuffer(Consumer<EditorBuffer> action) { EditorBuffer buffer = active(); if (buffer != null) action.accept(buffer); }
    private void withEditableBuffer(Consumer<EditorBuffer> action) { withBuffer(buffer -> { if (buffer.area.isEditable()) action.accept(buffer); }); }
    private static String unquote(String text) { return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"") ? text.substring(1, text.length() - 1) : text; }
    private static Tab fixedTab(String name, Node node) { Tab tab = new Tab(name, node); tab.setClosable(false); return tab; }
    private static HBox panelHeading(String text, Node... actions) {
        Label heading = new Label(text); Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        HBox box = new HBox(6, heading, space); box.getChildren().addAll(actions); box.setAlignment(Pos.CENTER_LEFT); box.getStyleClass().add("panel-heading"); return box;
    }
    private static Button button(String name, String id, Runnable action) { Button button = new Button(name); button.setId(id); button.setOnAction(event -> action.run()); button.setFocusTraversable(false); return button; }
    private static MenuItem item(String name, String accelerator, Runnable action) {
        MenuItem item = new MenuItem(name); if (accelerator != null) item.setAccelerator(KeyCombination.keyCombination(accelerator));
        item.setOnAction(event -> action.run()); return item;
    }
    private Optional<String> ask(String title, String prompt, String value) {
        TextInputDialog dialog = new TextInputDialog(value); dialog.initOwner(stage); dialog.setTitle(title); dialog.setHeaderText(prompt); return dialog.showAndWait();
    }
    private boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(title);
        return alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
    }
    private Optional<ButtonType> saveQuestion(String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO, ButtonType.CANCEL); alert.initOwner(stage); alert.setTitle("Unsaved changes"); alert.setHeaderText("Save changes?");
        ((Button) alert.getDialogPane().lookupButton(ButtonType.YES)).setText("Save"); ((Button) alert.getDialogPane().lookupButton(ButtonType.NO)).setText("Discard"); return alert.showAndWait();
    }
    private void info(String title, String text) { Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(title); alert.showAndWait(); }
    private void error(Throwable failure) { String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); status.setText(message); appendOutput("Error: " + message + "\n"); }
    private <T> void background(Callable<T> work, Consumer<T> success) { background(work, success, this::error); }
    private <T> void background(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
        if (disposed) return;
        disk.submit(() -> {
            try {
                T value = work.call();
                if (disposed) { closeUnused(value); return; }
                try { Platform.runLater(() -> { if (disposed) closeUnused(value); else success.accept(value); }); }
                catch (IllegalStateException stopped) { closeUnused(value); }
            } catch (Exception problem) { fx(() -> failure.accept(problem)); }
        });
    }
    private static void closeUnused(Object value) {
        if (value instanceof AutoCloseable resource) try { resource.close(); } catch (Exception ignored) { }
    }
    private void fx(Runnable action) { if (!disposed) Platform.runLater(() -> { if (!disposed) action.run(); }); }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    private void appendOutput(String text) { synchronized (pendingOutput) {
        if (text.length() > 100000) text = text.substring(text.length() - 100000);
        if (pendingOutput.length() + text.length() > 200000) pendingOutput.delete(0, Math.min(pendingOutput.length(), pendingOutput.length() + text.length() - 200000));
        pendingOutput.append(text);
    }}
    private void flushOutput() {
        String text; synchronized (pendingOutput) { if (pendingOutput.isEmpty()) return; text = pendingOutput.toString(); pendingOutput.setLength(0); }
        transcript.appendText(text); if (transcript.getLength() > 250000) transcript.deleteText(0, transcript.getLength() - 200000);
    }
    private void requestClose() {
        if (closing || disposed) return;
        saveBeforeLeaving(() -> { closing = true; preferences.putDouble("width", stage.getWidth()); preferences.putDouble("height", stage.getHeight()); dispose(); stage.hide(); Platform.exit(); });
    }
    public void dispose() {
        final EditorRuntime engine;
        synchronized (runtimeLifecycle) {
            if (disposed) return; disposed = true; runtimeGeneration++; operationId++;
            engine = runtime; runtime = null;
        }
        outputTimer.stop(); recoveryTimer.stop(); closeWatcher(); checks.shutdownNow(); disk.shutdown(); execution.shutdownNow();
        if (engine != null) controls.submit(engine::close); controls.shutdown();
    }
    private void closeWatcher() { if (watcher != null) { try { watcher.close(); } catch (IOException failure) { error(failure); } watcher = null; } }
    @FunctionalInterface private interface EngineAction { Object run(EditorRuntime runtime) throws Exception; }
    @FunctionalInterface private interface EngineControl { void run(EditorRuntime runtime) throws Exception; }
}
