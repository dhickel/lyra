package io.mindspice.lyra.repl;

import io.mindspice.lyra.compiler.source.ResolvedSource;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleReloadTest {
    @Test
    void reloadUsesFreshSourceWhileOldSelectiveValueRemainsBound() {
        AtomicReference<String> sourceText = new AtomicReference<>(
                "let @pub value :I32 = 10");
        var sourceUri = URI.create("memory://reload-counter.lyra");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical ->
                logical.value().equals("counter")
                        ? java.util.Optional.of(ResolvedSource.memory(
                                logical, sourceUri, sourceText.get()))
                        : java.util.Optional.empty();

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import counter"));
            success(session.submit("selected.lyra", "import counter->{value as oldValue}"));
            assertEquals("10", scalar(session.submit("before.lyra", "counter->:.value")));
            assertEquals("10", scalar(session.submit("before-selected.lyra", "oldValue")));

            sourceText.set("let @pub value :I32 = 20");
            assertInstanceOf(EvaluationResult.Success.class, session.reload("counter"));

            assertEquals("20", scalar(session.submit("after.lyra", "counter->:.value")));
            assertEquals("10", scalar(session.submit("after-selected.lyra", "oldValue")));
        }
    }

    @Test
    void reloadReportsScheduledAttemptedAndCompletedInitializers() {
        AtomicReference<String> sourceText = new AtomicReference<>(
                "let @pub first :I32 = 10\nlet @pub second :I32 = 20");
        var logical = io.mindspice.lyra.compiler.source.LogicalModuleId.of("progress");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested ->
                requested.equals(logical)
                        ? java.util.Optional.of(ResolvedSource.memory(
                                logical, URI.create("memory://reload-progress.lyra"), sourceText.get()))
                        : java.util.Optional.empty();

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            var initial = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("import.lyra", "import progress"));
            assertEquals(2, initial.initializerProgress().scheduled().size());
            assertEquals(2, initial.initializerProgress().attempted().size(),
                    initial.initializerProgress().toString());
            assertEquals(2, initial.initializerProgress().completed().size(),
                    initial.initializerProgress().toString());

            sourceText.set("let @pub first :I32 = 30\nlet @pub second :I32 = 40");
            var reloaded = assertInstanceOf(EvaluationResult.Success.class, session.reload(logical));
            assertEquals(2, reloaded.initializerProgress().scheduled().size());
            assertEquals(reloaded.initializerProgress().scheduled(),
                    reloaded.initializerProgress().attempted());
            assertEquals(reloaded.initializerProgress().scheduled(),
                    reloaded.initializerProgress().completed());
        }
    }

    @Test
    void failedReloadReportsTheInitializerThatWasAttemptedButDidNotComplete() {
        AtomicReference<String> sourceText = new AtomicReference<>(
                "let @pub value :I32 = 10");
        var logical = io.mindspice.lyra.compiler.source.LogicalModuleId.of("partial_progress");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested ->
                requested.equals(logical)
                        ? java.util.Optional.of(ResolvedSource.memory(
                                logical, URI.create("memory://partial-progress.lyra"), sourceText.get()))
                        : java.util.Optional.empty();

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import partial_progress"));
            sourceText.set("let @pub first :I32 = 20\n"
                    + "let zero :I32 = 0\nlet @pub second :I32 = (% 1 zero)");
            var failed = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.reload(logical));
            var progress = failed.initializerProgress();
            assertEquals(3, progress.scheduled().size());
            assertEquals(3, progress.attempted().size());
            assertEquals(2, progress.completed().size());
            assertEquals(progress.scheduled().subList(0, 2), progress.completed());
            assertEquals(progress.scheduled().getLast(), progress.attempted().getLast());
        }
    }

    @Test
    void reloadAcceptsACommittedNamespaceAlias() {
        AtomicReference<String> sourceText = new AtomicReference<>(
                "let @pub value :I32 = 10");
        var logical = io.mindspice.lyra.compiler.source.LogicalModuleId.of("counter");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested ->
                requested.equals(logical)
                        ? java.util.Optional.of(ResolvedSource.memory(
                                logical, URI.create("memory://alias-reload-counter.lyra"), sourceText.get()))
                        : java.util.Optional.empty();

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import counter as current"));
            sourceText.set("let @pub value :I32 = 20");
            success(session.reload("current"));
            assertEquals("20", scalar(session.submit("read.lyra", "current->:.value")));
        }
    }

    @Test
    void failedReloadKeepsThePublishedDefaultAndCanBeRetried() {
        AtomicReference<String> sourceText = new AtomicReference<>(
                "let @pub value :I32 = 10");
        var logical = io.mindspice.lyra.compiler.source.LogicalModuleId.of("counter");
        var resolverCalls = new java.util.concurrent.atomic.AtomicInteger();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested -> {
            if (!requested.equals(logical)) return java.util.Optional.empty();
            resolverCalls.incrementAndGet();
            return java.util.Optional.of(ResolvedSource.memory(
                    logical, URI.create("memory://reload-failure-counter.lyra"), sourceText.get()));
        };

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import counter"));
            assertEquals("10", scalar(session.submit("before.lyra", "counter->:.value")));
            var published = session.workspaceState();

            sourceText.set("let zero :I32 = 0\nlet @pub value :I32 = (% 1 zero)");
            assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.reload(logical));
            assertEquals(published, session.workspaceState());
            assertEquals("10", scalar(session.submit("after-failed-reload.lyra", "counter->:.value")));

            sourceText.set("let @pub value :I32 = 20");
            success(session.reload(logical));
            assertEquals("20", scalar(session.submit("after-retry.lyra", "counter->:.value")));
            assertEquals(3, resolverCalls.get());
        }
    }

    @Test
    void reloadReadsFreshDependenciesButLeavesOldSelectiveValuesBound() {
        AtomicReference<String> baseText = new AtomicReference<>(
                "let @pub value :I32 = 10");
        AtomicReference<String> counterText = new AtomicReference<>(
                "import base\nlet @pub value :I32 = base->:.value");
        var base = io.mindspice.lyra.compiler.source.LogicalModuleId.of("base");
        var counter = io.mindspice.lyra.compiler.source.LogicalModuleId.of("counter");
        var resolverCalls = new java.util.concurrent.atomic.AtomicInteger();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested -> {
            if (requested.equals(base)) {
                resolverCalls.incrementAndGet();
                return java.util.Optional.of(ResolvedSource.memory(
                        base, URI.create("memory://reload-base.lyra"), baseText.get()));
            }
            if (requested.equals(counter)) {
                resolverCalls.incrementAndGet();
                return java.util.Optional.of(ResolvedSource.memory(
                        counter, URI.create("memory://reload-counter.lyra"), counterText.get()));
            }
            return java.util.Optional.empty();
        };

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import counter"));
            success(session.submit("select.lyra", "import counter->{value as oldValue}"));
            assertEquals("10", scalar(session.submit("before.lyra", "counter->:.value")));
            assertEquals("10", scalar(session.submit("before-selected.lyra", "oldValue")));

            baseText.set("let @pub value :I32 = 20");
            success(session.reload(counter));
            assertEquals("20", scalar(session.submit("after.lyra", "counter->:.value")));
            assertEquals("10", scalar(session.submit("after-selected.lyra", "oldValue")));
            assertEquals(4, resolverCalls.get(),
                    "initial counter/base and the reload counter/base are each resolved once");
        }
    }

    @Test
    void reloadLeavesAnUnaffectedDefaultOnItsOriginalProducer() {
        AtomicReference<String> counterText = new AtomicReference<>(
                "let @pub value :I32 = 10");
        AtomicReference<String> otherText = new AtomicReference<>(
                "import std->io io->::println[\"other-init\"]\n"
                        + "let @pub value :I32 = 30");
        var counter = io.mindspice.lyra.compiler.source.LogicalModuleId.of("counter");
        var other = io.mindspice.lyra.compiler.source.LogicalModuleId.of("other");
        var resolverCalls = new java.util.concurrent.atomic.AtomicInteger();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested -> {
            resolverCalls.incrementAndGet();
            if (requested.equals(counter)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        counter, URI.create("memory://unaffected-counter.lyra"), counterText.get()));
            }
            if (requested.equals(other)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        other, URI.create("memory://unaffected-other.lyra"), otherText.get()));
            }
            return java.util.Optional.empty();
        };
        var output = new java.io.ByteArrayOutputStream();
        var io = new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                java.io.InputStream.nullInputStream(), output, output,
                java.nio.charset.StandardCharsets.UTF_8);

        try (var session = LyraSession.open(SessionOptions.builder()
                .resolver(resolver).ioEnvironment(io).build())) {
            success(session.submit("import.lyra", "import counter import other"));
            assertEquals("other-init" + System.lineSeparator(),
                    output.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("10", scalar(session.submit("before-counter.lyra", "counter->:.value")));
            assertEquals("30", scalar(session.submit("before-other.lyra", "other->:.value")));

            counterText.set("let @pub value :I32 = 20");
            otherText.set("let @pub value :I32 = 40");
            success(session.reload(counter));
            assertEquals("20", scalar(session.submit("after-counter.lyra", "counter->:.value")));
            assertEquals("30", scalar(session.submit("after-other.lyra", "other->:.value")));
            assertEquals("other-init" + System.lineSeparator(),
                    output.toString(java.nio.charset.StandardCharsets.UTF_8),
                    "an unaffected retained module must not be reinitialized");
            assertEquals(3, resolverCalls.get(),
                    "reload resolves only the selected module closure");
        }
    }

    @Test
    void reloadPreservesAnUnrelatedOldDependencyEdge() {
        AtomicReference<String> baseText = new AtomicReference<>(
                "let @pub value :I32 = 10");
        var base = io.mindspice.lyra.compiler.source.LogicalModuleId.of("base");
        var top = io.mindspice.lyra.compiler.source.LogicalModuleId.of("top");
        var other = io.mindspice.lyra.compiler.source.LogicalModuleId.of("other");
        var resolverCalls = new java.util.concurrent.atomic.AtomicInteger();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested -> {
            resolverCalls.incrementAndGet();
            if (requested.equals(base)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        base, URI.create("memory://old-edge-base.lyra"), baseText.get()));
            }
            if (requested.equals(top)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        top, URI.create("memory://old-edge-top.lyra"),
                        "import base\nlet @pub value :I32 = base->:.value"));
            }
            if (requested.equals(other)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        other, URI.create("memory://old-edge-other.lyra"),
                        "import base\nlet @pub get :Fn<;I32> = (=> || base->:.value)"));
            }
            return java.util.Optional.empty();
        };

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import top import other"));
            success(session.submit("select.lyra", "import other->{get as oldGet}"));
            assertEquals("10", scalar(session.submit("before.lyra", "other->::get[]")));

            baseText.set("let @pub value :I32 = 20");
            success(session.reload(top));

            assertEquals("20", scalar(session.submit("after-top.lyra", "top->:.value")));
            assertEquals("10", scalar(session.submit("after-other.lyra", "other->::get[]")));
            assertEquals("10", scalar(session.submit("after-selected.lyra", "(oldGet)")));
            assertEquals(5, resolverCalls.get(),
                    "reload resolves the selected closure without rereading an old dependency edge");
        }
    }

    @Test
    void reloadRebuildsAChangedDiamondDependencyOnce() {
        AtomicReference<String> baseText = new AtomicReference<>(
                "let @pub value :I32 = 10");
        var base = io.mindspice.lyra.compiler.source.LogicalModuleId.of("base");
        var left = io.mindspice.lyra.compiler.source.LogicalModuleId.of("left");
        var right = io.mindspice.lyra.compiler.source.LogicalModuleId.of("right");
        var top = io.mindspice.lyra.compiler.source.LogicalModuleId.of("top");
        var resolverCalls = new java.util.concurrent.atomic.AtomicInteger();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested -> {
            resolverCalls.incrementAndGet();
            if (requested.equals(base)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        base, URI.create("memory://diamond-reload-base.lyra"), baseText.get()));
            }
            if (requested.equals(left)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        left, URI.create("memory://diamond-reload-left.lyra"),
                        "import base\nlet @pub value :I32 = base->:.value"));
            }
            if (requested.equals(right)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        right, URI.create("memory://diamond-reload-right.lyra"),
                        "import base\nlet @pub value :I32 = base->:.value"));
            }
            if (requested.equals(top)) {
                return java.util.Optional.of(ResolvedSource.memory(
                        top, URI.create("memory://diamond-reload-top.lyra"),
                        "import left import right\n"
                                + "let @pub value :I32 = (+ left->:.value right->:.value)"));
            }
            return java.util.Optional.empty();
        };

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import top"));
            success(session.submit("select.lyra", "import top->{value as oldValue}"));
            assertEquals("20", scalar(session.submit("before.lyra", "top->:.value")));

            baseText.set("let @pub value :I32 = 20");
            success(session.reload(top));
            assertEquals("40", scalar(session.submit("after.lyra", "top->:.value")));
            assertEquals("20", scalar(session.submit("after-old.lyra", "oldValue")));
            assertEquals(8, resolverCalls.get(),
                    "the four-module diamond is resolved once per generation");
        }
    }

    @Test
    void reloadRetainsOldAggregateAndCallableSelectiveValues() {
        AtomicReference<String> sourceText = new AtomicReference<>(
                "let @pub @mut count :I32 = 1\n"
                        + "let @pub items :Array<I32> = Array<I32>[1]\n"
                        + "let @pub bump :Fn<;I32> = (=> || { count := (+ count 1) count })");
        var logical = io.mindspice.lyra.compiler.source.LogicalModuleId.of("values");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested ->
                requested.equals(logical)
                        ? java.util.Optional.of(ResolvedSource.memory(
                                logical, URI.create("memory://reload-values.lyra"), sourceText.get()))
                        : java.util.Optional.empty();

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import values"));
            success(session.submit("select.lyra",
                    "import values->{items as oldItems bump as oldBump}"));
            assertEquals("1", scalar(session.submit("before-items.lyra", "oldItems[0]")));
            assertEquals("2", scalar(session.submit("before-bump.lyra", "(oldBump)")));

            sourceText.set("let @pub @mut count :I32 = 10\n"
                    + "let @pub items :Array<I32> = Array<I32>[20]\n"
                    + "let @pub bump :Fn<;I32> = (=> || { count := (+ count 10) count })");
            success(session.reload(logical));

            assertEquals("20", scalar(session.submit("after-items.lyra", "values->:.items[0]")));
            assertEquals("1", scalar(session.submit("after-old-items.lyra", "oldItems[0]")));
            assertEquals("20", scalar(session.submit("after-bump.lyra", "values->::bump[]")));
            assertEquals("3", scalar(session.submit("after-old-bump.lyra", "(oldBump)")));
        }
    }

    @Test
    void deletingTheBackingSourceDoesNotReplaceThePinnedDefault() {
        AtomicReference<java.util.Optional<ResolvedSource>> current = new AtomicReference<>();
        var logical = io.mindspice.lyra.compiler.source.LogicalModuleId.of("counter");
        var source = ResolvedSource.memory(
                logical, URI.create("memory://deleted-reload-counter.lyra"),
                "let @pub value :I32 = 10");
        current.set(java.util.Optional.of(source));
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) requested ->
                requested.equals(logical) ? current.get() : java.util.Optional.empty();

        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import.lyra", "import counter"));
            current.set(java.util.Optional.empty());
            var failure = assertInstanceOf(EvaluationResult.CompilationFailure.class,
                    session.reload(logical));
            assertTrue(failure.diagnostics().stream().anyMatch(d ->
                    d.code().equals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.RESOLVE_MISSING_MODULE)));
            assertEquals("10", scalar(session.submit("still-pinned.lyra", "counter->:.value")));
        }
    }

    @Test
    void changedTopologyAddsANewDependencyAndDoesNotRerunTheRemovedOne() {
        var sources = new java.util.LinkedHashMap<String, String>();
        sources.put("base", "let @pub value :I32 = 1");
        sources.put("top", "import base let @pub value :I32 = base->:.value");
        var calls = new java.util.ArrayList<String>();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical -> {
            calls.add(logical.value());
            return java.util.Optional.ofNullable(sources.get(logical.value())).map(text ->
                    ResolvedSource.memory(logical, URI.create("memory://topology/" + logical.value()), text));
        };
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import", "import top import base"));
            sources.remove("base");
            sources.put("added", "let @pub value :I32 = 9");
            sources.put("top", "import added let @pub value :I32 = added->:.value");
            calls.clear();
            var result = assertInstanceOf(EvaluationResult.Success.class, session.reload("top"));
            assertEquals(java.util.List.of("top", "added"), calls);
            assertEquals(2, result.initializerProgress().scheduled().size());
            assertEquals(result.initializerProgress().scheduled(), result.initializerProgress().completed());
            assertEquals("9", scalar(session.submit("new", "top->:.value")));
            assertEquals("1", scalar(session.submit("removed", "base->:.value")));
        }
    }

    @Test
    void failedReloadRepeatsEffectsOnRetryAndKeepsStagedNamesHidden() {
        var text = new AtomicReference<>("let @pub value :I32 = 1");
        var output = new java.io.ByteArrayOutputStream();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical ->
                java.util.Optional.of(ResolvedSource.memory(logical, URI.create("memory://effects"), text.get()));
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver)
                .ioEnvironment(new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        java.io.InputStream.nullInputStream(), output, output,
                        java.nio.charset.StandardCharsets.UTF_8)).build())) {
            success(session.submit("import", "import effects"));
            var before = session.workspaceState();
            text.set("import std->io let printed :Unit = io->::println[\"attempt\"] "
                    + "let zero :I32 = 0 let @pub value :I32 = (% 1 zero)");
            for (int retry = 0; retry < 2; retry++) {
                var failed = assertInstanceOf(EvaluationResult.RuntimeFailure.class, session.reload("effects"));
                assertEquals(3, failed.initializerProgress().attempted().size());
                assertEquals(2, failed.initializerProgress().completed().size());
                assertEquals(before, session.workspaceState());
            }
            assertEquals("attempt\nattempt\n", output.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("1", scalar(session.submit("old", "effects->:.value")));
            assertTrue(session.workspaceState().bindings().keySet().stream()
                    .noneMatch(name -> name.startsWith("__lyra_reload_")));
        }
    }

    @Test
    void cancelledReloadReportsTheActualPrefixAndRetainsTheOldDefault() {
        var text = new AtomicReference<>("let @pub value :I32 = 1");
        var sessionRef = new AtomicReference<LyraSession>();
        var cancel = new java.util.concurrent.atomic.AtomicBoolean();
        var output = new java.io.ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                super.write(bytes, offset, length);
                if (cancel.compareAndSet(true, false)) {
                    var session = sessionRef.get();
                    var busy = assertInstanceOf(EvaluationResult.Busy.class,
                            session.reload("not a module"));
                    assertTrue(session.cancel(busy.activeEvaluationId().orElseThrow()));
                }
            }
        };
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical ->
                java.util.Optional.of(ResolvedSource.memory(logical, URI.create("memory://cancel-reload"), text.get()));
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver)
                .ioEnvironment(new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        java.io.InputStream.nullInputStream(), output, output,
                        java.nio.charset.StandardCharsets.UTF_8)).build())) {
            sessionRef.set(session);
            success(session.submit("import", "import cancellable"));
            var before = session.workspaceState();
            text.set("import std->io let printed :Unit = io->::println[\"attempt\"] "
                    + "let @pub value :I32 = 9");
            cancel.set(true);
            var result = assertInstanceOf(EvaluationResult.Cancelled.class, session.reload("cancellable"));
            assertEquals(2, result.initializerProgress().scheduled().size());
            assertEquals(result.initializerProgress().scheduled().subList(0, 1),
                    result.initializerProgress().attempted());
            assertEquals(result.initializerProgress().attempted(), result.initializerProgress().completed());
            assertEquals(before, session.workspaceState());
            assertEquals("1", scalar(session.submit("old", "cancellable->:.value")));
            success(session.reload("cancellable"));
            assertEquals("9", scalar(session.submit("new", "cancellable->:.value")));
            assertEquals("attempt\nattempt\n", output.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void reloadSourceCapacityPreflightReportsScheduledButUnattemptedWork() {
        var text = new AtomicReference<>("let @pub value :I32 = 1");
        var output = new java.io.ByteArrayOutputStream();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical ->
                java.util.Optional.of(ResolvedSource.memory(logical, URI.create("memory://capacity-reload"), text.get()));
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).maxSourceRecords(3)
                .ioEnvironment(new io.mindspice.lyra.runtime.RuntimeIoEnvironment(
                        java.io.InputStream.nullInputStream(), output, output,
                        java.nio.charset.StandardCharsets.UTF_8)).build())) {
            success(session.submit("import", "import capacity"));
            var before = session.workspaceState();
            text.set("import std->io let printed :Unit = io->::println[\"must not run\"] "
                    + "let @pub value :I32 = 2");
            var failure = assertInstanceOf(EvaluationResult.CompilationFailure.class, session.reload("capacity"));
            assertEquals(2, failure.initializerProgress().scheduled().size());
            assertTrue(failure.initializerProgress().attempted().isEmpty());
            assertTrue(failure.initializerProgress().completed().isEmpty());
            assertEquals(0, output.size());
            assertEquals(before, session.workspaceState());
        }
    }

    @Test
    void delayedFailuresAndCapturedCallablesKeepTheirExactSameFileSourceRevision() {
        String oldSource = "let @pub fail :Fn<I32;I32> = (=> |x| (% 11 x))";
        String newSource = "/* 😀 new revision */ let @pub fail :Fn<I32;I32> = (=> |x| (% 22 x))";
        var text = new AtomicReference<>(oldSource);
        var uri = URI.create("memory://same-file-errors.lyra");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical ->
                java.util.Optional.of(ResolvedSource.memory(logical, uri, text.get()));
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            var initial = assertInstanceOf(EvaluationResult.Success.class,
                    session.submit("import", "import errors import errors->{fail as oldFail}"));
            success(session.submit("capture", "let captured :Fn<I32;I32> = (=> |x| errors->::fail[x]) "
                    + "let bundle :Tuple<Fn<I32;I32>,Array<Fn<I32;I32>>> = Tuple[oldFail, Array<Fn<I32;I32>>[oldFail]]"));
            text.set(newSource);
            var reloaded = assertInstanceOf(EvaluationResult.Success.class, session.reload("errors"));
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    initial.initializerProgress().scheduled().getFirst().moduleId(),
                    reloaded.initializerProgress().scheduled().getFirst().moduleId());
            var oldFailure = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("old-call", "(oldFail 0)"));
            var newFailure = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("new-call", "errors->::fail[0]"));
            var oldFrame = oldFailure.frames().stream().filter(frame -> frame.origin().uri().equals(java.util.Optional.of(uri)))
                    .findFirst().orElseThrow();
            var newFrame = newFailure.frames().stream().filter(frame -> frame.origin().uri().equals(java.util.Optional.of(uri)))
                    .findFirst().orElseThrow();
            assertEquals("(% 11 x)", oldFrame.excerpt().orElseThrow());
            assertEquals("(% 22 x)", newFrame.excerpt().orElseThrow());
            assertEquals(oldSource.indexOf("(%"), oldFrame.span().startOffset());
            assertEquals(newSource.indexOf("(%"), newFrame.span().startOffset());
            assertEquals(oldFrame.span().sourceId(), newFrame.span().sourceId(),
                    "public spans map back to the same caller-visible URI, not the compiler identity");
            var captured = assertInstanceOf(EvaluationResult.RuntimeFailure.class,
                    session.submit("captured-call", "(captured 0)"));
            assertEquals(oldFrame, captured.frames().stream().filter(frame -> frame.origin().uri().equals(java.util.Optional.of(uri)))
                    .findFirst().orElseThrow());
            assertEquals("true", scalar(session.submit("identity", "(eq? bundle:.0 bundle:.1[0])")));
            assertEquals("false", scalar(session.submit("distinct", "(eq? oldFail errors->:.fail)")));
        }
    }

    @Test
    void reloadAllowsAChangedResolverSourceIdentityWithoutRetargetingOldImports() {
        var uri = new AtomicReference<>(URI.create("memory://original.lyra"));
        var text = new AtomicReference<>("let @pub value :I32 = 1");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical ->
                java.util.Optional.of(ResolvedSource.memory(logical, uri.get(), text.get()));
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import", "import moved import moved->{value as oldValue}"));
            uri.set(URI.create("memory://replacement.lyra"));
            text.set("let @pub value :I32 = 2");
            success(session.reload("moved"));
            assertEquals("1", scalar(session.submit("old", "oldValue")));
            assertEquals("2", scalar(session.submit("new", "moved->:.value")));
        }
    }

    @Test
    void malformedAndUnresolvedReloadsReportTheOriginalSourceWithoutPublishing() {
        var text = new AtomicReference<>("let @pub value :I32 = 1");
        var uri = URI.create("memory://invalid-reload.lyra");
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical ->
                logical.value().equals("invalid") ? java.util.Optional.of(ResolvedSource.memory(logical, uri, text.get()))
                        : java.util.Optional.empty();
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import", "import invalid"));
            var before = session.workspaceState();
            for (String invalid : java.util.List.of("let @pub value :I32 = (", "let @pub value :I32 = missing",
                    "import missing let @pub value :I32 = 2")) {
                text.set(invalid);
                var failure = assertInstanceOf(EvaluationResult.CompilationFailure.class, session.reload("invalid"));
                assertEquals(io.mindspice.lyra.compiler.source.SourceId.uri(uri),
                        failure.diagnostics().getFirst().primarySpan().sourceId());
                assertTrue(failure.initializerProgress().attempted().isEmpty());
                assertEquals(before, session.workspaceState());
            }
            assertEquals("1", scalar(session.submit("still-old", "invalid->:.value")));
        }
    }

    @Test
    void absentAndAmbiguousTargetsNeverConsultTheResolverOrPublish() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var resolver = (io.mindspice.lyra.compiler.api.SourceResolver) logical -> {
            calls.incrementAndGet();
            return java.util.Optional.of(ResolvedSource.memory(logical,
                    URI.create("memory://targets/" + logical.value()), "let @pub value :I32 = 1"));
        };
        try (var session = LyraSession.open(SessionOptions.builder().resolver(resolver).build())) {
            success(session.submit("import", "import first as second import second as other"));
            var before = session.workspaceState();
            assertEquals(2, calls.get());
            assertInstanceOf(EvaluationResult.CompilationFailure.class, session.reload("missing"));
            var ambiguous = assertInstanceOf(EvaluationResult.CompilationFailure.class, session.reload("second"));
            assertEquals(io.mindspice.lyra.compiler.diagnostic.CompilerDiagnosticCodes.MODULE_DUPLICATE_IDENTITY,
                    ambiguous.diagnostics().getFirst().code());
            assertEquals(2, calls.get());
            assertEquals(before, session.workspaceState());
        }
    }

    private static void success(EvaluationResult result) {
        assertInstanceOf(EvaluationResult.Success.class, result,
                () -> result.status() + " " + result.diagnostics() + " " + result.failureSummary());
    }

    private static String scalar(EvaluationResult result) {
        var success = assertInstanceOf(EvaluationResult.Success.class, result,
                () -> result.status() + " " + result.diagnostics() + " " + result.failureSummary());
        return assertInstanceOf(ValueSnapshot.Scalar.class,
                success.value().orElseThrow().data()).value();
    }
}
