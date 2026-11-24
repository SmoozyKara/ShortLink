package shortener;

import shortener.core.LinkService;
import shortener.infra.CodeGenerator;
import shortener.infra.Config;
import shortener.infra.DesktopBrowser;
import shortener.infra.FileStore;
import shortener.ui.Console;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        try {
            if (args.length > 1) {
                throw new IllegalArgumentException("Использование: java ... shortener.Main [config.properties]");
            }
            Config config = Config.load(Path.of(args.length == 0 ? "config.properties" : args[0]));
            Files.createDirectories(config.dataFile().getParent());
            Path lockPath = config.dataFile().resolveSibling(config.dataFile().getFileName() + ".lock");
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) {
                    throw new IOException("Файл данных уже используется другим экземпляром приложения");
                }
                var service = new LinkService(new FileStore(config.dataFile()), config.settings(), Clock.systemUTC(),
                        new CodeGenerator(config.settings().codeLength()), new DesktopBrowser());
                var console = new Console(service, new PrintWriter(System.out, true, StandardCharsets.UTF_8));
                var scheduler = Executors.newSingleThreadScheduledExecutor();
                try {
                    long interval = config.settings().cleanupMillis();
                    scheduler.scheduleWithFixedDelay(console::tick, interval, interval, TimeUnit.MILLISECONDS);
                    console.run(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                } finally {
                    scheduler.shutdownNow();
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        throw new IOException("Планировщик не завершился вовремя");
                    }
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            System.err.println("Работа приложения прервана.");
            System.exit(1);
        } catch (IOException | RuntimeException error) {
            System.err.println("Не удалось запустить или завершить приложение: " + error.getMessage());
            System.exit(1);
        }
    }
}
