# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java-based compiler for a custom functional programming language that compiles to the JVM. The language features LISP-like syntax with unique accessor operators and a grammar-driven parsing system.

## Build Commands

- **Compile:** `mvn compile`
- **Test:** `mvn test`
- **Clean:** `mvn clean`
- **Package:** `mvn package`
- **Run specific test:** `mvn test -Dtest=TestForms`
- **Run resolution tests:** `mvn test -Dtest=ResolutionTest`

### Java 25 EA Commands

This project uses Java 25 Early Access with preview features enabled by default in the Maven configuration.

- **JDK 25 EA Path:** `/home/hickelpickle/.jdks/openjdk-ea-25+36-3489`
- **Compile with Java 25 EA:** `JAVA_HOME=/home/hickelpickle/.jdks/openjdk-ea-25+36-3489 mvn compile`
- **Test with Java 25 EA:** `JAVA_HOME=/home/hickelpickle/.jdks/openjdk-ea-25+36-3489 mvn test`

## Project Architecture

### Core Components

**Lexer** (`src/main/java/parse/Lexer.java`)
- Tokenizes source code into tokens
- Handles numeric literals, identifiers, operators, and syntax elements
- Uses state machine pattern for character-by-character processing

**Parser** (`src/main/java/parse/Parser.java`)
- Two-phase parsing: grammar matching via `Grammar.findNextMatch()`, then AST construction
- Uses `Result<T, E>` type for error handling throughout
- Contains `LangParser` (main parser) and `SubParser` (lookahead helper)

**Grammar System** (`src/main/java/lang/grammar/`)
- `Grammar.java`: Pattern matching engine that determines which language constructs are present
- `GrammarForm.java`: Represents parsed grammar patterns before AST conversion
- `GrammarMatch.java`: Result wrapper for grammar pattern matching

**AST** (`src/main/java/lang/ast/`)
- `ASTNode.java`: Sealed interface hierarchy representing parsed program structure
- All nodes contain `MetaData` for type information and source location tracking

**Type System** (`src/main/java/lang/`)
- `LangType.java`: Type representations including primitives, functions, and arrays
- `Symbol.java`: Identifier management with resolution status tracking

**Symbol Resolution** (`src/main/java/lang/env/`)
- `Environment.java`: Environment chain for symbol scoping and resolution
- `Namespace.java`: Namespace management with symbol tables
- `Symbol.java`: Symbol representation with resolution status tracking
- `SymbolTable.java`: Symbol lookup and management

**Resolution System** (`src/main/java/compile/resolve/`)
- `Resolver.java`: Interface for symbol resolution operations
- `NsScope.java`: Namespace scoping for resolution context

**Error Handling** (`src/util/Result.java`)
- Rust-style `Result<T, E>` type used throughout for error propagation
- Custom exceptions in `src/main/java/util/exceptions/`

### Language Features

**Accessor Operators:**
- `:.` - Field access (with implicit self for methods)
- `::` - Function access/calls (required for all function calls)
- `->` - Namespace access

**Syntax Patterns:**
- S-expressions: `(func arg1 arg2)`
- Lambda expressions: `(=> |params| body)` or `(=> : RetType |params| body)`
- Let statements: `let var : Type = expr`
- Block expressions: `{ stmt1 stmt2 expr }`
- Conditionals: `(predicate -> then_expr : else_expr)`

## Development Notes

- Uses Java 25 EA with preview features enabled by default via Maven configuration
- Target platform: JVM bytecode compilation
- All parsing uses `Result<T, E>` pattern - avoid throwing exceptions directly
- Grammar matching happens before AST construction (two-phase approach)
- Symbol resolution uses environment chains for scoping
- Eclipse Collections library used for performance-optimized collections
- Jackson library used for JSON serialization (future IR output)

## Testing

Tests are in `src/test/java/TestForms.java` with example language constructs. Run tests to verify parser changes work correctly.