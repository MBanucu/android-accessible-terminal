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
        selectors();
        expectations();
        nodeFormatting();

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

    // --- selectors ----------------------------------------------------------

    private static void selectors() {
        ProbeSelector text = ProbeSelector.parse("text=OK");
        check("selector: text kind", "TEXT", text.kind().name());
        check("selector: text value", "OK", text.value());
        check("selector: text label", "text=OK", text.label());

        ProbeSelector desc = ProbeSelector.parse("desc=Close");
        check("selector: desc kind", "DESC", desc.kind().name());
        check("selector: desc label", "desc=Close", desc.label());

        ProbeSelector id = ProbeSelector.parse("id=io.example:id/row");
        check("selector: id kind", "ID", id.kind().name());
        check("selector: id value", "io.example:id/row", id.value());

        ProbeSelector index = ProbeSelector.parse("index=2");
        check("selector: index kind", "INDEX", index.kind().name());
        check("selector: index number", "2", String.valueOf(index.index()));
        check("selector: index label", "index=2", index.label());

        ProbeSelector bare = ProbeSelector.parse("OK");
        check("selector: bare kind", "ANY", bare.kind().name());
        check("selector: bare label names its fields", "text|desc|id=OK", bare.label());

        check("selector: a value may contain equals", "a=b",
                ProbeSelector.parse("text=a=b").value());

        try {
            ProbeSelector.parse("nope=1");
            check("selector: unknown prefix is an error", "exception", "accepted");
        } catch (IllegalArgumentException expected) {
            check("selector: unknown prefix names the selectors", "true",
                    String.valueOf(expected.getMessage().contains(
                            "selectors: text=, desc=, id=, index=")));
        }
        try {
            ProbeSelector.parse("index=x");
            check("selector: index must be a number", "exception", "accepted");
        } catch (IllegalArgumentException expected) {
            check("selector: index number message", "true",
                    String.valueOf(expected.getMessage().contains("whole number")));
        }
        try {
            ProbeSelector.parse("index=-1");
            check("selector: index is zero-based", "exception", "accepted");
        } catch (IllegalArgumentException expected) {
            check("selector: index negative message", "true",
                    String.valueOf(expected.getMessage().contains("zero-based")));
        }
        try {
            ProbeSelector.parse("text=");
            check("selector: a named value is required", "exception", "accepted");
        } catch (IllegalArgumentException expected) {
            check("selector: empty text message", "true",
                    String.valueOf(expected.getMessage().contains("needs a value")));
        }

        List<ProbeNode> nodes = List.of(
                node("android.widget.TextView", "OK", "Close", "io.example:id/ok"),
                node("android.widget.TextView", "Item 01", "Row 01", "io.example:id/item01"),
                node("android.widget.TextView", "Ready", "Fixture status", null));
        check("selector: text matches", "true",
                String.valueOf(ProbeSelector.parse("text=OK").matches(nodes.get(0))));
        check("selector: text misses a description", "false",
                String.valueOf(ProbeSelector.parse("text=Close").matches(nodes.get(0))));
        check("selector: desc matches", "true",
                String.valueOf(ProbeSelector.parse("desc=Close").matches(nodes.get(0))));
        check("selector: id matches", "true",
                String.valueOf(ProbeSelector.parse("id=item01").matches(nodes.get(1))));
        check("selector: bare matches a description", "true",
                String.valueOf(ProbeSelector.parse("Fixture status").matches(nodes.get(2))));
        check("selector: bare misses", "false",
                String.valueOf(ProbeSelector.parse("nope").matches(nodes.get(2))));
        check("selector: index picks its position", "1",
                String.valueOf(ProbeSelector.parse("index=1").matchingIndexes(nodes).get(0)));
        check("selector: an index out of range matches nothing", "0",
                String.valueOf(ProbeSelector.parse("index=9").matchingIndexes(nodes).size()));
        check("selector: matched field is text", "text",
                ProbeSelector.parse("text=OK").matchedField(nodes.get(0)));
        check("selector: matched field is desc", "desc",
                ProbeSelector.parse("desc=Close").matchedField(nodes.get(0)));
        check("selector: matched field is id", "id",
                ProbeSelector.parse("id=item01").matchedField(nodes.get(1)));
        check("selector: bare reports the first field", "desc",
                ProbeSelector.parse("Close").matchedField(nodes.get(0)));

        check("selector: closest puts the shared prefix first", "Item 01",
                ProbeSelector.parse("text=Item 40").closest(nodes, 1).get(0).text);
        check("selector: no nodes is an explained empty list",
                "(none: 0 nodes scanned)",
                ProbeSelector.parse("text=x").candidates(List.of()));
        check("selector: an out-of-range index lists neighbours", "3",
                String.valueOf(ProbeSelector.parse("index=9")
                        .closest(nodes, 3).size()));
    }

    // --- assertions ---------------------------------------------------------

    private static void expectations() {
        List<ProbeNode> nodes = List.of(
                new ProbeNode(0, "android.widget.FrameLayout", null, null, null,
                        0, 0, 1080, 2400, List.of(), false, false, false, false),
                node("android.widget.TextView", "Item 01", "Row 01", "io.example:id/item01"),
                node("android.widget.TextView", "Ready", "Fixture status",
                        "io.example:id/status"),
                node("android.widget.TextView", "Saved: draft", null, null));

        ProbeReply exists = expect("exists", "text=Ready", null, nodes);
        check("expect: exists code", "0", String.valueOf(exists.code));
        check("expect: exists names the match", "true",
                String.valueOf(exists.data.contains("matched") && exists.data.contains("Ready")));

        ProbeReply missing = expect("exists", "text=Missing", null, nodes);
        check("expect: exists failure code", "400", String.valueOf(missing.code));
        check("expect: exists names the selector", "true",
                String.valueOf(missing.data.contains("expect exists text=Missing")));
        check("expect: exists lists candidates", "true",
                String.valueOf(missing.data.contains("closest:")));

        ProbeReply absent = expect("absent", "text=Missing", null, nodes);
        check("expect: absent code", "0", String.valueOf(absent.code));
        check("expect: absent says it scanned", "true",
                String.valueOf(absent.data.contains("no node matched (4 scanned)")));

        ProbeReply present = expect("absent", "text=Ready", null, nodes);
        check("expect: absent failure code", "400", String.valueOf(present.code));
        check("expect: absent shows the matched node", "true",
                String.valueOf(present.data.contains("first:") && present.data.contains("Ready")));

        ProbeReply exact = expect("text_is", "text=Ready", null, nodes);
        check("expect: text_is code", "0", String.valueOf(exact.code));
        check("expect: text_is names the node", "true",
                String.valueOf(exact.data.contains("text exactly 'Ready'")));

        ProbeReply notExact = expect("text_is", "text=Read", null, nodes);
        check("expect: text_is is exact, not contains", "400",
                String.valueOf(notExact.code));
        check("expect: text_is failure says exactly", "true",
                String.valueOf(notExact.data.contains("text exactly 'Read'")));

        ProbeReply contains = expect("text_contains", "text=draft", null, nodes);
        check("expect: text_contains code", "0", String.valueOf(contains.code));

        ProbeReply notContains = expect("text_contains", "text=saved", null, nodes);
        check("expect: case is kept", "400", String.valueOf(notContains.code));
        check("expect: a miss lists closest", "true",
                String.valueOf(notContains.data.contains("Saved: draft")));

        ProbeReply needsValue = expect("text_is", "desc=Fixture status", null, nodes);
        check("expect: a desc selector needs a value", "400",
                String.valueOf(needsValue.code));
        check("expect: the value message names the flag", "true",
                String.valueOf(needsValue.data.contains("--extra value:s:<text>")));

        ProbeReply byId = expect("text_is", "id=io.example:id/status", "Ready", nodes);
        check("expect: id plus value", "0", String.valueOf(byId.code));

        ProbeReply byIdContains = expect("text_contains", "id=io.example:id/item01",
                "Item", nodes);
        check("expect: id plus contains", "0", String.valueOf(byIdContains.code));

        ProbeReply unknown = expect("bogus", "text=Ready", null, nodes);
        check("expect: unknown assertion code", "400", String.valueOf(unknown.code));
        check("expect: unknown assertion lists the four", "true",
                String.valueOf(unknown.data.contains(
                        "exists, absent, text_is, text_contains")));

        check("expect: assertion names are case-insensitive", "true",
                String.valueOf(ProbeExpect.isAssertion("TEXT_CONTAINS")));
        check("expect: a selector is not an assertion", "false",
                String.valueOf(ProbeExpect.isAssertion("text=Ready")));

        ProbeExpect.Request compact = ProbeExpect.request("text_contains=saved", null);
        check("expect: compact assertion", "text_contains", compact.assertion);
        check("expect: compact selector", "saved", compact.selector);
        ProbeExpect.Request compound = ProbeExpect.request("absent=text=Item 01", null);
        check("expect: compact assertion with a selector", "absent", compound.assertion);
        check("expect: compact selector keeps key=value", "text=Item 01", compound.selector);
        ProbeExpect.Request explicit = ProbeExpect.request("saved", "text_is");
        check("expect: explicit assertion", "text_is", explicit.assertion);
        check("expect: explicit selector", "saved", explicit.selector);
        ProbeExpect.Request plain = ProbeExpect.request("text=Ready", null);
        check("expect: a plain selector defaults to exists", "exists", plain.assertion);
        try {
            ProbeExpect.request("text=Ready", "bogus");
            check("expect: an explicit assertion is checked", "exception", "accepted");
        } catch (IllegalArgumentException expected) {
            check("expect: explicit assertion message", "true",
                    String.valueOf(expected.getMessage().contains("unknown assertion")));
        }
    }

    // --- node formatting ----------------------------------------------------

    private static void nodeFormatting() {
        ProbeNode node = new ProbeNode(1, "android.widget.TextView", "hello", "a row",
                "io.example:id/row", 0, 10, 1080, 60,
                List.of("CLICK"), false, true, true, false);
        check("node: brief keeps text", "true",
                String.valueOf(node.brief().startsWith("text=hello")));
        check("node: brief keeps the description", "true",
                String.valueOf(node.brief().contains("desc=a row")));
        check("node: brief keeps the id", "true",
                String.valueOf(node.brief().contains("id=io.example:id/row")));
        check("node: brief keeps bounds", "true",
                String.valueOf(node.brief().contains("bounds=0,10,1080,60")));
        check("node: describe names the id", "true",
                String.valueOf(node.describe().contains("id=io.example:id/row")));
        check("node: describe names the description", "true",
                String.valueOf(node.describe().contains("desc=a row")));
        check("node: a null field is a dash", "text=-", node("x", null, null, null).brief()
                .replace(" desc=-", "").replace(" bounds=0,0,0,0", ""));
        check("node: newlines are escaped", "a\\nb", ProbeNode.quote("a\nb"));

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longText.append('x');
        }
        check("node: long text is cut and its length kept", "true",
                String.valueOf(ProbeNode.quote(longText).endsWith("(200)")));
    }

    private static ProbeNode node(String className, String text, String desc, String id) {
        return new ProbeNode(1, className, text, desc, id, 0, 0, 0, 0,
                List.of(), false, false, false, false);
    }

    private static ProbeReply expect(String assertion, String selector, String value,
            List<ProbeNode> nodes) {
        return ProbeExpect.evaluate(assertion, ProbeSelector.parse(selector), value, nodes);
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
