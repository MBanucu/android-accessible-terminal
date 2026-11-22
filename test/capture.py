#!/usr/bin/env python3
"""A screen recording of a run, and the moments in it.

The suite records the device's screen once, from the first check to the last, and
writes an event next to every moment worth finding later: a check starting, a
key typed, a frame asked for. Nothing is extracted while the tests run. This
script is the recording, the events, and the "extract that frame now" part.

    python3 test/capture.py start --dir samples/out/capture
    python3 test/capture.py event session:typed
    python3 test/capture.py stop
    python3 test/capture.py frames --event session:typed --out samples/out/frames
    python3 test/capture.py frames --at 12.5,40 --out samples/out/frames
    python3 test/capture.py show

Why the mapping is exact, and not "the video starts when I pressed start"
------------------------------------------------------------------------
`screenrecord` writes the presentation time of every frame, in the device's
elapsed-realtime clock, into a metadata track beside the video (
`writeWinscopeMetadata` in frameworks/av's screenrecord.cpp): a magic string, the
frame count, and one 64-bit timestamp per frame. That is the same clock
`/proc/uptime` reports, so a moment logged on the device and a frame in the video
are comparable numbers rather than two clocks to be eyeballed against each other.

This script reads that track out of the MP4 itself (ffprobe does not expose data
tracks) and pairs it with the container's own pts, so an event becomes a frame
index: the last frame presented at or before the event. An event therefore maps
to the picture that was on screen at that moment, which is what a screenshot
means. No frame rate is assumed; `screenrecord` records frames only when the
screen changes, and gaps in time are gaps in the list.

The one number that is not from the video is the offset between the device's
clock and this machine's: the suite logs `event` on this side, the frames are
timed on that side. It is sampled twice, half a round trip each way, and the
spread between the samples is reported as the accuracy of the mapping. A round
trip is tens of milliseconds; a frame is 33 ms at 30 fps, and the sidecar says
which of the two dominated.

Where the device predates the metadata track (Android 12 and older screenrecord),
`stop` falls back to the offset from the recording's own start and says so; the
mapping is then only as good as that assumption, and `accuracy` names it.
"""

import argparse
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time

MAGIC_V1 = b"#VV1NSC0PET1ME!#"
MAGIC_V2 = b"#VV1NSC0PET1ME2#"
CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"dinf"}
STATE = "capture.json"
EVENTS = "events.jsonl"
RECORDING = "recording.mp4"
REMOTE = "/data/local/tmp/suite-capture.mp4"


def adb(args, serial="", capture=True):
    command = [os.environ.get("ADB", "adb")]
    if serial:
        command += ["-s", serial]
    command += args
    if capture:
        return subprocess.run(command, capture_output=True, text=True)
    return subprocess.run(command)


def device_shell(command, serial=""):
    return adb(["shell", command], serial)


def host_clock():
    """This machine's monotonic seconds: a clock ntpd cannot step.

    Termux cannot read /proc/uptime (SELinux), and time.monotonic is the same
    kind of clock anyway. It is not the device's boot clock: the difference is
    the machine's own suspend time, a constant for the run, and it lands in the
    measured offset rather than in the mapping.
    """
    return time.monotonic()


def device_uptime(serial=""):
    """The device's elapsed-realtime seconds, the clock the video's frames use."""
    result = device_shell("cat /proc/uptime", serial)
    if result.returncode != 0:
        return None
    return float(result.stdout.split()[0])


def sample_clock(serial=""):
    """(device - host) in seconds, with the half round trip as its error bar."""
    before = host_clock()
    device = device_uptime(serial)
    after = host_clock()
    if device is None:
        return None
    # The device's reading was taken somewhere inside the call, so the middle of
    # the call is the best guess at when, and half the call is how wrong that is.
    return {"offset": device - (before + after) / 2.0, "error": (after - before) / 2.0}


# --- the MP4's metadata track ------------------------------------------------


def boxes(data, start, end):
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack(">I4s", data[pos:pos + 8])
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[pos + 8:pos + 16])[0]
            header = 16
        if size < header or pos + size > end:
            return
        yield kind, pos, header, size
        if kind in CONTAINERS:
            yield from boxes(data, pos + header, pos + size)
        pos += size


def full_box(body, fields):
    """Read a version-0 full box's fields: a 4-byte version/flags, then values."""
    values = struct.unpack(">" + fields, body[4:4 + struct.calcsize(">" + fields)])
    return values


def track_tables(data, start, end):
    """What a track's sample tables say: handler, sizes, offsets, chunk layout."""
    handler = None
    sizes, offsets, chunks = [], [], []
    for kind, pos, header, size in boxes(data, start, end):
        body = data[pos + header:pos + size]
        if kind == b"hdlr":
            handler = body[8:12]
        elif kind == b"stsz":
            default, count = full_box(body, "II")
            sizes = [default] * count if default else list(
                struct.unpack(">%dI" % count, body[12:12 + 4 * count]))
        elif kind == b"stco":
            count = full_box(body, "I")[0]
            offsets = list(struct.unpack(">%dI" % count, body[8:8 + 4 * count]))
        elif kind == b"co64":
            count = full_box(body, "I")[0]
            offsets = list(struct.unpack(">%dQ" % count, body[8:8 + 8 * count]))
        elif kind == b"stsc":
            count = full_box(body, "I")[0]
            for i in range(count):
                chunks.append(struct.unpack(">III", body[8 + 12 * i:20 + 12 * i])[:2])
    return handler, sizes, offsets, chunks


def track_samples(data, offsets, chunks, sizes):
    index, out = 0, []
    for piece, (first_chunk, per_chunk) in enumerate(chunks):
        last = (chunks[piece + 1][0] - 1) if piece + 1 < len(chunks) else len(offsets)
        for chunk in range(first_chunk, last + 1):
            offset = offsets[chunk - 1]
            for _ in range(per_chunk):
                if index >= len(sizes):
                    return out
                out.append(data[offset:offset + sizes[index]])
                offset += sizes[index]
                index += 1
    return out


def parse_metadata(path):
    """Frame times from the Winscope metadata track: {clock, frames, epoch_offset}."""
    data = open(path, "rb").read()
    best = None
    for kind, pos, header, size in boxes(data, 0, len(data)):
        if kind != b"trak":
            continue
        handler, sizes, offsets, chunks = track_tables(data, pos + header, pos + size)
        if handler != b"meta":
            continue
        for payload in track_samples(data, offsets, chunks, sizes):
            # Version 2 first: the elapsed clock, which /proc/uptime also reports.
            if payload.startswith(MAGIC_V2):
                epoch_offset = struct.unpack("<q", payload[20:28])[0]
                count = struct.unpack("<I", payload[28:32])[0]
                stamps = struct.unpack("<%dQ" % count, payload[32:32 + 8 * count])
                found = {"clock": "elapsed", "unit": 1e9, "frames": list(stamps),
                         "epoch_offset_ns": epoch_offset}
            elif payload.startswith(MAGIC_V1):
                count = struct.unpack("<I", payload[16:20])[0]
                stamps = struct.unpack("<%dQ" % count, payload[20:20 + 8 * count])
                found = {"clock": "monotonic", "unit": 1e6, "frames": list(stamps)}
            else:
                continue
            if found["frames"] and (best is None or found["clock"] == "elapsed"):
                best = found
    if best is None:
        return None
    frames = best["frames"]
    if any(b < a for a, b in zip(frames, frames[1:])):
        sys.exit("capture: the metadata track's frame times are not in order")
    return best


def container_pts(path):
    """The container's own pts per frame, in presentation order."""
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "frame=pts_time", "-of", "csv=p=0", path], capture_output=True, text=True)
    if result.returncode != 0:
        return []
    stamps = []
    for line in result.stdout.split():
        try:
            stamps.append(float(line))
        except ValueError:
            pass
    return stamps


# --- commands ----------------------------------------------------------------


def load_state(directory):
    path = os.path.join(directory, STATE)
    if not os.path.isfile(path):
        sys.exit("capture: no recording in %s (run start first)" % directory)
    with open(path) as handle:
        return json.load(handle)


def save_state(directory, state):
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, STATE), "w") as handle:
        json.dump(state, handle, indent=2)


def cmd_start(args):
    os.makedirs(args.dir, exist_ok=True)
    serial = args.serial or os.environ.get("ANDROID_SERIAL", "")
    if adb(["get-state"], serial).returncode != 0:
        sys.exit("capture: no device (set ANDROID_SERIAL or --serial)")
    device_shell("rm -f %s" % REMOTE, serial)
    # A leftover recorder from a dead run would hold the display's capture slot
    # and would also be found by stop, so it is stopped before this one starts.
    device_shell("pkill -INT screenrecord", serial)
    clock = sample_clock(serial)
    if clock is None:
        sys.exit("capture: could not read the device clock")
    option = ""
    if args.size:
        option += " --size %s" % args.size
    if args.bit_rate:
        option += " --bit-rate %s" % args.bit_rate
    # setsid + & so the recorder outlives this adb call: start and stop are
    # separate commands, and the suite does not hold a client open for a run.
    device_shell("setsid screenrecord --time-limit 0%s %s </dev/null >/dev/null 2>&1 &"
                 % (option, REMOTE), serial)
    running = False
    for _ in range(40):
        if device_shell("pidof screenrecord", serial).returncode == 0:
            running = True
            break
        time.sleep(0.05)
    if not running:
        sys.exit("capture: screenrecord did not start")
    state = {"remote": REMOTE, "serial": serial, "started": time.time(),
             "host_clock": host_clock(), "clock": [clock],
             "size": args.size or "native", "bit_rate": args.bit_rate or "default"}
    save_state(args.dir, state)
    with open(os.path.join(args.dir, EVENTS), "w") as handle:
        handle.write(json.dumps({"name": "record:start", "host": host_clock()}) + "\n")
    print("capture: recording to the device, events in %s" % os.path.join(args.dir, EVENTS))


def cmd_event(args):
    directory = args.dir
    if not os.path.isdir(directory):
        return 0
    if not os.path.isfile(os.path.join(directory, STATE)):
        return 0
    record = {"name": args.name, "host": host_clock()}
    for pair in args.data or []:
        key, _, value = pair.partition("=")
        record.setdefault("data", {})[key.strip()] = value.strip()
    with open(os.path.join(directory, EVENTS), "a") as handle:
        handle.write(json.dumps(record) + "\n")
    return 0


def cmd_stop(args):
    state = load_state(args.dir)
    serial = state.get("serial", "")
    stop_sampled = sample_clock(serial)
    device_shell("pkill -INT screenrecord", serial)
    for _ in range(100):
        if device_shell("pidof screenrecord", serial).returncode != 0:
            break
        time.sleep(0.05)
    else:
        sys.exit("capture: screenrecord would not stop")
    recording = os.path.join(args.dir, RECORDING)
    pull = adb(["pull", state["remote"], os.path.abspath(recording)], serial)
    if pull.returncode != 0:
        sys.exit("capture: could not pull the recording: %s" % pull.stderr.strip())
    device_shell("rm -f %s" % state["remote"], serial)
    # Before the remux, because ffmpeg's MP4 muxer cannot write screenrecord's
    # `meta` handler tracks and drops them: the frame times have to be read out
    # of the file screenrecord closed.
    meta = parse_metadata(recording)
    # A file written by screenrecord plays but cannot be seeked until something
    # closes it: remuxing with faststart is what makes `frames` a seek, not a
    # decode from the beginning.
    fixed = recording + ".fixed.mp4"
    remux = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", recording,
         "-c", "copy", "-movflags", "+faststart", fixed], capture_output=True)
    if remux.returncode == 0 and os.path.getsize(fixed) > 0:
        os.replace(fixed, recording)
    else:
        say = "capture: could not remux; frames will still decode from the start"
        print(say)
        if os.path.exists(fixed):
            os.remove(fixed)

    pts = container_pts(recording)
    sidecar = {"recording": os.path.basename(recording),
               "size": os.path.getsize(recording),
               "duration": pts[-1] - pts[0] if len(pts) > 1 else 0.0,
               "events": [], "frames": []}
    if state.get("clock") and stop_sampled:
        state["clock"].append(stop_sampled)
    if meta is None:
        sidecar["sync"] = {"clock": "none", "accuracy_ms": None,
                           "why": "this screenrecord wrote no metadata track"}
    else:
        sidecar["frames"] = meta["frames"]
        sidecar["sync"] = {"clock": meta["clock"], "unit_ns": int(meta["unit"]),
                           "frame_count": len(meta["frames"]),
                           "epoch_offset_ns": meta.get("epoch_offset_ns")}
        clocks = state.get("clock") or []
        if clocks:
            offsets = [sample["offset"] for sample in clocks]
            errors = [sample["error"] for sample in clocks]
            sidecar["sync"]["offset"] = sum(offsets) / len(offsets)
            sidecar["sync"]["offset_samples"] = len(offsets)
            # The spread of the samples is the honest error of the offset; half a
            # round trip is the floor. A frame at 30 fps is 33 ms, so this says
            # whether the mapping is frame accurate or only approximate.
            sidecar["sync"]["offset_spread_ms"] = 1000.0 * (
                max(offsets) - min(offsets)) / 2.0
            sidecar["sync"]["round_trip_ms"] = 1000.0 * sum(errors) / len(errors)
        if pts and len(pts) != len(meta["frames"]):
            # The remux keeps every video frame but numbers them its own way; a
            # count that differs means an index would point at the wrong picture.
            print("capture: %d frames in the container, %d in the metadata: "
                  "events keep their frame index, and --at still works"
                  % (len(pts), len(meta["frames"])))
    sidecar["events"] = map_events(args.dir, sidecar)
    with open(os.path.join(args.dir, "recording.json"), "w") as handle:
        json.dump(sidecar, handle, indent=2)
    print_summary(args.dir, sidecar)
    return 0


def map_events(directory, sidecar):
    frames = sidecar.get("frames") or []
    sync = sidecar.get("sync") or {}
    pts = container_pts(os.path.join(directory, RECORDING))
    out = []
    path = os.path.join(directory, EVENTS)
    if not os.path.isfile(path):
        return out
    for line in open(path, errors="replace"):
        line = line.strip()
        if not line:
            continue
        try:
            event = json.loads(line)
        except ValueError:
            continue
        mapped = {"name": event.get("name"), "host": event.get("host")}
        if event.get("data"):
            mapped["data"] = event["data"]
        if frames and "offset" in sync:
            device_ns = (event["host"] + sync["offset"]) * 1e9
            at = 0
            for index, stamp in enumerate(frames):
                if stamp <= device_ns:
                    at = index
                else:
                    break
            mapped["frame"] = at
            # How long before the event that frame had appeared. A still screen
            # produces no new frames, so this is often seconds and is not an
            # error: that frame was what was on screen. Negative means the event
            # came before anything had been drawn, which only the start event of
            # a run does.
            mapped["since_frame_ms"] = round((device_ns - frames[at]) / 1e6, 1)
            if device_ns < frames[0]:
                mapped["before_video"] = True
            if at < len(pts):
                mapped["video_s"] = round(pts[at], 3)
            else:
                mapped["video_s"] = round(pts[-1] + (device_ns - frames[-1]) / 1e9, 3)
        out.append(mapped)
    return out


def print_summary(directory, sidecar):
    sync = sidecar.get("sync") or {}
    frames = sidecar.get("frames") or []
    events = sidecar.get("events") or []
    print("capture: %s  %.1f s  %.1f MB  %d frame(s)"
          % (sidecar["recording"], sidecar["duration"],
             sidecar["size"] / 1e6, len(frames)))
    if sync.get("clock") == "none":
        print("capture: no per-frame timestamps: events are not mapped")
        return
    period = 0.0
    if len(frames) > 1:
        gaps = sorted((b - a) / sync["unit_ns"] for a, b in zip(frames, frames[1:]))
        period = gaps[len(gaps) // 2]
    print("capture: clock %s%s, events mapped %d/%d, typical frame %.0f ms, "
          "clock offset error %.0f ms (%.1f ms round trip)"
          % (sync["clock"], " (per-frame)" if frames else "",
             sum(1 for e in events if "frame" in e), len(events), period * 1000,
             sync.get("offset_spread_ms") or 0.0, sync.get("round_trip_ms") or 0.0))


def cmd_show(args):
    directory = args.dir
    sidecar_path = os.path.join(directory, "recording.json")
    if not os.path.isfile(sidecar_path):
        sys.exit("capture: no recording.json in %s (run stop first)" % directory)
    with open(sidecar_path) as handle:
        sidecar = json.load(handle)
    print_summary(directory, sidecar)
    for event in sidecar.get("events") or []:
        if "frame" not in event:
            print("  %-40s (not mapped)" % event["name"])
            continue
        print("  %-40s frame %4d  at %7.3fs  %6.0f ms after that frame"
              % (event["name"], event["frame"], event["video_s"],
                 event["since_frame_ms"]))
    return 0


def frame_command(recording, video_s, out):
    # Seek just before the frame's own pts: ffmpeg seeks to a keyframe and decodes
    # forward, and `-frames:v 1` then hands over the first frame at or after the
    # seek, which is the frame that was on screen.
    return ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-ss", "%.6f" % max(0.0, video_s - 0.001), "-i", recording,
            "-map", "0:v:0", "-frames:v", "1", out]


def cmd_frames(args):
    directory = args.dir
    sidecar_path = args.sidecar or os.path.join(directory, "recording.json")
    if not os.path.isfile(sidecar_path):
        sys.exit("capture: no recording.json; run stop first")
    with open(sidecar_path) as handle:
        sidecar = json.load(handle)
    recording = args.recording or os.path.join(directory, sidecar["recording"])
    if not os.path.isfile(recording):
        sys.exit("capture: no recording at %s" % recording)
    events = sidecar.get("events") or []
    wanted = [name.strip() for name in (args.event or "").split(",") if name.strip()]
    missing = [name for name in wanted
               if not any(e["name"] == name and "video_s" in e for e in events)]
    if missing:
        sys.exit("capture: no mapped event named: " + ", ".join(missing))
    picks = []
    if args.all:
        picks = [e for e in events if "video_s" in e]
    for name in wanted:
        # A repeated name (a check that marks its own start and end with the same
        # name) gives every moment, not one of them.
        picks.extend([e for e in events if e["name"] == name and "video_s" in e])
    for value in (args.at or "").split(","):
        value = value.strip()
        if value:
            picks.append({"name": "at-%s" % value, "video_s": float(value)})
    if not picks:
        sys.exit("capture: say what to extract: --event NAME, --at SECONDS, or --all")
    os.makedirs(args.out, exist_ok=True)
    for pick in picks:
        slug = re.sub(r"[^A-Za-z0-9_.-]+", "-", pick["name"]).strip("-")
        out = os.path.join(args.out, "%07.3fs-%s.png" % (pick["video_s"], slug))
        result = subprocess.run(frame_command(recording, pick["video_s"], out),
                                capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit("capture: ffmpeg failed at %.3fs: %s"
                     % (pick["video_s"], result.stderr.strip()))
        print("%s  %.3fs  %s" % (out, pick["video_s"], pick.get("name", "")))
    return 0


def main(argv):
    parser = argparse.ArgumentParser(
        description="Record a run once, keep the moments as events, extract "
                    "frames when asked.")
    parser.add_argument("--dir", default="samples/out/capture",
                        help="where the recording and its events live")
    sub = parser.add_subparsers(dest="command", required=True)

    def with_dir(parser, **kwargs):
        """Every subcommand takes --dir: the shell writes it after the verb."""
        parser.add_argument("--dir", dest="sub_dir", default="")
        return parser

    start = with_dir(sub.add_parser("start", help="start recording the device's screen"))
    start.add_argument("--serial", default="")
    start.add_argument("--size", default="", help="WxH; native by default")
    start.add_argument("--bit-rate", dest="bit_rate", default="6M")
    start.set_defaults(run=cmd_start)

    event = with_dir(sub.add_parser("event", help="mark a moment; no adb call"))
    event.add_argument("name")
    event.add_argument("--data", action="append", metavar="KEY=VALUE")
    event.set_defaults(run=cmd_event)

    stop = with_dir(sub.add_parser("stop", help="stop, pull, and map the events to frames"))
    stop.set_defaults(run=cmd_stop)

    show = with_dir(sub.add_parser("show", help="the mapped events of a recording"))
    show.set_defaults(run=cmd_show)

    frames = with_dir(sub.add_parser("frames", help="extract frames, now"))
    frames.add_argument("--event", default="", help="event name(s), comma separated")
    frames.add_argument("--at", default="", help="seconds into the video, comma separated")
    frames.add_argument("--all", action="store_true", help="every mapped event")
    frames.add_argument("--out", default="samples/out/frames")
    frames.add_argument("--recording", default="")
    frames.add_argument("--sidecar", default="")
    frames.set_defaults(run=cmd_frames)

    args = parser.parse_args(argv)
    # `--dir` is accepted on either side of the verb; the shell writes it after.
    args.dir = args.sub_dir or args.dir
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
