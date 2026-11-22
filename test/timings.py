#!/usr/bin/env python3
"""Test timings in SQLite, with an estimate for the run in progress.

The instrumentation logs one line per finished test, on the app's tag:

    timing: <class>#<test> <millis>ms <passed|failed>

and, from the tests that time their own parts:

    step:<name> <millis>ms

This script keeps both in a SQLite file, which answers the questions the harness
has: how these tests have behaved over time (`history`), how much longer the run
in progress needs (`eta`), and where the time inside one test went (`steps`).

The estimate for what is left is the median of each remaining test's past
durations. What has been spent comes from the wall clock when the caller knows
when it started, so the install or setup a run pays before its first test is
part of the estimate instead of being discovered at the end; when it does not,
the spent time is the sum of the tests already recorded. `--order` puts the
remaining tests in the order the run will run them, which is what names the test
in progress and what counts a test the history has not seen yet.

    python3 test/timings.py record  --log run.log --run 1699999999
    python3 test/timings.py eta     --class <class> --run 1699999999 --log run.log
    python3 test/timings.py eta     --class samples.e2e --run 1699999999 \\
        --log run.log --order "pty tui session" --started 1699999990
    python3 test/timings.py history [--class <class>]
    python3 test/timings.py steps   [--run 1699999999]

The database defaults to test/.timings.db beside this script and can be queried
with any SQLite client.
"""

import argparse
import os
import re
import sqlite3
import statistics
import sys
import time
from datetime import datetime, timedelta, timezone

LINE = re.compile(r"timing: (\S+)#(\S+) (\d+)ms (passed|failed)")
STEP = re.compile(r"step:(.+?) (\d+)ms\b")
DEFAULT_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".timings.db")
SCHEMA = """
CREATE TABLE IF NOT EXISTS timings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run TEXT NOT NULL,
    class TEXT NOT NULL,
    test TEXT NOT NULL,
    outcome TEXT NOT NULL,
    millis INTEGER NOT NULL,
    recorded_at TEXT NOT NULL,
    UNIQUE (run, test)
);
CREATE INDEX IF NOT EXISTS timings_by_test ON timings (class, test);
CREATE TABLE IF NOT EXISTS steps (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run TEXT NOT NULL,
    class TEXT NOT NULL,
    test TEXT NOT NULL,
    step TEXT NOT NULL,
    millis INTEGER NOT NULL,
    recorded_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS steps_by_test ON steps (run, class, test);
"""


def connect(path):
    db = sqlite3.connect(path)
    db.executescript(SCHEMA)
    return db


def ingest(db, log, run):
    """Adds the timings in `log` that this database has not seen yet."""
    if not log or not os.path.isfile(log):
        return 0
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    added = 0
    pending = []
    with open(log, errors="replace") as stream:
        for line in stream:
            step = STEP.search(line)
            if step:
                pending.append((step.group(1).strip(), int(step.group(2))))
                continue
            found = LINE.search(line)
            if not found:
                continue
            row = db.execute(
                "INSERT OR IGNORE INTO timings"
                " (run, class, test, outcome, millis, recorded_at)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (run, found.group(1), found.group(2), found.group(4),
                 int(found.group(3)), stamp),
            )
            added += row.rowcount
            if row.rowcount:
                # A step belongs to the test whose timing line follows it. The
                # log is re-read after every phase, so steps are only stored when
                # their test is new - otherwise each re-read would repeat them.
                for name, millis in pending:
                    db.execute(
                        "INSERT INTO steps (run, class, test, step, millis, recorded_at)"
                        " VALUES (?, ?, ?, ?, ?, ?)",
                        (run, found.group(1), found.group(2), name, millis, stamp),
                    )
            pending.clear()
    db.commit()
    return added


def median_of(values):
    return int(statistics.median(values)) if values else None


def seconds(millis):
    return "%.1fs" % (millis / 1000.0)


# What a test with no history at all is assumed to cost. It is the median of the
# class when there is one; this is only for a class with a single recorded test.
DEFAULT_ESTIMATE = 10_000
# How many of a test's most recent runs its estimate is the median of. All of
# history would make the estimate lag behind a suite that got faster (or a phone
# that got slower) by as many runs as it has ever had; the last few follow it.
RECENT_RUNS = 5


def split_order(order):
    """`--order` as a list, however the caller wrote the separator."""
    return [piece for piece in re.split(r"[,\s]+", order.strip()) if piece]


def plan(db, name, run, order=None):
    """The tests of this class in run order, what is done, and what is left.

    `order` is the run's own list of tests. It is the only way to know which test
    is in progress, to count a test that has no history yet, and to put the
    remaining ones in the order they will actually run; without it the tests are
    sorted by name, which is right for a `history` read and wrong for an estimate.
    """
    known = {row[0] for row in db.execute(
        "SELECT DISTINCT test FROM timings WHERE class = ?", (name,))}
    done = {row[0]: row[1] for row in db.execute(
        "SELECT test, millis FROM timings WHERE class = ? AND run = ?", (name, run))}
    if order:
        tests = [test for test in split_order(order) if test in known or test in done]
    else:
        tests = sorted(known | set(done))
    overall = median_of([row[0] for row in db.execute(
        "SELECT millis FROM timings WHERE class = ?", (name,))])
    estimates = {}
    for test in tests:
        if test in done:
            continue
        # `id` is insertion order, and runs are recorded as they finish, so the
        # highest ids are the most recent runs of this test.
        past = [row[0] for row in db.execute(
            "SELECT millis FROM timings WHERE class = ? AND test = ? AND run <> ?"
            " ORDER BY id DESC LIMIT ?", (name, test, run, RECENT_RUNS))]
        estimates[test] = median_of(past) or overall or DEFAULT_ESTIMATE
    runs = db.execute(
        "SELECT COUNT(DISTINCT run) FROM timings WHERE class = ?", (name,)).fetchone()[0]
    return tests, done, estimates, runs


def report_record(args):
    db = connect(args.db)
    print("recorded %d timing(s) for run %s" % (ingest(db, args.log, args.run), args.run))
    return 0


def report_eta(args):
    db = connect(args.db)
    ingest(db, args.log, args.run)
    tests, done, estimates, runs = plan(db, args.class_, args.run, args.order)
    if not tests:
        print("eta: no timing history yet for %s" % args.class_)
        return 0
    left = sum(estimates.values())
    # Wall clock when the caller has it: a run pays for its install and its
    # device setup before the first test, and an estimate built only from test
    # durations leaves that out until the run is over. The sum is the fallback
    # for a caller that only knows what the log says.
    elapsed = sum(done.values())
    if args.started is not None:
        elapsed = max(0, int(time.time() - args.started)) * 1000
    elif args.elapsed is not None:
        elapsed = max(0, args.elapsed) * 1000
    current = next((test for test in tests if test not in done), None)
    line = ("eta: %d/%d done, %s spent, %s left, %s total"
            % (len(done), len(tests), seconds(elapsed), seconds(left),
               seconds(elapsed + left)))
    if current:
        line += ", now %s" % current
    if left and (args.started is not None or args.elapsed is not None):
        line += ", finish %s" % (datetime.now()
                                  + timedelta(milliseconds=left)).strftime("%H:%M:%S")
    line += ", %d run(s) of history" % runs
    print(line)
    return 0


def report_history(args):
    db = connect(args.db)
    query = ("SELECT class, test, millis, outcome FROM timings"
             + (" WHERE class = ?" if args.class_ else "")
             + " ORDER BY class, test, id")
    rows = db.execute(query, (args.class_,) if args.class_ else ())
    grouped = {}
    for room, test, millis, outcome in rows:
        entry = grouped.setdefault((room, test), {"millis": [], "outcome": outcome})
        entry["millis"].append(millis)
    if not grouped:
        print("no timings recorded yet")
        return 0
    print("%-44s %4s %8s %8s %8s %8s  %s"
          % ("test", "runs", "median", "p90", "min", "max", "last"))
    for (room, test), entry in grouped.items():
        values = sorted(entry["millis"])
        p90 = values[min(len(values) - 1, round(0.9 * (len(values) - 1)))]
        print("%-44s %4d %8s %8s %8s %8s  %s"
              % (test, len(values), seconds(statistics.median(values)), seconds(p90),
                 seconds(values[0]), seconds(values[-1]), entry["outcome"]))
    return 0


def report_steps(args):
    """Where the time inside each test of one run went."""
    db = connect(args.db)
    run = args.run
    if not run:
        found = db.execute(
            "SELECT run FROM timings ORDER BY CAST(run AS INTEGER) DESC LIMIT 1"
        ).fetchone()
        if not found:
            print("no timings recorded yet")
            return 0
        run = found[0]
    rows = list(db.execute(
        "SELECT class, test, millis FROM timings WHERE run = ? ORDER BY millis DESC",
        (run,)))
    if not rows:
        print("no timings for run %s" % run)
        return 0
    print("run %s: %s in %d test(s)" % (run, seconds(sum(row[2] for row in rows)), len(rows)))
    for room, test, millis in rows:
        steps = list(db.execute(
            "SELECT step, millis FROM steps WHERE run = ? AND class = ? AND test = ?"
            " ORDER BY id", (run, room, test)))
        if not steps:
            print("  %-50s %6s" % (test, seconds(millis)))
            continue
        covered = sum(step[1] for step in steps)
        print("  %-50s %6s  (%s in %d step(s), %s outside them)"
              % (test, seconds(millis), seconds(covered), len(steps),
                 seconds(max(0, millis - covered))))
        for name, took in steps:
            print("      %-46s %6s" % (name, seconds(took)))
    return 0


def main(argv):
    # --db is accepted on either side of the subcommand: before it here, after it
    # through `common`, where SUPPRESS keeps the inner default from wiping the
    # outer one when the flag is only given once.
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--db", default=argparse.SUPPRESS)

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--db", default=DEFAULT_DB, help="SQLite file to use")
    sub = parser.add_subparsers(dest="command", required=True)

    record = sub.add_parser("record", parents=[common],
                            help="store the timings found in a log")
    record.add_argument("--log", required=True)
    record.add_argument("--run", required=True)
    record.set_defaults(handler=report_record)

    eta = sub.add_parser("eta", parents=[common],
                         help="estimate how long the run in progress needs")
    eta.add_argument("--class", dest="class_", required=True)
    eta.add_argument("--run", required=True)
    eta.add_argument("--log", default="")
    eta.add_argument("--order", default="",
                     help="the run's tests, in the order it will run them")
    eta.add_argument("--started", type=int, default=None,
                     help="epoch seconds the run began, for wall-clock spent")
    eta.add_argument("--elapsed", type=int, default=None,
                     help="seconds the run has spent so far")
    eta.set_defaults(handler=report_eta)

    history = sub.add_parser("history", parents=[common],
                             help="per-test durations recorded so far")
    history.add_argument("--class", dest="class_", default="")
    history.set_defaults(handler=report_history)

    steps = sub.add_parser("steps", parents=[common],
                           help="where the time inside each test went")
    steps.add_argument("--run", default="")
    steps.set_defaults(handler=report_steps)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
