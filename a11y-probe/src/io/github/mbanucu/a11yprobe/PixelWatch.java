package io.github.mbanucu.a11yprobe;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import io.github.mbanucu.screenstream.ScreenStream;

/**
 * Pixels, watched on the phone by the probe rather than pulled off it by adb.
 *
 * <p>The suite used to run {@code adb exec-out screencap} for every pixel check:
 * a 1440x3200 PNG crossed the wire, went through ffmpeg, and a script counted it
 * on the other side. That made the screen a picture taken at some moment after
 * the fact, and the frame it landed on was whatever the next capture happened to
 * catch. This class is the same assertion made where the pixels are: an
 * accessibility service may take the screen itself ({@code canTakeScreenshot}),
 * so the frame, its number and its time are all taken here, and what crosses the
 * wire is a few lines of text.
 *
 * <p>Three things follow from that, and they are the point:
 *
 * <ul>
 *   <li>Only changed frames are counted. The watch compares each screenshot with
 *       the one before it and drops the ones that changed nothing, so the frames
 *       numbered here are the frames where something happened - the same rule
 *       {@code screenrecord} applies to the video, and the reason a gap between
 *       two numbers is idle time rather than a lost frame.</li>
 *   <li>A frame is timed when the screen showed it, not when it was asked for.
 *       {@link AccessibilityService.ScreenshotResult#getTimestamp()} is the
 *       device's elapsed clock, which is the clock the video's per-frame metadata
 *       uses - so the frame a check passes on can be pointed at in the recording
 *       without an offset.</li>
 *   <li>The check passes on the first frame that holds what it asked for,
 *       instead of on a screenshot taken once some seconds have gone by. The
 *       blink check is only honest this way: it needs two different phases of a
 *       caret, and it says which frame showed each.</li>
 * </ul>
 *
 * <p>The platform throttles these screenshots to one every 333 ms
 * ({@code ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS}), so the
 * watch paces itself a little above that and retries when it is turned away.
 *
 * <p>When the probe's screen stream is running, the watch judges the stream's
 * changed frames instead of pacing screenshots, so a change shorter than 333 ms
 * is still seen and the numbers and times it reports are the stream's - the same
 * numbers a consumer on the socket sees. One screenshot is taken first, of the
 * screen the watch starts on, because a stream says what changed and not what is
 * already there: a check that starts while the app is already drawn has to be
 * able to pass on the screen it was given. The answer says which source it used
 * ({@code source=stream} or {@code source=screenshots}).
 *
 * <p>What it looks at is described by the extras that come with the command; see
 * {@link #start}. The answer is written to {@code files/<name>.txt} once, at the
 * end, so a test polls for {@code result=} rather than for a moving file.
 */
final class PixelWatch {

    /** Every 4th pixel in each direction: enough for a fill, cheap enough per frame. */
    private static final int STRIDE = 4;
    /** One request per 360 ms: the platform floor is 333, and this keeps off it. */
    private static final long INTERVAL_MS = 360;
    /** A screenshot that never answers is a failure, not a wait. */
    private static final long ANSWER_MS = 3000;
    /**
     * How long a "is the app in front" answer is kept. Asking costs a binder
     * round trip per window, which is more than counting a frame's pixels; the
     * foreground does not change between frames, and a frame judged against a
     * 200 ms old answer is a frame of the app that was in front a 200 ms ago.
     */
    private static final long IN_FRONT_MS = 200;

    /** Nearest-neighbour: a sampled colour is a real pixel's colour, not a blend. */
    private static final Paint NEAREST = new Paint();

    static {
        NEAREST.setFilterBitmap(false);
        NEAREST.setAntiAlias(false);
        NEAREST.setDither(false);
    }

    private PixelWatch() {
    }

    /**
     * Starts a watch and returns at once; the answer arrives in {@code name.txt}.
     *
     * @param name       file to answer in, so two checks do not overwrite each other
     * @param region     {@code x,y,w,h} to count colours in; the whole screen when null
     * @param colours    {@code label,rmin,rmax,gmin,gmax,bmin,bmax,min} entries, ':' between
     * @param cellGrid   {@code l,t,r,b,cols}: a row of the view divided into the grid's columns
     * @param cells      {@code col,min,max} entries, ':' between, means of a cell's brightness
     * @param across     true when the cells may be satisfied by different frames (a blink)
     * @param timeoutMs  how long to keep looking
     */
    static String start(AccessibilityService service, String name, String region,
            String colours, String cellGrid, String cells, boolean across, long timeoutMs) {
        if (service == null) {
            return "pixels: the probe service is not running";
        }
        File directory = ProbeService.files();
        if (directory == null) {
            return "pixels: no files directory to answer in";
        }
        Watch watch;
        try {
            watch = new Watch(service, directory, name, region, colours, cellGrid, cells,
                    across, timeoutMs);
        } catch (IllegalArgumentException wrong) {
            return "pixels: " + wrong.getMessage();
        }
        new Thread(watch, "pixel-watch").start();
        return "pixels: watching " + name + " (" + watch.describe() + ")";
    }

    /** One colour to count: inclusive channel bounds, and how many pixels must match. */
    private static final class Colour {
        final String label;
        final int rMin, rMax, gMin, gMax, bMin, bMax, min;
        int count;

        Colour(String spec) {
            String[] parts = spec.split(",");
            if (parts.length != 8) {
                throw new IllegalArgumentException(
                        "a colour is label,rmin,rmax,gmin,gmax,bmin,bmax,min: " + spec);
            }
            label = parts[0].trim();
            rMin = number(parts[1], 0);
            rMax = number(parts[2], 255);
            gMin = number(parts[3], 0);
            gMax = number(parts[4], 255);
            bMin = number(parts[5], 0);
            bMax = number(parts[6], 255);
            min = number(parts[7], 0);
        }

        boolean matches(int pixel) {
            int r = (pixel >> 16) & 0xff;
            int g = (pixel >> 8) & 0xff;
            int b = pixel & 0xff;
            return r >= rMin && r <= rMax && g >= gMin && g <= gMax && b >= bMin && b <= bMax;
        }

        boolean met() {
            return count >= min;
        }
    }

    /** One cell's mean brightness, which is how a block cursor is told from a blank cell. */
    private static final class Cell {
        final int column, min, max;
        int mean = -1;
        int metFrame = -1;
        long metTimestamp;
        int metMean = -1;

        Cell(String spec) {
            String[] parts = spec.split(",");
            if (parts.length != 3) {
                throw new IllegalArgumentException("a cell is col,min,max: " + spec);
            }
            column = number(parts[0], 0);
            min = number(parts[1], 0);
            max = number(parts[2], 255);
        }

        boolean met() {
            return mean >= min && mean <= max;
        }
    }

    /** One screenshot, in the two shapes a watch needs, with the moment it showed. */
    private static final class Frame {
        final int[] sample;
        final int sampleWidth, sampleHeight;
        final int[] row;
        final int width, height;
        final long timestampNs;
        /**
         * The source's number for this frame: the stream's count of changes when
         * the frames come from there, the watch's own count of changed screenshots
         * when they do not. Either way it is the number the answer reports, so it
         * means the same thing as the number a consumer on the socket sees.
         */
        long number;

        Frame(int[] sample, int sampleWidth, int sampleHeight, int[] row,
                int width, int height, long timestampNs) {
            this.sample = sample;
            this.sampleWidth = sampleWidth;
            this.sampleHeight = sampleHeight;
            this.row = row;
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
        }
    }

    private static final class Watch implements Runnable {
        private final AccessibilityService service;
        private final File directory;
        private final String name;
        private final int[] region;
        private final int[] grid;
        private final List<Colour> colours = new ArrayList<>();
        private final List<Cell> cells = new ArrayList<>();
        private final boolean across;
        private final long timeoutMs;
        private final String targetPackage;

        private Bitmap sampleBitmap, rowBitmap;
        private int[] previous;

        // The stream path's arrays are reused frame to frame: a tap runs on the
        // capture thread, so what it allocates is a pause in the stream. Screenshot
        // frames are kept instead, because the watch compares each with the last.
        private int[] sampleBuffer, sampleColumns, rowBuffer;
        private int streamWidth = -1, streamHeight = -1;
        private boolean front, frontKnown;
        private long frontChecked;
        /** One judge at a time: a tap runs on the capture thread, the first frame here. */
        private final Object judging = new Object();

        private String source;
        private int looked, frames;
        private boolean passed;
        private String failure;
        private Frame last;
        private int metFrame = -1;
        private long metTimestamp;
        private volatile boolean finished;

        Watch(AccessibilityService service, File directory, String name, String region,
                String colours, String cellGrid, String cells, boolean across, long timeoutMs) {
            this.service = service;
            this.directory = directory;
            this.name = name;
            this.region = rectangle(region, "region", 4);
            this.grid = rectangle(cellGrid, "cellgrid", 5);
            this.across = across;
            this.timeoutMs = Math.max(1000, Math.min(timeoutMs, 60000));
            this.targetPackage = ProbeService.targetPackage();
            if (colours != null && !colours.trim().isEmpty()) {
                // ':' between entries and ',' inside one: neither is a character
                // a shell has an opinion about, because this string crosses one.
                for (String spec : colours.split(":")) {
                    if (!spec.trim().isEmpty()) {
                        this.colours.add(new Colour(spec.trim()));
                    }
                }
            }
            if (cells != null && !cells.trim().isEmpty()) {
                if (this.grid == null) {
                    throw new IllegalArgumentException("cells need a cellgrid to divide");
                }
                for (String spec : cells.split(":")) {
                    if (!spec.trim().isEmpty()) {
                        this.cells.add(new Cell(spec.trim()));
                    }
                }
            }
            if (this.colours.isEmpty() && this.cells.isEmpty()) {
                throw new IllegalArgumentException("nothing to watch: give colours or cells");
            }
        }

        String describe() {
            StringBuilder text = new StringBuilder();
            if (region != null) {
                text.append("region=").append(region[0]).append(',').append(region[1])
                        .append(',').append(region[2]).append(',').append(region[3]);
            }
            for (Colour colour : colours) {
                text.append(' ').append(colour.label).append(">=").append(colour.min);
            }
            if (grid != null) {
                text.append(" grid=").append(grid[0]).append(',').append(grid[1])
                        .append(',').append(grid[2]).append(',').append(grid[3])
                        .append('/').append(grid[4]);
            }
            for (Cell cell : cells) {
                text.append(" cell").append(cell.column).append('=').append(cell.min)
                        .append("..").append(cell.max);
            }
            if (across) {
                text.append(" across");
            }
            return text.toString().trim();
        }

        @Override
        public void run() {
            ProbeService.writeReplace(name + ".txt",
                    "state=watching\nspec=" + describe() + "\n");
            long started = SystemClock.elapsedRealtime();
            if (!watchStream(started)) {
                source = "screenshots";
                watchScreenshots(started);
            }
            answer(started);
        }

        /** Screenshots, a little faster than the platform's 333 ms floor. */
        private void watchScreenshots(long started) {
            long lastRequest = 0;
            while (SystemClock.elapsedRealtime() - started < timeoutMs) {
                long since = SystemClock.elapsedRealtime() - lastRequest;
                if (since < INTERVAL_MS) {
                    sleep(INTERVAL_MS - since);
                }
                lastRequest = SystemClock.elapsedRealtime();
                Object answer = screenshot();
                if (answer instanceof Integer) {
                    int error = (Integer) answer;
                    // Turned away for asking too soon is the one answer to retry:
                    // anything else means this service cannot see the screen.
                    if (error == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        continue;
                    }
                    failure = "screenshot failed with error " + error;
                    break;
                }
                looked++;
                Frame frame;
                try {
                    frame = read((AccessibilityService.ScreenshotResult) answer);
                } catch (RuntimeException unreadable) {
                    failure = "could not read the screenshot: " + unreadable;
                    break;
                }
                boolean changed = previous == null || !Arrays.equals(previous, frame.sample);
                if (!changed) {
                    continue;
                }
                // The frame owns its array: a new one is read for every shot, so
                // this keeps the last frame's pixels and nothing else's.
                previous = frame.sample;
                // A frame of the launcher or a keyboard is not this app's drawing:
                // waiting for the app to be in front is what keeps the colour that
                // was on screen from the previous check out of this one's answer.
                if (!inFront()) {
                    continue;
                }
                frames++;
                frame.number = frames;
                last = frame;
                measure(last);
                if (judge(frame)) {
                    passed = true;
                    break;
                }
            }
        }

        /**
         * Frames from the screenstream module instead of screenshots.
         *
         * <p>The compositor has already dropped the frames that changed nothing
         * and numbered the rest, so there is no pacing here and nothing to compare
         * with the frame before: whatever arrives is a change to judge. That is the
         * difference the stream makes to a check - a caret that blinks between two
         * screenshots is two frames here, and the numbers and times are the ones a
         * consumer on the socket sees.
         *
         * <p>The tap runs on the capture thread and releases the frame it was given.
         *
         * @return false when nothing is capturing, so the caller falls back
         */
        private boolean watchStream(long started) {
            final CountDownLatch done = new CountDownLatch(1);
            ScreenStream.Tap tap = frame -> {
                try {
                    synchronized (judging) {
                        if (finished || passed) {
                            return;
                        }
                        Frame read;
                        try {
                            read = readStreamFrame(frame);
                        } catch (RuntimeException unreadable) {
                            failure = "could not read a stream frame: " + unreadable;
                            done.countDown();
                            return;
                        }
                        if (!inFrontNow()) {
                            return;
                        }
                        frames++;
                        read.number = frame.number;
                        last = read;
                        measure(read);
                        if (judge(read)) {
                            passed = true;
                            done.countDown();
                        }
                    }
                } finally {
                    frame.release();
                }
            };
            if (!ScreenStream.subscribe(tap)) {
                return false;
            }
            source = "stream";
            try {
                // A stream answers "what changed", not "what is on the screen". A
                // check that starts while the app is already drawn - a caret that
                // does not blink, say - would otherwise wait on a screen that stays
                // still and see nothing at all. So the screen the watch starts on is
                // judged once, numbered as the frame the source changed last, which
                // is still the frame on the display.
                if (!passed) {
                    Frame first = screenshotNow();
                    if (first != null) {
                        synchronized (judging) {
                            if (!passed) {
                                first.number = ScreenStream.frameNumber();
                                if (inFrontNow()) {
                                    frames++;
                                    last = first;
                                    measure(first);
                                    if (judge(first)) {
                                        passed = true;
                                    }
                                }
                            }
                        }
                    }
                }
                if (!passed) {
                    done.await(Math.max(1, timeoutMs - (SystemClock.elapsedRealtime() - started)),
                            TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                finished = true;
                ScreenStream.unsubscribe(tap);
            }
            return true;
        }

        /**
         * One screenshot of the screen as it is now, or null when it was refused.
         *
         * <p>Asked again when the platform says it is too soon after the last one:
         * another watch in the same run may have taken it microseconds ago.
         */
        private Frame screenshotNow() {
            long deadline = SystemClock.elapsedRealtime() + 1500;
            while (SystemClock.elapsedRealtime() < deadline) {
                Object taken = screenshot();
                if (taken instanceof Integer) {
                    if ((Integer) taken == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        sleep(120);
                        continue;
                    }
                    return null;
                }
                looked++;
                try {
                    return read((AccessibilityService.ScreenshotResult) taken);
                } catch (RuntimeException unreadable) {
                    failure = "could not read the screenshot: " + unreadable;
                    return null;
                }
            }
            return null;
        }

        /** Whether one frame holds what was asked for; the blink needs two frames. */
        private boolean judge(Frame frame) {
            if (across) {
                for (Cell cell : cells) {
                    if (cell.metFrame < 0 && cell.met()) {
                        cell.metFrame = (int) frame.number;
                        cell.metTimestamp = frame.timestampNs;
                        cell.metMean = cell.mean;
                    }
                }
                boolean met = true;
                for (Cell cell : cells) {
                    met &= cell.metFrame >= 0;
                }
                if (met) {
                    metFrame = (int) frame.number;
                    metTimestamp = frame.timestampNs;
                }
                return met;
            }
            boolean met = true;
            for (Colour colour : colours) {
                met &= colour.met();
            }
            for (Cell cell : cells) {
                met &= cell.met();
            }
            if (met) {
                metFrame = (int) frame.number;
                metTimestamp = frame.timestampNs;
            }
            return met;
        }

        /** The answer, written once: a test polls for {@code result=}, not a change. */
        private void answer(long started) {
            StringBuilder out = new StringBuilder();
            out.append("result=").append(passed ? "pass" : failure != null ? "error" : "timeout")
                    .append('\n');
            out.append("source=").append(source).append('\n');
            out.append("looked=").append(looked).append('\n');
            out.append("frames=").append(frames).append('\n');
            out.append("took_ms=").append(SystemClock.elapsedRealtime() - started).append('\n');
            if (passed) {
                out.append("frame=").append(metFrame).append('\n');
                out.append("ts_ns=").append(metTimestamp).append('\n');
            } else if (failure != null) {
                out.append("why=").append(failure).append('\n');
            } else if (frames == 0) {
                out.append("why=").append("stream".equals(source)
                        ? "the screen never changed" : "the app was never in front").append('\n');
            } else {
                out.append("why=no frame held what was asked for\n");
            }
            for (Colour colour : colours) {
                out.append("count.").append(colour.label).append('=').append(colour.count)
                        .append('\n');
            }
            for (int i = 0; i < cells.size(); i++) {
                Cell cell = cells.get(i);
                out.append("cell.").append(cell.column).append('=').append(cell.mean).append('\n');
                if (cell.metFrame >= 0) {
                    // Which frame met this one, and what it read there: the blink
                    // is those two lines being about different frames.
                    out.append("met.").append(i + 1).append(".frame=")
                            .append(cell.metFrame).append('\n');
                    out.append("met.").append(i + 1).append(".ts_ns=")
                            .append(cell.metTimestamp).append('\n');
                    out.append("met.").append(i + 1).append(".cell.").append(cell.column)
                            .append('=').append(cell.metMean).append('\n');
                }
            }
            if (last != null) {
                out.append("size=").append(last.width).append('x').append(last.height).append('\n');
            }
            ProbeService.writeReplace(name + ".txt", out.toString());
        }

        /** The newest screenshot, or the error code it was refused with. */
        private Object screenshot() {
            final BlockingQueue<Object> answer = new ArrayBlockingQueue<>(2);
            Executor direct = new Executor() {
                @Override
                public void execute(Runnable runnable) {
                    runnable.run();
                }
            };
            service.takeScreenshot(Display.DEFAULT_DISPLAY, direct,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            answer.offer(result);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            answer.offer(errorCode);
                        }
                    });
            try {
                Object value = answer.poll(ANSWER_MS, TimeUnit.MILLISECONDS);
                return value == null ? AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                        : value;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR;
            }
        }

        /**
         * The screenshot's pixels: every fourth one for counting and for telling
         * whether the screen changed, and the watched row at full size for the
         * cell means, where a block cursor is a handful of pixels wide.
         */
        private Frame read(AccessibilityService.ScreenshotResult result) {
            HardwareBuffer buffer = result.getHardwareBuffer();
            Bitmap shot = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            try {
                Bitmap software = shot.copy(Bitmap.Config.ARGB_8888, false);
                try {
                    int width = software.getWidth();
                    int height = software.getHeight();
                    int sampleWidth = Math.max(1, width / STRIDE);
                    int sampleHeight = Math.max(1, height / STRIDE);
                    if (sampleBitmap == null || sampleBitmap.getWidth() != sampleWidth
                            || sampleBitmap.getHeight() != sampleHeight) {
                        sampleBitmap = Bitmap.createBitmap(sampleWidth, sampleHeight,
                                Bitmap.Config.ARGB_8888);
                        previous = null;
                    }
                    Canvas canvas = new Canvas(sampleBitmap);
                    canvas.drawBitmap(software, null, new Rect(0, 0, sampleWidth, sampleHeight),
                            NEAREST);
                    int[] sample = new int[sampleWidth * sampleHeight];
                    sampleBitmap.getPixels(sample, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight);
                    int[] row = null;
                    if (grid != null) {
                        Rect source = new Rect(
                                clamp(grid[0], 0, width), clamp(grid[1], 0, height),
                                clamp(grid[2], 0, width), clamp(grid[3], 0, height));
                        int rowWidth = Math.max(1, source.width());
                        int rowHeight = Math.max(1, source.height());
                        if (rowBitmap == null || rowBitmap.getWidth() != rowWidth
                                || rowBitmap.getHeight() != rowHeight) {
                            rowBitmap = Bitmap.createBitmap(rowWidth, rowHeight,
                                    Bitmap.Config.ARGB_8888);
                        }
                        Canvas rowCanvas = new Canvas(rowBitmap);
                        rowCanvas.drawBitmap(software, source,
                                new Rect(0, 0, rowWidth, rowHeight), NEAREST);
                        row = new int[rowWidth * rowHeight];
                        rowBitmap.getPixels(row, 0, rowWidth, 0, 0, rowWidth, rowHeight);
                    }
                    return new Frame(sample, sampleWidth, sampleHeight, row,
                            width, height, nanos(result.getTimestamp()));
                } finally {
                    software.recycle();
                }
            } finally {
                shot.recycle();
                buffer.close();
            }
        }

        /**
         * A stream frame in the same two shapes a screenshot is read into: every
         * fourth pixel for counting, and the watched row at full size for the cell
         * means. The stream's pixels are RGBA at the display's own row stride, so
         * the sample grid is the same one the screenshot path builds - which is
         * what makes a count from one comparable with a count from the other.
         */
        private Frame readStreamFrame(ScreenStream.Frame frame) {
            byte[] pixels = frame.pixels;
            int width = frame.width;
            int height = frame.height;
            int stride = frame.stride;
            int sampleWidth = Math.max(1, width / STRIDE);
            int sampleHeight = Math.max(1, height / STRIDE);
            if (sampleBuffer == null || sampleBuffer.length != sampleWidth * sampleHeight
                    || streamWidth != width || streamHeight != height) {
                sampleBuffer = new int[sampleWidth * sampleHeight];
                // Which pixel each sample stands for, worked out once: a division
                // per sample is most of what this used to cost per frame.
                sampleColumns = new int[sampleWidth];
                for (int sx = 0; sx < sampleWidth; sx++) {
                    sampleColumns[sx] = (sx * width / sampleWidth) * 4;
                }
                streamWidth = width;
                streamHeight = height;
            }
            int[] sample = sampleBuffer;
            for (int sy = 0; sy < sampleHeight; sy++) {
                int rowStart = (sy * height / sampleHeight) * stride;
                int out = sy * sampleWidth;
                for (int sx = 0; sx < sampleWidth; sx++) {
                    int index = rowStart + sampleColumns[sx];
                    if (index + 3 >= pixels.length) {
                        continue;
                    }
                    sample[out + sx] = argb(pixels, index);
                }
            }
            int[] row = null;
            if (grid != null) {
                int left = clamp(grid[0], 0, width);
                int top = clamp(grid[1], 0, height);
                int right = clamp(grid[2], left, width);
                int bottom = clamp(grid[3], top, height);
                int rowWidth = Math.max(1, right - left);
                int rowHeight = Math.max(1, bottom - top);
                if (rowBuffer == null || rowBuffer.length != rowWidth * rowHeight) {
                    rowBuffer = new int[rowWidth * rowHeight];
                }
                row = rowBuffer;
                for (int y = 0; y < rowHeight; y++) {
                    int base = (top + y) * stride + left * 4;
                    int out = y * rowWidth;
                    for (int x = 0; x < rowWidth; x++) {
                        int index = base + x * 4;
                        if (index + 3 >= pixels.length) {
                            continue;
                        }
                        row[out + x] = argb(pixels, index);
                    }
                }
            }
            return new Frame(sample, sampleWidth, sampleHeight, row, width, height,
                    frame.timestampNs);
        }

        /** One RGBA byte quadruple as the ARGB int a screenshot's bitmap holds. */
        private static int argb(byte[] pixels, int index) {
            return 0xff000000 | ((pixels[index] & 0xff) << 16)
                    | ((pixels[index + 1] & 0xff) << 8) | (pixels[index + 2] & 0xff);
        }

        /** Fills in the counts and the cell means of one frame. */
        private void measure(Frame frame) {
            int x0 = 0;
            int y0 = 0;
            int x1 = frame.sampleWidth;
            int y1 = frame.sampleHeight;
            if (region != null) {
                x0 = clamp(region[0] / STRIDE, 0, frame.sampleWidth);
                y0 = clamp(region[1] / STRIDE, 0, frame.sampleHeight);
                x1 = clamp((region[0] + region[2] + STRIDE - 1) / STRIDE, x0, frame.sampleWidth);
                y1 = clamp((region[1] + region[3] + STRIDE - 1) / STRIDE, y0, frame.sampleHeight);
            }
            for (Colour colour : colours) {
                colour.count = 0;
            }
            if (!colours.isEmpty()) {
                for (int y = y0; y < y1; y++) {
                    int base = y * frame.sampleWidth;
                    for (int x = x0; x < x1; x++) {
                        int pixel = frame.sample[base + x];
                        for (Colour colour : colours) {
                            if (colour.matches(pixel)) {
                                colour.count++;
                            }
                        }
                    }
                }
                // Each counted pixel stands for the 4x4 block it samples, so the
                // number is the same quantity the old full-frame count was; it is
                // an estimate of area, not a count of every pixel.
                for (Colour colour : colours) {
                    colour.count *= STRIDE * STRIDE;
                }
            }
            if (frame.row != null && grid != null) {
                for (Cell cell : cells) {
                    cell.mean = mean(frame.row, grid, cell.column);
                }
            }
        }

        /** Mean of a cell's channels, the same number the pixel dumps used to give. */
        private static int mean(int[] row, int[] grid, int column) {
            int width = Math.max(1, grid[2] - grid[0]);
            int height = Math.max(1, grid[3] - grid[1]);
            int cols = Math.max(1, grid[4]);
            int x0 = (int) Math.round((double) width * column / cols);
            int x1 = (int) Math.round((double) width * (column + 1) / cols);
            x0 = clamp(x0, 0, width);
            x1 = clamp(x1, x0 + 1, width);
            long sum = 0;
            long count = 0;
            for (int y = 0; y < height; y++) {
                int base = y * width;
                for (int x = x0; x < x1; x++) {
                    int pixel = row[base + x];
                    sum += ((pixel >> 16) & 0xff) + ((pixel >> 8) & 0xff) + (pixel & 0xff);
                    count += 3;
                }
            }
            return count == 0 ? -1 : (int) (sum / count);
        }

        /** {@link #inFront}, asked at most once every {@link #IN_FRONT_MS}. */
        private boolean inFrontNow() {
            long now = SystemClock.elapsedRealtime();
            if (frontKnown && now - frontChecked < IN_FRONT_MS) {
                return front;
            }
            front = inFront();
            frontChecked = now;
            frontKnown = true;
            return front;
        }

        /** True while the app under test is on screen, so another app's frame is skipped. */
        private boolean inFront() {
            if (targetPackage == null || targetPackage.isEmpty()) {
                return true;
            }
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null) {
                return false;
            }
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                boolean match = targetPackage.contentEquals(root.getPackageName());
                root.recycle();
                if (match) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * A screenshot's moment, in the nanoseconds the video's frame times use.
     *
     * <p>{@code getTimestamp()} is documented as nanoseconds; on this device it is
     * milliseconds of uptime - 45,825,671 against /proc/uptime's 45,842 s - so the
     * unit is decided by magnitude: an uptime in milliseconds stays under 10^11
     * for three years, and one in nanoseconds passes it after a hundred seconds.
     */
    private static long nanos(long stamp) {
        return stamp >= 100000000000L ? stamp : stamp * 1000000L;
    }

    private static int[] rectangle(String spec, String label, int fields) {
        if (spec == null || spec.trim().isEmpty()) {
            return null;
        }
        String[] parts = spec.split(",");
        if (parts.length != fields) {
            throw new IllegalArgumentException(label + " wants " + fields + " numbers: " + spec);
        }
        int[] values = new int[fields];
        for (int i = 0; i < fields; i++) {
            values[i] = number(parts[i], Integer.MIN_VALUE);
        }
        return values;
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            if (fallback == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("not a number: " + text);
            }
            return fallback;
        }
    }

    private static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
