package io.mindspice.lyra.editor;

import io.mindspice.lyra.compiler.ast.*;
import io.mindspice.lyra.compiler.diagnostic.*;
import io.mindspice.lyra.compiler.grammar.GrammarMatcher;
import io.mindspice.lyra.compiler.lex.*;
import io.mindspice.lyra.compiler.parse.Parser;
import io.mindspice.lyra.compiler.semantic.*;
import io.mindspice.lyra.compiler.source.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The ordinary compiler phases are the authority for editor syntax and semantic checks. */
public final class LanguageService {
    public record Style(int start, int end, String css) { }
    public record Navigation(int start, int end, Path target, int targetStart, int targetEnd) { }
    public record Definition(Path file, String name, String signature, List<String> parameters,
                             int start, int end, int nameOffset, int bodyOffset,
                             boolean function, boolean topLevel) {
        public Definition { parameters = List.copyOf(parameters); }
        public String label() { return (function ? "ƒ  " : "•  ") + name + "  " + signature + (topLevel ? "" : "  (local)"); }
    }
    public record Analysis(Path file, String text, List<Style> styles, List<Definition> definitions,
                           List<Diagnostic> diagnostics, Optional<SyntaxProgram> syntax, List<Navigation> navigation) {
        public Analysis {
            styles = List.copyOf(styles); definitions = List.copyOf(definitions);
            diagnostics = List.copyOf(diagnostics); syntax = Objects.requireNonNull(syntax); navigation = List.copyOf(navigation);
        }
        public Analysis(Path file, String text, List<Style> styles, List<Definition> definitions, List<Diagnostic> diagnostics, Optional<SyntaxProgram> syntax) {
            this(file, text, styles, definitions, diagnostics, syntax, List.of());
        }
        public boolean valid() { return diagnostics.stream().noneMatch(d -> d.severity().isError()); }
    }

    public Analysis analyze(Path file, String text, List<Path> roots, Map<Path, String> buffers, boolean semantic) {
        Path normalized = file.toAbsolutePath().normalize();
        SourceSnapshot snapshot = snapshot(normalized, text);
        var lexical = Lexer.lex(snapshot);
        if (lexical.isFailure()) {
            // The compiler deliberately publishes no partial token stream. Re-lex only a valid prefix
            // so an unfinished literal does not erase highlighting earlier in the document.
            int prefix = lexical.diagnostics().stream().mapToInt(d -> d.primarySpan().startOffset()).min().orElse(0);
            var earlier = Lexer.lex(snapshot(normalized, text.substring(0, prefix)));
            List<Style> prior = earlier.optionalValue().map(LanguageService::styles).orElse(List.of());
            return new Analysis(normalized, text, prior, List.of(), lexical.diagnostics(), Optional.empty());
        }
        LexedSource tokens = lexical.optionalValue().orElseThrow();
        List<Style> styles = styles(tokens);
        var grammar = GrammarMatcher.match(tokens);
        if (grammar.isFailure()) return new Analysis(normalized, text, styles, List.of(), grammar.diagnostics(), Optional.empty());
        var parsed = Parser.parse(tokens, grammar.optionalValue().orElseThrow());
        if (parsed.isFailure()) return new Analysis(normalized, text, styles, List.of(), parsed.diagnostics(), Optional.empty());
        SyntaxProgram program = parsed.optionalValue().orElseThrow();
        List<Definition> definitions = new ArrayList<>();
        program.forms().forEach(form -> walk(form, normalized, text, true, definitions));
        List<Diagnostic> diagnostics = new ArrayList<>(parsed.diagnostics());
        List<Navigation> navigation = new ArrayList<>();
        if (semantic) {
            var graph = ModuleGraphDiscovery.discover(program, snapshot, configuration(roots, buffers));
            diagnostics.addAll(graph.diagnostics());
            if (graph.isSuccess()) {
                var resolved = SemanticResolver.resolve(graph.optionalValue().orElseThrow());
                diagnostics.addAll(resolved.diagnostics());
                if (resolved.isSuccess()) {
                    var typedResult = TypeChecker.check(resolved.optionalValue().orElseThrow());
                    diagnostics.addAll(typedResult.diagnostics());
                    typedResult.optionalValue().ifPresent(typed -> {
                        for (int i = 0; i < definitions.size(); i++) {
                            Definition definition = definitions.get(i);
                            typed.declarations().stream().filter(d -> d.name().equals(definition.name()) && d.span().equals(
                                    SourceSpan.of(snapshot.sourceId(), definition.start(), definition.end()))).findFirst()
                                    .flatMap(TypedDeclaration::contract).ifPresent(contract -> {
                                        String signature = contract.canonicalSpelling();
                                        definitions.set(definitions.indexOf(definition), new Definition(definition.file(), definition.name(), signature,
                                                definition.parameters(), definition.start(), definition.end(), definition.nameOffset(), definition.bodyOffset(),
                                                signature.startsWith("Fn<"), definition.topLevel()));
                                    });
                        }
                        for (TypedReference reference : typed.references()) {
                            if (!reference.span().sourceId().equals(snapshot.sourceId()) || reference.targetDeclaration().isEmpty()) continue;
                            var declaration = typed.resolvedGraph().declaration(reference.targetDeclaration().orElseThrow());
                            if (declaration.isPresent() && declaration.get().originDeclaration().isPresent())
                                declaration = typed.resolvedGraph().declaration(declaration.get().originDeclaration().orElseThrow());
                            declaration.ifPresent(target -> {
                                String identity = target.nameSpan().sourceId().value();
                                if (identity.startsWith("file:")) navigation.add(new Navigation(reference.span().startOffset(), reference.span().endOffset(),
                                        Path.of(java.net.URI.create(identity)), target.nameSpan().startOffset(), target.nameSpan().endOffset()));
                            });
                        }
                    });
                }
            }
        }
        return new Analysis(normalized, text, styles, definitions, diagnostics, Optional.of(program), navigation);
    }

    public SourceConfiguration configuration(List<Path> roots, Map<Path, String> buffers) {
        Map<Path, String> captured = new HashMap<>();
        buffers.forEach((key, value) -> captured.put(key.toAbsolutePath().normalize(), value));
        List<SourceResolver> resolvers = roots.stream().map(root -> (SourceResolver) logical -> {
            Path path = root.resolve(logical.relativeSourcePath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) && !captured.containsKey(path)) return Optional.empty();
            try {
                Path real = Files.exists(path) ? path.toRealPath() : path;
                String buffer = captured.getOrDefault(real, captured.get(path));
                byte[] bytes = buffer != null ? buffer.getBytes(StandardCharsets.UTF_8) : WorkspaceFiles.readBytes(real);
                return Optional.of(new ResolvedSource(logical, SourceId.uri(real.toUri()), PhysicalSourceKey.of(real), bytes));
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }).toList();
        return new SourceConfiguration(List.of(), resolvers);
    }

    private static SourceSnapshot snapshot(Path path, String text) {
        return SourceSnapshot.capture(SourceId.uri(path.toUri()), PhysicalSourceKey.of(path),
                text.getBytes(StandardCharsets.UTF_8)).optionalValue().orElseThrow();
    }

    private static List<Style> styles(LexedSource source) {
        List<Style> styles = new ArrayList<>();
        for (Token token : source.tokens()) {
            token.leadingTrivia().stream().filter(Trivia::isComment).forEach(trivia ->
                    styles.add(new Style(trivia.span().startOffset(), trivia.span().endOffset(), "syntax-comment")));
            String css = switch (token.kind()) {
                case LET, IMPORT, AS, MATCH, WHEN, LAMBDA_ARROW -> "syntax-keyword";
                case MODIFIER -> "syntax-modifier";
                case TYPE_NAME -> "syntax-type";
                case STRING_LITERAL, CHAR_LITERAL -> "syntax-string";
                case INTEGER_LITERAL, FLOAT_LITERAL, BOOLEAN_LITERAL, NIL_LITERAL -> "syntax-number";
                case IDENTIFIER -> "syntax-name";
                default -> token.kind().isAccessor() || token.kind().isOperator() ? "syntax-operator" : "syntax-punctuation";
            };
            if (token.span().length() > 0) styles.add(new Style(token.span().startOffset(), token.span().endOffset(), css));
        }
        return List.copyOf(styles);
    }

    private static void walk(Object value, Path file, String text, boolean topLevel, List<Definition> definitions) {
        if (value instanceof SyntaxNode.LetBinding binding) {
            var lambda = binding.initializer();
            List<SyntaxNode.Parameter> params = lambda instanceof SyntaxNode.Lambda full ? full.parameters()
                    : lambda instanceof SyntaxNode.CompactLambda compact ? compact.parameters() : List.of();
            boolean function = lambda instanceof SyntaxNode.Lambda || lambda instanceof SyntaxNode.CompactLambda;
            int body = lambda instanceof SyntaxNode.Lambda full ? bodyStart(full.body())
                    : lambda instanceof SyntaxNode.CompactLambda compact ? bodyStart(compact.body()) : binding.span().startOffset();
            String signature = binding.annotation().map(annotation -> slice(text, annotation.span()))
                    .orElse(function ? "|" + String.join(", ", params.stream().map(p -> p.name().name()).toList()) + "|" : "inferred");
            definitions.add(new Definition(file, binding.name().name(), signature,
                    params.stream().map(p -> p.name().name()).toList(), binding.span().startOffset(), binding.span().endOffset(),
                    binding.name().span().startOffset(), body, function || signature.replace(" ", "").startsWith("Fn<"), topLevel));
        }
        // Syntax is a closed immutable tree of records. Traverse it, never source text patterns.
        if (value instanceof SyntaxNode node && node.getClass().isRecord()) {
            for (var component : node.getClass().getRecordComponents()) {
                try {
                    Object child = component.getAccessor().invoke(node);
                    if (child instanceof SyntaxNode || child instanceof List<?> || child instanceof Optional<?>)
                        walk(child, file, text, false, definitions);
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot traverse compiler syntax", failure); }
            }
        } else if (value instanceof List<?> list) list.forEach(child -> walk(child, file, text, false, definitions));
        else if (value instanceof Optional<?> optional) optional.ifPresent(child -> walk(child, file, text, false, definitions));
    }
    private static int bodyStart(SyntaxNode node) {
        return node instanceof SyntaxNode.Block block && !block.forms().isEmpty()
                ? bodyStart(block.forms().getFirst()) : node.span().startOffset();
    }
    private static String slice(String text, SourceSpan span) { return text.substring(span.startOffset(), span.endOffset()).strip(); }
}
