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
    if not isinstance(score, (int, float)) or not math.isfinite(score):
        raise ValueError(f"missing finite {'primary' if secondary is None else secondary} score for {item.get('benchmark')}")
    return float(score)


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
        result = {"benchmark": "fixture." + name, "primaryMetric": {"score": score},
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
    raw.append(row("generatedColdLoadAndCall", 4_000_000.0))
    gates = json.loads((Path(__file__).with_name("phase23-gates.json")).read_text())
    result = evaluate(raw, {"artifactClassCount": 28}, gates)
    assert result["schema"] == "lyra.phase23.gate-result.v1" and result["allSelectedGatesPass"]
    failing_gates = json.loads(json.dumps(gates))
    failing_gates["latency"]["coreEquivalentMaximumRatio"] = 0.5
    failed = evaluate(raw, {"artifactClassCount": 28}, failing_gates)
    assert failed["status"] == "fail" and not failed["allSelectedGatesPass"]
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
