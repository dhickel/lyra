# Deferred features and rejected spellings

This page distinguishes current absence from an alternative spelling. Deferred capabilities are not reserved placeholders and normally fail through ordinary lexical, parse, resolution, or type diagnostics.

## Deferred language capabilities

- variants, destructuring patterns, type patterns, match bindings, automatic match narrowing, and exhaustiveness analysis;
- inheritance, interfaces, overriding, overloading, static/shared type members, and custom struct constructors;
- user-defined generics beyond built-in `Array`, `Range`, `Tuple`, and `Fn` forms;
- macros, quoting, hygiene, and compile-time metaprogramming;
- general iterator protocols, dedicated loop statements, unsigned/floating ranges, `return`, `break`, `continue`, and `do while`;
- bitwise and shift operators;
- `throw`, `try`, `catch`, and `finally`;
- `Any`, dynamic values, and runtime member lookup;
- optional, default, omitted, or named arguments, varargs, automatic currying, and partial application;
- source edition directives and simultaneous selectable language editions.

## Deferred products and integration

- Lyra source calling arbitrary Java classes/members;
- arbitrary Java callbacks into Lyra contracts;
- engine or Vulkan integration;
- broader standard-library domains such as filesystem, clocks, randomness, networking, processes, and concurrency;
- hostile-code sandboxing, quotas, forced termination, process isolation, REPL authentication, credentials, or encryption;
- automatic local REPL root initialization;
- transparent reload, state migration, latest-version handles, watcher reload, and old-reference rebinding;
- serialized public IR, backend plug-in APIs, additional production backends, optimization levels, and incremental caches;
- automatic debugger attachment to an external REPL and guaranteed arbitrary body-local inspection.

## Rejected or obsolete source spellings

| Do not write | Current form or rule |
| --- | --- |
| `?? pattern -> result` | `pattern -> result` |
| `(match _ condition -> value _ -> fallback)` | `(cond condition -> value _ -> fallback)` |
| `::match[...]` | `match[...]` |
| `::iter[...]` | `iter[...]` |
| `::while[...]` | `while[...]` |
| `cond[...]` | Parenthesized `(cond ...)` only |
| `Type[args]` for a nominal | `:Type[args]` |
| `module->:.Type[args]` | `:module->Type[args]` |
| `name:Type` or `name : Type` | `name :Type` |
| `::method` as a value | `receiver:.method`; `::` always has brackets |
| `:=[target value]` | `target := value` or `(:= target value)` |
| `@const`, `@opt`, `@nilable`, `@static` | Not current modifiers |
| `nor`, `nand`, `xnor`, `eqt?`, `eqv?` | Not current operators |

Parentheses are callable application, not grouping. There is no general statement separator. Commas outside documented lists are invalid except for the narrow sibling-`::` disambiguation described in [lexical structure](lexical-structure.md#commas).
