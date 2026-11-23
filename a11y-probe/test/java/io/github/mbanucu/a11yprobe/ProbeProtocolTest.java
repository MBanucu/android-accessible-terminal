package io.github.mbanucu.a11yprobe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The probe's protocol, off the device: the JSON a dump emits, the batch line
 * grammar, and the reply/result bookkeeping.
 *
 * <p>The classes under test import no {@code android.*}; {@code
 * a11y-probe/test.sh} compiles them and this file with a plain {@code javac}.
 * The JSON sample is also handed to {@code python3 -c 'json.loads(...)'}, which
 * is the same parser an acceptance check uses on the device.
 */
public final class ProbeProtocolTest {

    private static int passed;
    private static int failed;

    private ProbeProtocolTest() {
    }

    public static void main(String[] args) {
        jsonEncoding();
        batchParsing();
        arguments();
        repliesAndResults();

        System.out.println();
        System.out.println((passed + failed) + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // --- JSON ---------------------------------------------------------------

    private static void jsonEncoding() {
        List<ProbeNode> nodes = List.of(
                new ProbeNode(0, "android.widget.FrameLayout", null, null, null,
                        0, 0, 1080, 2400, List.of(), false, false, false, false),
                new ProbeNode(1, "android.widget.TextView", "hello", "a row",
                        "io.example:id/row", 0, 10, 1080, 60,
                        List.of("CLICK", "A11Y_FOCUS"), false, true, true, false));
        String json = ProbeJson.dump("io.example", 2, nodes, 0);

        check("json: one line", "false", String.valueOf(json.contains("\n")));
        check("json: package", "true", String.valueOf(json.startsWith(
                "{\"package\":\"io.example\",\"windows\":2,\"truncated\":false,\"nodes\":[")));
        check("json: a null text is null", "true",
                String.valueOf(json.contains("\"text\":null,\"contentDescription\":null")));
        check("json: text and description", "true",
                String.valueOf(json.contains("\"text\":\"hello\",\"contentDescription\":\"a row\"")));
        check("json: class", "true",
                String.valueOf(json.contains("\"class\":\"android.widget.TextView\"")));
        check("json: bounds", "true", String.valueOf(json.contains(
                "\"bounds\":{\"left\":0,\"top\":10,\"right\":1080,\"bottom\":60}")));
        check("json: actions", "true",
                String.valueOf(json.contains("\"actions\":[\"CLICK\",\"A11Y_FOCUS\"]")));
        check("json: editable/clickable", "true",
                String.valueOf(json.contains("\"editable\":false,\"clickable\":true")));
        check("json: id", "true",
                String.valueOf(json.contains("\"id\":\"io.example:id/row\"")));
        check("json: closed", "true", String.valueOf(json.endsWith("]}")));

        System.out.println("JSON-SAMPLE " + json);

        StringBuilder escaped = new StringBuilder();
        ProbeJson.string(escaped, "a\"b\\c\nd\te\u0001");
        check("json: escapes", "\"a\\\"b\\\\c\\nd\\te\\u0001\"", escaped.toString());
        StringBuilder nil = new StringBuilder();
        ProbeJson.string(nil, null);
        check("json: null", "null", nil.toString());

        List<ProbeNode> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new ProbeNode(0, "android.widget.TextView", "row " + i, null, null,
                    0, i, 100, i + 1, List.of("CLICK"), false, true, false, false));
        }
        String capped = ProbeJson.dump("io.example", 1, many, 260);
        check("json: capped is truncated", "true",
                String.valueOf(capped.contains("\"truncated\":true")));
        check("json: capped is still closed", "true", String.valueOf(capped.endsWith("]}")));
        check("json: capped is shorter", "true",
                String.valueOf(capped.length() < ProbeJson.dump("io.example", 1, many, 0).length()));
    }

    // --- batch --------------------------------------------------------------

    private static void batchParsing() {
        String body = "find OK\n"
                + "dump name=tree json=true\n"
                + "\n"
                + "# a comment, not a command\n"
                + "wait done timeout=3000\n";
        List<ProbeBatch.Line> lines = ProbeBatch.parse(body);
        check("batch: one command per line", "3", String.valueOf(lines.size()));
        check("batch: first method", "find", lines.get(0).method);
        check("batch: positional argument", "OK", lines.get(0).args.arg());
        check("batch: second method", "dump", lines.get(1).method);
        check("batch: named arguments", "tree", lines.get(1).args.get("name"));
        check("batch: boolean argument", "true", lines.get(1).args.get("json"));
        check("batch: third method", "wait", lines.get(2).method);
        check("batch: number argument", "3000", lines.get(2).args.get("timeout"));

        ProbeBatch.Line spaces = ProbeBatch.parse("set_text echo hello world").get(0);
        check("batch: argument keeps spaces", "echo hello world", spaces.args.arg());

        ProbeBatch.Line value = ProbeBatch.parse("set_text text=echo hi there").get(0);
        check("batch: value keeps spaces", "echo hi there", value.args.get("text"));

        ProbeBatch.Line two = ProbeBatch.parse("set_text find=prompt text=echo hi").get(0);
        check("batch: two values", "prompt|echo hi",
                two.args.get("find") + "|" + two.args.get("text"));

        ProbeBatch.Line quoted = ProbeBatch.parse("find \"a key=value\"").get(0);
        check("batch: a quoted value hides keys", "a key=value", quoted.args.arg());

        check("batch: empty body", "0", String.valueOf(ProbeBatch.parse("").size()));
        check("batch: null body", "0", String.valueOf(ProbeBatch.parse(null).size()));

        check("batch: ok line", "2 code=0 data=dumped tree.txt",
                ProbeBatch.resultLine(2, ProbeReply.ok("dumped tree.txt")));
        check("batch: newlines escaped", "1 code=400 data=bad\\nline",
                ProbeBatch.resultLine(1, ProbeReply.error("bad\nline")));
        check("batch: state line", "1 code=0 data=matched seq=7 state=matched",
                ProbeBatch.resultLine(1, ProbeReply.state("matched seq=7", "matched")));
    }

    // --- arguments ----------------------------------------------------------

    private static void arguments() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("pkg", "io.example");
        defaults.put("json", "true");
        defaults.put("find", "outer");
        ProbeArgs line = ProbeBatch.parse("find inner").get(0).args;
        ProbeArgs merged = line.inherit(new ProbeArgs(null, defaults),
                "pkg", "json", "timeout");
        check("args: shared package inherited", "io.example", merged.get("pkg"));
        check("args: shared json inherited", "true", merged.get("json"));
        check("args: own argument wins", "inner", merged.firstOrArg("find"));
        check("args: unselected default not inherited", "null", String.valueOf(merged.get("find")));
        check("args: an internal value fills a gap", "7",
                merged.withDefault("since", "7").get("since"));
        check("args: an internal value does not overwrite", "io.example",
                merged.withDefault("pkg", "internal").get("pkg"));

        ProbeArgs numbers = new ProbeArgs(null, Map.of("timeout", "12", "async", "yes"));
        check("args: long", "12", String.valueOf(numbers.longValue(5, "timeout")));
        check("args: default long", "5", String.valueOf(numbers.longValue(5, "waitMs")));
        check("args: bool", "true", String.valueOf(numbers.bool("async")));
        check("args: missing bool", "false", String.valueOf(numbers.bool("json")));
        try {
            numbers.longValue(5, "async");
            check("args: a non-number is an error", "exception", "accepted");
        } catch (IllegalArgumentException expected) {
            check("args: a non-number is an error", "true",
                    String.valueOf(expected.getMessage().contains("not a number")));
        }

        check("args: path separators cleaned", ".._.._etc_passwd",
                ProbeArgs.safeFileName("../../etc/passwd", "tree"));
        check("args: empty name falls back", "tree", ProbeArgs.safeFileName("", "tree"));
        check("args: dot dot falls back", "tree", ProbeArgs.safeFileName("..", "tree"));
        check("args: null falls back", "tree", ProbeArgs.safeFileName(null, "tree"));
        check("args: ordinary name kept", "e2e-tui_2.json",
                ProbeArgs.safeFileName("e2e-tui_2.json", "tree"));
    }

    // --- replies and results ------------------------------------------------

    private static void repliesAndResults() {
        ProbeReply ok = ProbeReply.ok("dumped");
        check("reply: ok code", "0", String.valueOf(ok.code));
        check("reply: ok", "true", String.valueOf(ok.ok()));
        check("reply: log is the data", "dumped", ok.logLine());

        ProbeReply error = ProbeReply.error("no node matching 'nope'");
        check("reply: error code", "400", String.valueOf(error.code));
        check("reply: error is not ok", "false", String.valueOf(error.ok()));
        check("reply: error sentence", "no node matching 'nope'", error.data);

        ProbeReply dump = ProbeReply.ok("the whole tree", "dumped tree.txt (14 chars)");
        check("reply: summary is the log line", "dumped tree.txt (14 chars)", dump.logLine());
        check("reply: data is still the tree", "the whole tree", dump.data);

        ProbeResults results = new ProbeResults(2);
        check("results: reserve", "true", String.valueOf(results.reserve("a")));
        check("results: a second reserve collides", "false",
                String.valueOf(results.reserve("a")));
        check("results: reserved is pending", "null", String.valueOf(results.get("a")));
        check("results: reserved exists", "true", String.valueOf(results.has("a")));
        results.complete("a", "matched seq=1");
        check("results: completed", "matched seq=1", results.get("a"));

        results.reserve("b");
        results.reserve("c");
        check("results: oldest evicted", "false", String.valueOf(results.has("a")));
        check("results: newest kept", "true", String.valueOf(results.has("c")));
        check("results: size stays bounded", "2", String.valueOf(results.size()));
    }

    // --- reporting ----------------------------------------------------------

    private static void check(String what, String expected, String actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.println("ok   " + what);
        } else {
            failed++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
