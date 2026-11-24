package shortener;

import shortener.core.Browser;
import shortener.core.LinkService;
import shortener.core.Settings;
import shortener.core.State;
import shortener.core.Store;
import shortener.core.Urls;
import shortener.infra.CodeGenerator;
import shortener.infra.Config;
import shortener.infra.FileStore;
import shortener.ui.Console;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class TestRunner {
    private static final String URL = "https://example.org/long/path?x=1";
    private static final Settings SETTINGS = new Settings(Duration.ofSeconds(10), 3, 20,
            "https://short.local/", 7, 10);
    private static int passed;
    private static int failed;

    private TestRunner() { }

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    private static final class MemoryStore implements Store {
        private State state = State.empty();
        private boolean fail;
        public State load() { return state; }
        public void save(State value) throws IOException {
            if (fail) { throw new IOException("test write error"); }
            state = value;
        }
    }

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }

    private static final class Fixture {
        final MemoryStore store = new MemoryStore();
        final TestClock clock = new TestClock();
        final List<URI> opened = new ArrayList<>();
        final LinkService service;
        Fixture() throws IOException { this(null, null); }
        Fixture(Supplier<String> codes, Browser browser) throws IOException {
            AtomicInteger seq = new AtomicInteger();
            service = new LinkService(store, SETTINGS, clock,
                    codes == null ? () -> String.format("code%03d", seq.incrementAndGet()) : codes,
                    browser == null ? opened::add : browser);
        }
        LinkService.Created create(int limit) throws IOException { return service.create(null, URL, limit); }
    }

    public static void main(String[] args) {
        test("unit: valid HTTP(S)", () -> eq(URL, Urls.parse(URL).toString()));
        test("unit: invalid URLs", () -> {
            for (String value : List.of("abc", "ftp://example.org", "https://", "file:///tmp/a", "https://u:p@example.org", "https://example.org:65536")) {
                expect(IllegalArgumentException.class, () -> Urls.parse(value));
            }
        });
        test("unit: generator alphabet and length", () -> {
            var gen = new CodeGenerator(7);
            for (int i = 0; i < 100; i++) { ok(gen.get().matches("[A-Za-z0-9]{7}")); }
        });
        test("unit: configuration loaded", () -> {
            var config = Config.load(java.nio.file.Path.of("config.properties"));
            ok(!config.settings().ttl().isNegative() && !config.settings().ttl().isZero());
        });
        test("unit: invalid configuration", () -> expect(IllegalArgumentException.class,
                () -> new Settings(Duration.ZERO, 2, 1, "https://short.local/", 7, 1)));
        test("unit: UUID only after successful creation", () -> {
            var f = new Fixture();
            expect(IllegalArgumentException.class, () -> f.service.create(null, "bad", 2));
            eq(0, f.store.load().users().size());
            ok(f.create(2).owner() != null);
        });
        test("unit: same URL different users", () -> {
            var f = new Fixture(); var a = f.create(2); var b = f.create(2);
            ok(!a.owner().equals(b.owner())); ok(!a.link().code().equals(b.link().code()));
        });
        test("unit: collision retry", () -> {
            var queue = new java.util.ArrayDeque<>(List.of("aaaa", "aaaa", "bbbb"));
            var f = new Fixture(queue::remove, null);
            eq("aaaa", f.create(1).link().code()); eq("bbbb", f.create(1).link().code());
        });
        test("unit: multiple links and isolation", () -> {
            var f = new Fixture(); var a = f.create(2); var b = f.create(2);
            f.service.create(a.owner(), URL, 1);
            eq(2, f.service.list(a.owner()).size()); eq(1, f.service.list(b.owner()).size());
        });
        test("unit: default and invalid limits", () -> {
            var f = new Fixture(); eq(3, f.service.create(null, URL, null).link().limit());
            expect(IllegalArgumentException.class, () -> f.create(0));
            expect(IllegalArgumentException.class, () -> f.create(21));
        });
        test("unit: exact limit and last allowed visit", () -> {
            var f = new Fixture(); var a = f.create(2); String code = a.link().code();
            eq(1, f.service.open(code).clicks()); eq(2, f.service.open(f.service.shortUrl(code)).clicks());
            expect(IllegalStateException.class, () -> f.service.open(code)); eq(2, f.opened.size());
            eq(URI.create(URL), f.opened.get(0));
        });
        test("unit: failed browser restores counter", () -> {
            var f = new Fixture(null, url -> { throw new IOException("no browser"); }); var a = f.create(1);
            expect(IOException.class, () -> f.service.open(a.link().code()));
            eq(0, f.service.list(a.owner()).get(0).clicks()); eq(0, f.service.takeNotices(a.owner()).size());
        });
        test("unit: failed disk write prevents browser opening", () -> {
            var f = new Fixture(); var a = f.create(1); f.store.fail = true;
            expect(IOException.class, () -> f.service.open(a.link().code())); eq(0, f.opened.size());
        });
        test("unit: owner only edits and deletes", () -> {
            var f = new Fixture(); var a = f.create(2); var b = f.create(2); String code = a.link().code();
            expect(IllegalArgumentException.class, () -> f.service.editLimit(b.owner(), code, 3));
            expect(IllegalArgumentException.class, () -> f.service.editUrl(b.owner(), code, URL));
            expect(IllegalArgumentException.class, () -> f.service.delete(b.owner(), code));
            expect(IllegalArgumentException.class, () -> f.service.delete(null, code));
            eq(3, f.service.editLimit(a.owner(), code, 3).limit());
            eq(URI.create("https://example.com"), f.service.editUrl(a.owner(), code, "https://example.com").url());
            f.service.delete(a.owner(), code); eq(0, f.service.list(a.owner()).size());
        });
        test("unit: limit edit retains visits and permits reactivation", () -> {
            var f = new Fixture(); var a = f.create(2); String code = a.link().code();
            f.service.open(code); f.service.open(code);
            expect(IllegalArgumentException.class, () -> f.service.editLimit(a.owner(), code, 1));
            eq(2, f.service.editLimit(a.owner(), code, 3).clicks()); eq(3, f.service.open(code).clicks());
        });
        test("unit: exact TTL boundary", () -> {
            var f = new Fixture(); var a = f.create(2); f.clock.advance(9);
            eq(0, f.service.cleanup()); f.clock.advance(1); eq(1, f.service.cleanup());
            eq(0, f.service.list(a.owner()).size()); expect(IllegalArgumentException.class, () -> f.service.open(a.link().code()));
        });
        test("unit: TTL checked without scheduled cleanup", () -> {
            var f = new Fixture(); var a = f.create(2); f.clock.advance(11);
            expect(IllegalArgumentException.class, () -> f.service.open(a.link().code())); eq(0, f.opened.size());
        });
        test("unit: notices delivered only to owner once", () -> {
            var f = new Fixture(); var a = f.create(1); var b = f.create(2); f.service.open(a.link().code());
            eq(0, f.service.takeNotices(b.owner()).size());
            ok(f.service.takeNotices(a.owner()).get(0).contains("лимит")); eq(0, f.service.takeNotices(a.owner()).size());
            f.clock.advance(10); f.service.cleanup();
            ok(f.service.takeNotices(a.owner()).get(0).contains("срок"));
            eq(1, f.service.takeNotices(b.owner()).size());
        });
        test("unit: unknown UUID rejected", () -> {
            var f = new Fixture(); expect(IllegalArgumentException.class, () -> f.service.requireUser(UUID.randomUUID()));
        });
        test("integration: file restart, users, counter and notification", () -> {
            var dir = Files.createTempDirectory("shortener-test-");
            try {
                var file = dir.resolve("state.properties"); var clock = new TestClock();
                var s1 = new LinkService(new FileStore(file), SETTINGS, clock, () -> "aaaa", url -> { });
                var a = s1.create(null, URL, 1); s1.open(a.link().code());
                var s2 = new LinkService(new FileStore(file), SETTINGS, clock, () -> "bbbb", url -> { });
                eq(1, s2.list(a.owner()).get(0).clicks()); eq(1, s2.takeNotices(a.owner()).size());
                clock.advance(10);
                var s3 = new LinkService(new FileStore(file), SETTINGS, clock, () -> "bbbb", url -> { });
                eq(0, s3.list(a.owner()).size()); eq(1, s3.takeNotices(a.owner()).size());
                eq("bbbb", s3.create(a.owner(), URL, null).link().code());
            } finally { Files.deleteIfExists(dir.resolve("state.properties")); Files.deleteIfExists(dir); }
        });
        test("integration: CLI commands and fake browser", () -> {
            var f = new Fixture(); var text = new StringWriter(); var cli = new Console(f.service, new PrintWriter(text));
            cli.run(new StringReader("help\ncreate https://example.org 1\nlist\nhttps://short.local/code001\nopen code001\nnew\ncreate bad 2\nexit\n"));
            ok(text.toString().contains("Ваш UUID")); ok(text.toString().contains("Уведомление:"));
            ok(text.toString().contains("Лимит переходов исчерпан")); ok(text.toString().contains("Ошибка:"));
            eq(1, f.opened.size());
        });
        test("integration: scheduled cleanup while idle", () -> {
            var f = new Fixture(); var text = new StringWriter(); var cli = new Console(f.service, new PrintWriter(text));
            cli.execute("create https://example.org 1"); f.clock.advance(10);
            var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            try { scheduler.schedule(cli::tick, 1, java.util.concurrent.TimeUnit.MILLISECONDS).get(2, java.util.concurrent.TimeUnit.SECONDS); }
            finally { scheduler.shutdownNow(); }
            ok(text.toString().contains("срок действия истёк")); eq(0, f.store.load().links().size());
        });
        test("integration: simultaneous requests do not bypass limit", () -> {
            var f = new Fixture(); var a = f.create(3); var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
            try {
                var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
                for (int i = 0; i < 12; i++) { tasks.add(() -> {
                    try { f.service.open(a.link().code()); return true; }
                    catch (IllegalStateException error) { return false; }
                }); }
                int successes = 0;
                for (var result : pool.invokeAll(tasks)) { if (result.get()) { successes++; } }
                eq(3, successes); eq(3, f.opened.size());
            } finally { pool.shutdownNow(); }
        });
        System.out.printf("%nPassed: %d; failed: %d%n", passed, failed);
        if (failed > 0) { System.exit(1); }
    }

    private static void test(String name, Check check) {
        try { check.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { failed++; System.err.println("FAIL " + name); error.printStackTrace(); }
    }
    private static void ok(boolean condition) { if (!condition) { throw new AssertionError("Expected true"); } }
    private static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) { throw new AssertionError("Expected " + expected + ", got " + actual); }
    }
    private static void expect(Class<? extends Throwable> type, Check check) throws Exception {
        try { check.run(); }
        catch (Exception error) { if (type.isInstance(error)) { return; } throw error; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
