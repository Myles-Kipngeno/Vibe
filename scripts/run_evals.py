"""Run the eval set and report what changed.

Unit tests prove a function does what it says. This proves the product still
behaves the way it promises to on whole conversations -- that a boundary still
blocks, that an unknown name is still asked about rather than invented, and
that an ordinary chat still gets a reply, which matters just as much: a product
that refuses everything is useless.

Two halves, because they cost different things.

    python scripts/run_evals.py

runs the deterministic half against the analysis and the gates. It needs no API
key, spends nothing, and must always pass.

    python scripts/run_evals.py --generate

additionally asks the configured provider for real replies and checks them
against the promises in the README: no invented people, no blown Sheng budget,
no stacked emojis, no reciting the example library. That half costs money and
is not deterministic, so a failure there is a prompt to go and look rather than
proof of a bug.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.core import evaluation, generator  # noqa: E402
from app.core.style_profile import blank_profile  # noqa: E402
from app.providers.registry import get_provider  # noqa: E402
from app.schemas import Message  # noqa: E402

CASES = ROOT / "evals" / "cases.json"

passed = 0
failed = 0
failures: list[str] = []


def check(case_id: str, name: str, ok: bool, detail: str = "") -> None:
    global passed, failed
    if ok:
        passed += 1
        print(f"    pass  {name}" + (f"  -- {detail}" if detail else ""))
    else:
        failed += 1
        failures.append(f"{case_id}: {name}  -- {detail}")
        print(f"    FAIL  {name}  -- {detail}")


def run_case(case: dict, provider, generate: bool) -> None:
    case_id = case["id"]
    print(f"\n  {case_id}")
    print(f"    {case['why']}")

    messages = [Message(**m) for m in case["messages"]]
    profile = blank_profile()
    for field, value in (case.get("style") or {}).items():
        profile = profile.model_copy(update={field: value})

    analysis = generator.analyse(
        messages=messages,
        known_keys=set(),
        contact_name=None,
        local_time=None,
        max_tone=profile.max_tone,
    )

    expect = case.get("expect", {})
    if "boundary_detected" in expect:
        check(
            case_id,
            "boundary detected" if expect["boundary_detected"] else "no false boundary",
            analysis.boundary_detected == expect["boundary_detected"],
            f"got {analysis.boundary_detected}",
        )
    if "alerts_at_least" in expect:
        count = len(analysis.alerts)
        check(
            case_id,
            "raises a context alert",
            count >= expect["alerts_at_least"],
            f"got {count}",
        )

    response = generator.generate(
        provider=provider,
        messages=messages,
        analysis=analysis,
        goal=case["goal"],
        profile=profile,
        supplied_context={},
        memories=[],
        avoid=[],
        action=case.get("action"),
        contact_name=None,
    )

    if "blocked" in expect:
        check(case_id, *_result(evaluation.check_blocked(response, expect["blocked"])))
    if "suggestions" in expect and expect["suggestions"] == 0:
        check(case_id, *_result(evaluation.check_no_suggestions(response)))

    gen = case.get("generation")
    if not (generate and gen):
        return

    if provider.is_mock:
        print("    skip  generation checks -- offline mock, not model output")
        return

    suggestions = response.suggestions
    if "min_suggestions" in gen:
        check(
            case_id,
            "enough options",
            len(suggestions) >= gen["min_suggestions"],
            f"got {len(suggestions)}",
        )
    if gen.get("no_invented_people"):
        check(
            case_id,
            *_result(evaluation.check_no_invented_people(suggestions, messages)),
        )
    if gen.get("sheng_budget"):
        check(
            case_id,
            *_result(evaluation.check_sheng_budget(suggestions, profile.sheng_ratio)),
        )
    check(case_id, *_result(evaluation.check_no_emoji_stacking(suggestions)))
    if len(suggestions) > 1:
        check(case_id, *_result(evaluation.check_options_are_distinct(suggestions)))


def _result(result: evaluation.CheckResult) -> tuple[str, bool, str]:
    return result.name, result.passed, result.detail


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--generate",
        action="store_true",
        help="also ask the configured provider for replies and check them",
    )
    parser.add_argument("--case", help="run one case by id")
    args = parser.parse_args()

    data = json.loads(CASES.read_text(encoding="utf-8"))
    cases = data["cases"]
    if args.case:
        cases = [c for c in cases if c["id"] == args.case]
        if not cases:
            print(f"No case with id {args.case!r}.")
            return 2

    provider = get_provider()
    print(f"Provider: {provider.name}" + ("  (offline mock)" if provider.is_mock else ""))
    if args.generate and provider.is_mock:
        print(
            "\nNOTE: --generate was passed but no real provider is configured, so the\n"
            "generation checks are skipped rather than run against templates.\n"
            "Set ANTHROPIC_API_KEY in backend/.env to exercise them."
        )

    print(f"\nRunning {len(cases)} case(s).")
    for case in cases:
        run_case(case, provider, args.generate)

    print(f"\n{'-' * 60}")
    print(f"{passed} passed, {failed} failed")
    if failures:
        print("\nFailures:")
        for line in failures:
            print(f"  {line}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
