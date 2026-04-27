#!/usr/bin/env python3
"""
T64 Smoketest Log-Analyser
==========================
Parst die 4 adb-logcat-Dateien des T64-Smoketests und gibt eine
vollständige Auswertung inkl. Latenz-Statistik und Failure-Analyse aus.

Verwendung:
    python log-analyse-t64.py \
        --samsung-p0 t64-samsung-preroll0.log \
        --samsung-p5 t64-samsung-preroll5.log \
        --fairphone-p0 t64-fairphone-preroll0.log \
        --fairphone-p5 t64-fairphone-preroll5.log

Ausgabe:
    Konsole + t64-report.md
"""

import argparse
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

# ── Konfiguration ────────────────────────────────────────────────────────────

EXPECTED_STARTS = 20          # Pro Konfiguration erwartet
P95_THRESHOLD_MS = 1500       # AC5
LOGCAT_TS_RE = re.compile(
    r"^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)"  # MM-DD HH:MM:SS.mmm
)
SMOKETEST_START_RE  = re.compile(r"SMOKETEST_START\s+ts=(\d+)")
SMOKETEST_CHUNK_RE  = re.compile(r"SMOKETEST_FIRST_CHUNK\s+latency_ms=(\d+)")
SESSION_START_RE    = re.compile(r"Session gestartet")
FATAL_RE            = re.compile(r"FATAL EXCEPTION|E AndroidRuntime")
ANR_RE              = re.compile(r"ANR in")


# ── Datenstrukturen ──────────────────────────────────────────────────────────

@dataclass
class StartEvent:
    ts_ms: int                          # SMOKETEST_START ts=
    first_chunk_latency: Optional[int]  # SMOKETEST_FIRST_CHUNK latency_ms=, None = nicht angekommen
    had_fatal: bool = False


@dataclass
class LogResult:
    label: str
    starts: list[StartEvent] = field(default_factory=list)
    anr_count: int = 0
    fatal_lines: list[str] = field(default_factory=list)
    raw_path: str = ""

    @property
    def total_starts(self) -> int:
        return len(self.starts)

    @property
    def successful_starts(self) -> int:
        return sum(1 for s in self.starts if s.first_chunk_latency is not None and not s.had_fatal)

    @property
    def latencies(self) -> list[int]:
        return [s.first_chunk_latency for s in self.starts if s.first_chunk_latency is not None]

    @property
    def success_rate_pct(self) -> float:
        if self.total_starts == 0:
            return 0.0
        return self.successful_starts / self.total_starts * 100

    @property
    def median_ms(self) -> Optional[float]:
        lats = self.latencies
        return round(statistics.median(lats), 1) if lats else None

    @property
    def p95_ms(self) -> Optional[int]:
        lats = sorted(self.latencies)
        if not lats:
            return None
        idx = max(0, int(len(lats) * 0.95) - 1)
        return lats[idx]

    def ac_ok(self) -> bool:
        """100 % Erfolg UND genug Starts."""
        return self.total_starts >= EXPECTED_STARTS and self.successful_starts == self.total_starts

    def ac5_ok(self) -> bool:
        p = self.p95_ms
        return p is not None and p < P95_THRESHOLD_MS


# ── Parsing ──────────────────────────────────────────────────────────────────

def parse_log(path: Path, label: str) -> LogResult:
    result = LogResult(label=label, raw_path=str(path))
    pending_start_ts: Optional[int] = None
    pending_had_fatal = False
    current_fatal_block: list[str] = []

    with path.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            # SMOKETEST_START → neues Ereignis beginnen
            m = SMOKETEST_START_RE.search(line)
            if m:
                # Vorherigen offenen Start als Failure schliessen
                if pending_start_ts is not None:
                    result.starts.append(StartEvent(
                        ts_ms=pending_start_ts,
                        first_chunk_latency=None,
                        had_fatal=pending_had_fatal
                    ))
                pending_start_ts = int(m.group(1))
                pending_had_fatal = False
                continue

            # SMOKETEST_FIRST_CHUNK → Start erfolgreich
            m = SMOKETEST_CHUNK_RE.search(line)
            if m and pending_start_ts is not None:
                result.starts.append(StartEvent(
                    ts_ms=pending_start_ts,
                    first_chunk_latency=int(m.group(1)),
                    had_fatal=pending_had_fatal
                ))
                pending_start_ts = None
                pending_had_fatal = False
                continue

            # FATAL / ANR
            if FATAL_RE.search(line):
                pending_had_fatal = True
                result.fatal_lines.append(line.rstrip())

            if ANR_RE.search(line):
                result.anr_count += 1

    # Letzter offener Start (kein Chunk mehr) → Failure
    if pending_start_ts is not None:
        result.starts.append(StartEvent(
            ts_ms=pending_start_ts,
            first_chunk_latency=None,
            had_fatal=pending_had_fatal
        ))

    return result


# ── Reporting ─────────────────────────────────────────────────────────────────

PASS = "✅"
FAIL = "❌"
WARN = "⚠️ "


def status(ok: bool) -> str:
    return PASS if ok else FAIL


def latency_histogram(latencies: list[int], bins: int = 10) -> str:
    if not latencies:
        return "  (keine Daten)"
    lo, hi = min(latencies), max(latencies)
    if lo == hi:
        return f"  Alle Werte: {lo} ms"
    width = (hi - lo) / bins
    buckets = [0] * bins
    for v in latencies:
        idx = min(int((v - lo) / width), bins - 1)
        buckets[idx] += 1
    lines = []
    for i, count in enumerate(buckets):
        low = int(lo + i * width)
        high = int(lo + (i + 1) * width)
        bar = "█" * count
        lines.append(f"  {low:5d}–{high:5d} ms │ {bar} ({count})")
    return "\n".join(lines)


def render_report(results: list[LogResult], to_console: bool = True) -> str:
    lines = []
    a = lines.append

    a("# T64 Smoketest — Auswertung\n")
    a("## Test-Matrix\n")
    a("| Gerät | Preroll | Starts | Erfolge | Rate | Median | p95 | AC | AC5 |")
    a("|-------|---------|--------|---------|------|--------|-----|----|-----|")

    for r in results:
        p95_str = f"{r.p95_ms} ms" if r.p95_ms is not None else "n/a"
        med_str = f"{r.median_ms} ms" if r.median_ms is not None else "n/a"
        a(f"| {r.label} | "
          f"{r.total_starts}/{EXPECTED_STARTS} erw. | "
          f"{r.successful_starts} | "
          f"{r.success_rate_pct:.0f}% | "
          f"{med_str} | {p95_str} | "
          f"{status(r.ac_ok())} | {status(r.ac5_ok())} |")

    a("\n## AC-Status\n")
    ac_labels = ["AC1 (Samsung Preroll=0)", "AC2 (Samsung Preroll=5s)",
                 "AC3 (Fairphone Preroll=0)", "AC4 (Fairphone Preroll=5s)"]
    for i, (r, label) in enumerate(zip(results, ac_labels), 1):
        note = "" if i not in (2, 4) else "  _(Preroll-Fail erwartet laut Scope, darf rot bleiben)_"
        a(f"- **{label}:** {status(r.ac_ok())} ({r.successful_starts}/{r.total_starts}){note}")

    # AC5: p95 über alle erfolgreichen Tests
    all_lats = [lat for r in results for lat in r.latencies]
    if all_lats:
        global_p95 = sorted(all_lats)[max(0, int(len(all_lats) * 0.95) - 1)]
        a(f"- **AC5 (p95 < {P95_THRESHOLD_MS} ms):** {status(global_p95 < P95_THRESHOLD_MS)} "
          f"(global p95 = {global_p95} ms über {len(all_lats)} Messungen)")
    else:
        a(f"- **AC5:** {FAIL} (keine Latenz-Daten)")

    a("\n## Latenz-Histogramme\n")
    for r in results:
        a(f"### {r.label} ({len(r.latencies)} Messungen)\n")
        a(latency_histogram(r.latencies))
        a("")

    a("\n## Failures\n")
    any_failure = False
    for r in results:
        failures = [s for s in r.starts if s.first_chunk_latency is None]
        if failures:
            any_failure = True
            a(f"### {r.label} — {len(failures)} Failure(s)\n")
            for i, s in enumerate(failures, 1):
                a(f"- Start #{i}: ts={s.ts_ms}, fatal={s.had_fatal}")
            a("")
    if not any_failure:
        a("Keine Failures.\n")

    a("\n## Crashes / ANRs\n")
    any_crash = False
    for r in results:
        if r.fatal_lines or r.anr_count:
            any_crash = True
            a(f"### {r.label}\n")
            a(f"- ANR-Zeilen: {r.anr_count}")
            a(f"- FATAL-Zeilen: {len(r.fatal_lines)}")
            if r.fatal_lines:
                a("```")
                for l in r.fatal_lines[:20]:  # max 20 Zeilen
                    a(l)
                a("```")
            a("")
    if not any_crash:
        a("Keine Crashes / ANRs detektiert.\n")

    a("\n## Empfehlung\n")
    all_ac1_ac3_ok = results[0].ac_ok() and results[2].ac_ok()  # AC1, AC3
    ac5_ok_global = bool(all_lats) and sorted(all_lats)[max(0, int(len(all_lats) * 0.95) - 1)] < P95_THRESHOLD_MS
    if all_ac1_ac3_ok and ac5_ok_global:
        a(f"{PASS} **T55 bestätigt:** Recording-Start stabil auf Samsung + Fairphone (Preroll=0). "
          f"Alle AC1/AC3/AC5 grün. T64 kann in master gemergt werden.")
    else:
        problems = []
        if not results[0].ac_ok():
            problems.append("Samsung Preroll=0 nicht 100%")
        if not results[2].ac_ok():
            problems.append("Fairphone Preroll=0 nicht 100%")
        if not ac5_ok_global:
            problems.append(f"p95-Latenz über {P95_THRESHOLD_MS} ms")
        a(f"{FAIL} **Offene Punkte:** {'; '.join(problems)}. Fix oder Eskalation nötig.")

    report = "\n".join(lines)
    if to_console:
        print(report)
    return report


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="T64 Smoketest Log-Analyser")
    parser.add_argument("--samsung-p0",    required=True, metavar="FILE",
                        help="Logcat Samsung Preroll=0")
    parser.add_argument("--samsung-p5",    required=True, metavar="FILE",
                        help="Logcat Samsung Preroll=5s")
    parser.add_argument("--fairphone-p0",  required=True, metavar="FILE",
                        help="Logcat Fairphone Preroll=0")
    parser.add_argument("--fairphone-p5",  required=True, metavar="FILE",
                        help="Logcat Fairphone Preroll=5s")
    parser.add_argument("--out", default="t64-report.md", metavar="FILE",
                        help="Ausgabe-Datei (default: t64-report.md)")
    args = parser.parse_args()

    configs = [
        (args.samsung_p0,   "Samsung   Preroll=0s"),
        (args.samsung_p5,   "Samsung   Preroll=5s"),
        (args.fairphone_p0, "Fairphone Preroll=0s"),
        (args.fairphone_p5, "Fairphone Preroll=5s"),
    ]

    results = []
    for path_str, label in configs:
        p = Path(path_str)
        if not p.exists():
            print(f"FEHLER: Datei nicht gefunden: {p}", file=sys.stderr)
            sys.exit(1)
        print(f"Parsing {label}: {p} ...", file=sys.stderr)
        results.append(parse_log(p, label))

    report = render_report(results)
    out = Path(args.out)
    out.write_text(report, encoding="utf-8")
    print(f"\nBericht gespeichert: {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
