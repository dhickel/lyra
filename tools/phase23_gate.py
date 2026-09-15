#!/usr/bin/env python3
"""Evaluate the owner-ratified Phase 23 gates from raw JMH/probe evidence."""

import argparse
import json
import math
import sys
from pathlib import Path

CORE = (
    "typed-direct-call", "arithmetic", "branch", "closure-cell",
    "array-read", "tuple-read", "string",
)
AGGREGATE_ALLOCATIONS = ("array-allocation", "tuple-allocation", "string-allocation")
NON_ALLOCATING_METHODS = (
    "generatedWarmExactHandleCall", "generatedCachedExportHandleCall", "generatedFacadeCall",
)


def metric(item, secondary=None):
    value = item.get("primaryMetric", {}) if secondary is None else item.get("secondaryMetrics", {}).get(secondary, {})
    score = value.get("score")
    label = "primary" if secondary is None else secondary
    if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score):
        raise ValueError(f"missing finite {label} score for {item.get('benchmark')}")
    score = float(score)
    if secondary is None and score <= 0:
        raise ValueError(f"missing finite positive primary score for {item.get('benchmark')}")
    if secondary is not None and score < 0:
        raise ValueError(f"negative {label} score for {item.get('benchmark')}")
    return score


def seconds(value):
    if not isinstance(value, str):
        raise ValueError(f"invalid JMH duration: {value!r}")
    parts = value.split()
    if len(parts) != 2:
        raise ValueError(f"invalid JMH duration: {value!r}")
    try:
        amount = float(parts[0])
    except ValueError as failure:
        raise ValueError(f"invalid JMH duration: {value!r}") from failure
    scale = {"s": 1.0, "ms": 0.001, "us": 0.000001, "ns": 0.000000001}.get(parts[1])
    if scale is None or not math.isfinite(amount):
        raise ValueError(f"invalid JMH duration: {value!r}")
    return amount * scale


def validate_full_protocol(raw):
    for item in raw:
        benchmark = item.get("benchmark")
        if item.get("mode") != "avgt" or item.get("primaryMetric", {}).get("scoreUnit") != "ns/op":
            raise ValueError(f"benchmark {benchmark} is not average-time nanoseconds")
        if isinstance(item.get("threads"), bool) or item.get("threads") != 1:
            raise ValueError(f"benchmark {benchmark} did not use one thread")
        if (isinstance(item.get("forks"), bool)
                or not isinstance(item.get("forks"), int) or item["forks"] < 2):
            raise ValueError(f"benchmark {benchmark} did not use at least two forks")
        if (isinstance(item.get("warmupIterations"), bool)
                or not isinstance(item.get("warmupIterations"), int)
                or item["warmupIterations"] < 3):
            raise ValueError(f"benchmark {benchmark} did not use at least three warmup iterations")
        if (isinstance(item.get("measurementIterations"), bool)
                or not isinstance(item.get("measurementIterations"), int)
                or item["measurementIterations"] < 5):
            raise ValueError(f"benchmark {benchmark} did not use at least five measurement iterations")
        if seconds(item.get("warmupTime")) != 1.0 or seconds(item.get("measurementTime")) != 1.0:
            raise ValueError(f"benchmark {benchmark} did not use one-second iterations")
        metric(item, "gc.alloc.rate.norm")


def index_results(raw):
    methods = {}
    scenarios = {}
    for item in raw:
        name = item.get("benchmark", "").rsplit(".", 1)[-1]
        scenario = item.get("params", {}).get("scenario")
        if scenario is None:
            if name in methods:
                raise ValueError(f"duplicate benchmark method: {name}")
            methods[name] = item
        else:
            key = (name, scenario)
            if key in scenarios:
                raise ValueError(f"duplicate benchmark scenario: {name}/{scenario}")
            scenarios[key] = item
    return methods, scenarios


def check(name, observed, limit, unit, comparison, category):
    return {
        "name": name,
        "category": category,
        "comparison": comparison,
        "observed": observed,
        "limit": limit,
        "unit": unit,
        "status": "pass" if observed <= limit else "fail",
    }


def evaluate(raw, generated_footprint, gates):
    methods, scenarios = index_results(raw)
    checks = []
    core_limit = gates["latency"]["coreEquivalentMaximumRatio"]
    for scenario in CORE:
        generated = scenarios[("generatedWorkload", scenario)]
        equivalent = scenarios[("javaEquivalentWorkload", scenario)]
        checks.append(check(f"latency.core.{scenario}", metric(generated) / metric(equivalent),
                            core_limit, "ratio", "generated exact-handle owner/open path / Java exact-handle owner/open path", "latency"))
    generated = scenarios[("generatedWorkload", "tail-recursion")]
    equivalent = scenarios[("javaEquivalentWorkload", "tail-recursion")]
    checks.append(check("latency.self-tail", metric(generated) / metric(equivalent),
                        gates["latency"]["selfTailEquivalentMaximumRatio"], "ratio",
                        "generated exact-handle owner/open tail loop / Java exact-handle owner/open tail loop", "latency"))
    for generated_name in ("generatedWarmExactHandleCall", "generatedCachedExportHandleCall"):
        checks.append(check(f"latency.{generated_name}", metric(methods[generated_name]) / metric(methods["javaWarmExactHandleCall"]),
                            gates["latency"]["exactHandleEquivalentMaximumRatio"], "ratio",
                            "generated bound exact handle / Java owner/open-checked bound exact handle", "latency"))
    checks.append(check("latency.generatedFacadeCall", metric(methods["generatedFacadeCall"]) / metric(methods["javaFacadeCall"]),
                        gates["latency"]["facadeEquivalentMaximumRatio"], "ratio",
                        "generated typed facade boundary / Java owner/open-checked typed facade boundary", "latency"))

    call_parity_limit = gates["callParity"]["sFormToDirectNameMaximumRatio"]
    for pair in (("fibSExpressionCall", "fibDirectNameCall", "callParity.fib"),
                 ("namedSExpressionCall", "namedDirectNameCall", "callParity.named")):
        direct = metric(methods[pair[1]])
        s_form = metric(methods[pair[0]])
        pair_fields = ("mode", "threads", "forks", "warmupIterations", "warmupTime",
                       "measurementIterations", "measurementTime", "jvmArgs", "jdkVersion",
                       "vmName", "vmVersion")
        if (methods[pair[0]].get("params", {}) != methods[pair[1]].get("params", {})
                or any(methods[pair[0]].get(field) != methods[pair[1]].get(field)
                       for field in pair_fields)):
            raise ValueError(f"call-parity effective configuration mismatch for {pair[2]}")
        checks.append(check(pair[2],
                            s_form / direct,
                            call_parity_limit, "ratio",
                            "proven-route S-expression call / direct-name call on the same exact declaration and storage route",
                            "callParity"))

    non_alloc_limit = gates["allocation"]["nonAllocatingMaximumBytesPerOperation"]
    for scenario in CORE + ("tail-recursion",):
        allocation = metric(scenarios[("generatedWorkload", scenario)], "gc.alloc.rate.norm")
        checks.append(check(f"allocation.non-allocating.{scenario}", allocation, non_alloc_limit, "B/op",
                            "generated steady-state allocation", "allocation"))
    for name in NON_ALLOCATING_METHODS:
        checks.append(check(f"allocation.non-allocating.{name}", metric(methods[name], "gc.alloc.rate.norm"),
                            non_alloc_limit, "B/op", "generated steady-state allocation", "allocation"))
    aggregate_limit = gates["allocation"]["semanticAggregateEquivalentMaximumRatio"]
    for scenario in AGGREGATE_ALLOCATIONS:
        generated_alloc = metric(scenarios[("generatedWorkload", scenario)], "gc.alloc.rate.norm")
        java_alloc = metric(scenarios[("javaEquivalentWorkload", scenario)], "gc.alloc.rate.norm")
        checks.append(check(f"allocation.semantic.{scenario}", generated_alloc / java_alloc, aggregate_limit,
                            "ratio", "generated semantic allocation / equivalent Java allocation", "allocation"))
    checks.append(check("allocation.semantic.closure-allocation",
                        metric(scenarios[("generatedWorkload", "closure-allocation")], "gc.alloc.rate.norm"),
                        gates["allocation"]["closureMaximumBytesPerOperation"], "B/op",
                        "generated required closure/cell allocation", "allocation"))
    checks.append(check("allocation.semantic.failure",
                        metric(scenarios[("generatedWorkload", "failure")], "gc.alloc.rate.norm"),
                        gates["allocation"]["expectedFailureMaximumBytesPerOperation"], "B/op",
                        "generated expected structured failure allocation", "allocation"))

    checks.append(check("footprint.emitted-classes", float(generated_footprint["artifactClassCount"]),
                        float(gates["footprint"]["maximumEmittedFixtureClasses"]), "classes",
                        "exact emitted fixture class count", "footprint"))
    checks.append(check("footprint.cold-load-and-call", metric(methods["generatedColdLoadAndCall"]),
                        gates["footprint"]["coldGeneratedLoadAndCallMaximumNanoseconds"], "ns/op",
                        "generated runtime load, instantiate, exact lookup, call, and close", "footprint"))
    return {
        "schema": "lyra.phase23.gate-result.v1",
        "status": "pass" if all(item["status"] == "pass" for item in checks) else "fail",
        "allSelectedGatesPass": all(item["status"] == "pass" for item in checks),
        "thresholdSchema": gates["schema"],
        "thresholds": gates,
        "structuralNoBoxingRequired": True,
        "evidenceOnly": {
            "classLoading": "whole-process; not gated",
            "metaspace": "whole-process; not gated",
            "rawDirectJava": "retained as an optimization-floor observation; not an equivalent ratio denominator",
        },
        "checks": checks,
    }


def self_test():
    def row(name, score=2.0, alloc=0.0, scenario=None):
        result = {"benchmark": "fixture." + name,
                  "mode": "avgt", "threads": 1, "forks": 2,
                  "warmupIterations": 3, "warmupTime": "1 s",
                  "measurementIterations": 5, "measurementTime": "1 s",
                  "primaryMetric": {"score": score, "scoreUnit": "ns/op"},
                  "secondaryMetrics": {"gc.alloc.rate.norm": {"score": alloc}}, "params": {}}
        if scenario is not None:
            result["params"]["scenario"] = scenario
        return result
    raw = []
    for scenario in CORE + ("tail-recursion",) + AGGREGATE_ALLOCATIONS + ("closure-allocation", "failure"):
        raw.extend((row("generatedWorkload", 2.0, 32.0 if scenario in AGGREGATE_ALLOCATIONS else
                        1024.0 if scenario == "closure-allocation" else 2048.0 if scenario == "failure" else 0.0, scenario),
                    row("javaEquivalentWorkload", 1.0, 16.0 if scenario in AGGREGATE_ALLOCATIONS else 0.0, scenario)))
    for name in NON_ALLOCATING_METHODS + ("javaWarmExactHandleCall", "javaFacadeCall"):
        raw.append(row(name, 2.0 if name.startswith("generated") else 1.0))
    for name in ("fibSExpressionCall", "fibDirectNameCall", "namedSExpressionCall", "namedDirectNameCall"):
        raw.append(row(name, 1.0))
    raw.append(row("generatedColdLoadAndCall", 4_000_000.0))
    gates = json.loads((Path(__file__).with_name("phase23-gates.json")).read_text())
    validate_full_protocol(raw)
    result = evaluate(raw, {"artifactClassCount": 28}, gates)
    assert result["schema"] == "lyra.phase23.gate-result.v1" and result["allSelectedGatesPass"]
    failing_gates = json.loads(json.dumps(gates))
    failing_gates["latency"]["coreEquivalentMaximumRatio"] = 0.5
    failed = evaluate(raw, {"artifactClassCount": 28}, failing_gates)
    assert failed["status"] == "fail" and not failed["allSelectedGatesPass"]
    parity_gates = json.loads(json.dumps(gates))
    parity_gates["callParity"]["sFormToDirectNameMaximumRatio"] = 0.5
    parity_failed = evaluate(raw, {"artifactClassCount": 28}, parity_gates)
    assert parity_failed["status"] == "fail" and not parity_failed["allSelectedGatesPass"]
    assert any(item["name"].startswith("callParity.") and item["status"] == "fail"
               for item in parity_failed["checks"])
    # Check the fixed primary-score threshold itself, not merely a changed limit.
    for name, gate_name in (("fibSExpressionCall", "callParity.fib"),
                            ("namedSExpressionCall", "callParity.named")):
        for score, expected in ((1.10, "pass"), (math.nextafter(1.10, math.inf), "fail")):
            boundary = [dict(item, primaryMetric={"score": score, "scoreUnit": "ns/op"})
                        if item["benchmark"] == "fixture." + name else item for item in raw]
            evaluated = evaluate(boundary, {"artifactClassCount": 28}, gates)
            assert next(check for check in evaluated["checks"]
                        if check["name"] == gate_name)["status"] == expected
    for name in ("fibSExpressionCall", "fibDirectNameCall",
                 "namedSExpressionCall", "namedDirectNameCall"):
        for score in (float("nan"), float("inf"), -1.0, 0.0, True, None):
            invalid_pair = [dict(item, primaryMetric={"score": score, "scoreUnit": "ns/op"})
                            if item["benchmark"] == "fixture." + name else item for item in raw]
            try:
                evaluate(invalid_pair, {"artifactClassCount": 28}, gates)
                raise AssertionError(f"invalid pair score was not rejected: {name}={score!r}")
            except ValueError:
                pass
    # Missing and duplicate pair members must fail closed, never pass.
    missing = [item for item in raw if item["benchmark"] != "fixture.fibDirectNameCall"]
    try:
        evaluate(missing, {"artifactClassCount": 28}, gates)
        raise AssertionError("missing direct-name pair member was not rejected")
    except KeyError:
        pass
    duplicate = raw + [next(item for item in raw
                            if item["benchmark"] == "fixture.fibDirectNameCall")]
    try:
        evaluate(duplicate, {"artifactClassCount": 28}, gates)
        raise AssertionError("duplicate direct-name pair member was not rejected")
    except ValueError:
        pass
    # A nonfinite pair score must fail closed, never pass.
    invalid = [dict(item, primaryMetric={"score": float("inf")})
               if item["benchmark"] == "fixture.fibSExpressionCall" else item for item in raw]
    try:
        evaluate(invalid, {"artifactClassCount": 28}, gates)
        raise AssertionError("nonfinite pair score was not rejected")
    except ValueError:
        pass
    # Nonpositive numerator and denominator scores must fail closed, never pass.
    for benchmark in ("fixture.fibSExpressionCall", "fixture.fibDirectNameCall"):
        zero = [dict(item, primaryMetric={"score": 0.0, "scoreUnit": "ns/op"})
                if item["benchmark"] == benchmark else item for item in raw]
        try:
            evaluate(zero, {"artifactClassCount": 28}, gates)
            raise AssertionError(f"nonpositive pair score was not rejected: {benchmark}")
        except ValueError:
            pass
    boolean_score = [dict(item, primaryMetric={"score": True, "scoreUnit": "ns/op"})
                     if item["benchmark"] == "fixture.fibSExpressionCall" else item for item in raw]
    try:
        evaluate(boolean_score, {"artifactClassCount": 28}, gates)
        raise AssertionError("boolean pair score was not rejected")
    except ValueError:
        pass
    # Preserve the accepted minimum budgets; stronger runs are valid evidence.
    validate_full_protocol([dict(item, forks=3, warmupIterations=4, measurementIterations=6)
                            for item in raw])
    for field, value in (("forks", 1), ("forks", True), ("warmupIterations", 2),
                         ("measurementIterations", 4), ("warmupTime", "500 ms"),
                         ("measurementTime", "2 s"), ("threads", 2), ("mode", "thrpt")):
        wrong_protocol = [dict(item, **{field: value})
                          if item["benchmark"] == "fixture.fibSExpressionCall" else item
                          for item in raw]
        try:
            validate_full_protocol(wrong_protocol)
            raise AssertionError(f"invalid JMH protocol was not rejected: {field}={value!r}")
        except ValueError:
            pass
    mismatched = [dict(item, params={"input": "other"})
                  if item["benchmark"] == "fixture.fibSExpressionCall" else item for item in raw]
    try:
        evaluate(mismatched, {"artifactClassCount": 28}, gates)
        raise AssertionError("mismatched call-parity effective configuration was not rejected")
    except ValueError:
        pass
    print("phase23-gate-self-test: PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--jmh")
    parser.add_argument("--footprint")
    parser.add_argument("--thresholds", default=str(Path(__file__).with_name("phase23-gates.json")))
    parser.add_argument("--output")
    parser.add_argument("--mode", choices=("gate", "observed"), default="gate")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.jmh or not args.footprint or not args.output:
        parser.error("--jmh, --footprint, and --output are required")
    try:
        raw = json.loads(Path(args.jmh).read_text())
        footprint = json.loads(Path(args.footprint).read_text())
        gates = json.loads(Path(args.thresholds).read_text())
        if args.mode == "gate":
            validate_full_protocol(raw)
        result = evaluate(raw, footprint, gates)
    except (KeyError, ValueError, TypeError, json.JSONDecodeError) as failure:
        print(f"phase23-gate: invalid or incomplete evidence: {failure}", file=sys.stderr)
        return 2
    result["evaluationMode"] = args.mode
    Path(args.output).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(f"phase23-gate: {result['status']} ({args.mode} mode)")
    return 1 if args.mode == "gate" and not result["allSelectedGatesPass"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
