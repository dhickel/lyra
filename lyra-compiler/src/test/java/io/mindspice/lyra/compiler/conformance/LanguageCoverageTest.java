package io.mindspice.lyra.compiler.conformance;

import io.mindspice.lyra.compiler.lex.Lexer;
import io.mindspice.lyra.compiler.lex.TokenKind;
import io.mindspice.lyra.compiler.source.PhysicalSourceKey;
import io.mindspice.lyra.compiler.source.SourceId;
import io.mindspice.lyra.compiler.source.SourceSnapshot;
import io.mindspice.lyra.compiler.types.PrimitiveType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Inventory drift must fail core tests when a new primitive or operator is introduced. */
class LanguageCoverageTest {
    @Test void everyPrimitiveAndOperatorHasPositiveAndNegativeCorpusCoverage() throws Exception {
        var corpus = LanguageCorpus.read();
        Set<String> primitives = Arrays.stream(PrimitiveType.values()).map(PrimitiveType::canonicalSpelling).collect(Collectors.toSet());
        assertEquals(primitives, LanguageAbiTest.scalars().stream().map(LanguageAbiTest.Scalar::type).collect(Collectors.toSet()));
        assertEquals(primitives.stream().map(String::toLowerCase).collect(Collectors.toSet()), corpus.stream()
                .filter(test -> test.feature().equals("primitives")).map(test -> test.name().split("/")[1].split("-text")[0]).collect(Collectors.toSet()));
        Set<TokenKind> positive = new HashSet<>(), negative = new HashSet<>();
        for (var test : corpus) {
            if (!Set.of("operators", "operator-arity").contains(test.feature())) continue;
            var snapshot = SourceSnapshot.capture(SourceId.path("case.lyra"), PhysicalSourceKey.uri(URI.create("memory:coverage")),
                    test.source().getBytes(StandardCharsets.UTF_8)).optionalValue().orElseThrow();
            var lexed = Lexer.lex(snapshot).optionalValue().orElseThrow();
            lexed.tokens().stream().filter(token -> token.kind().isOperator())
                    .forEach(token -> (test.outcome().equals("value") ? positive : negative).add(token.kind()));
        }
        Set<TokenKind> all = Arrays.stream(TokenKind.values()).filter(TokenKind::isOperator).collect(Collectors.toSet());
        assertEquals(all, positive, "Add executed fixtures for every new operator");
        assertEquals(all, negative, "Add malformed-arity fixtures for every new operator");
        Set<String> features = corpus.stream().map(LanguageCorpus.Case::feature).collect(Collectors.toSet());
        assertEquals(Set.of("primitives", "truthiness", "operators", "operator-arity", "evaluation", "bindings", "functions",
                "nilability", "conditionals", "aggregates", "strings", "lexical", "conversions", "modules", "excluded", "runtime", "match", "ranges", "nominal"), features);
        assertTrue(LanguageFuzzWorker.MODES.contains("match"), "Match scenarios must remain in the bounded fuzz campaign");
        var matches = corpus.stream().filter(test -> test.feature().equals("match")).toList();
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("value") && test.source().contains("(match ")),
                "Add a value-form match fixture");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("value") && test.source().contains("match[")),
                "Add a bare bracket-form match fixture");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("value") && test.source().contains("(cond ")),
                "Add a parenthesized cond fixture");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("value") && test.source().contains("_ when")),
                "Add a legal guarded wildcard followed by an unguarded fallback");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("value")
                        && test.source().contains("(secondPattern)")),
                "Add a reached computed pattern after a false guard");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("value")
                        && (test.source().contains("-> (match") || test.source().contains("-> (cond"))),
                "Add a nested match/cond fixture");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("value")
                        && test.source().contains(", ::")),
                "Add a sibling-:: comma arm fixture");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("value") && test.source().contains("_ ->")),
                "Add a mandatory final wildcard fixture");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("reject") && test.source().contains("when")),
                "Add malformed guard coverage");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("reject") && test.source().contains("??")),
                "Add obsolete and malformed marker coverage");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("reject")
                        && test.source().contains("(match ->")),
                "Add a missing-subject fragment");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("reject")
                        && test.source().contains("1 -> _ ->")),
                "Add a missing-result fragment");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("throws")),
                "Add a source-mapped match runtime-failure fixture");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("reject")
                        && test.source().contains("::match[")),
                "Add an obsolete '::match[' rejection fixture");
        assertTrue(matches.stream().anyMatch(test -> test.outcome().equals("reject")
                        && test.source().contains("(match _")),
                "Add an obsolete conditional-subject rejection fixture");
        var nominal = corpus.stream().filter(test -> test.feature().equals("nominal")).toList();
        assertTrue(nominal.stream().anyMatch(test -> test.outcome().equals("value")
                        && test.source().contains(":Box[")),
                "Add an explicit construction fixture");
        assertTrue(nominal.stream().anyMatch(test -> test.outcome().equals("value")
                        && test.source().contains("Values[")),
                "Add an uppercase value-indexing fixture");
        assertTrue(nominal.stream().anyMatch(test -> test.outcome().equals("reject")
                        && test.expected().equals("LYC-RESOLVE-027")),
                "Add an obsolete unprefixed-construction rejection fixture");
        var operators = corpus.stream().filter(test -> test.feature().equals("operators")).toList();
        assertTrue(operators.stream().anyMatch(test -> test.outcome().equals("value")
                        && test.source().contains("-1)")),
                "Add an adjacent bare negative literal fixture");
        var lexical = corpus.stream().filter(test -> test.feature().equals("lexical")).toList();
        assertTrue(lexical.stream().anyMatch(test -> test.outcome().equals("reject")
                        && test.source().contains("-129I8")),
                "Add a signed-minimum magnitude rejection fixture");
        var operatorArity = corpus.stream().filter(test -> test.feature().equals("operator-arity")).toList();
        assertTrue(operatorArity.stream().anyMatch(test -> test.outcome().equals("reject")
                        && test.source().contains("- 1)")),
                "Add a trivia-separated minus rejection fixture");
        assertTrue(operatorArity.stream().anyMatch(test -> test.outcome().equals("reject")
                        && test.source().contains("--1")),
                "Add a doubled-minus rejection fixture");
    }
}
