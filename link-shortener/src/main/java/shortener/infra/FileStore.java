package shortener.infra;

import shortener.core.Link;
import shortener.core.State;
import shortener.core.Store;
import shortener.core.Urls;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;
import java.util.UUID;

public final class FileStore implements Store {
    private final Path file;

    public FileStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    @Override
    public State load() throws IOException {
        if (Files.notExists(file)) {
            return State.empty();
        }
        Properties p = new Properties();
        try (var input = Files.newBufferedReader(file)) {
            p.load(input);
        }
        try {
            if (!"1".equals(p.getProperty("version"))) {
                throw new IllegalArgumentException("Неизвестная версия хранилища");
            }
            var users = new HashSet<UUID>();
            var issued = new HashSet<String>();
            var links = new HashMap<String, Link>();
            var notices = new ArrayList<State.Notice>();
            for (String key : p.stringPropertyNames()) {
                if (key.startsWith("user.")) {
                    users.add(UUID.fromString(key.substring(5)));
                } else if (key.startsWith("issued.")) {
                    issued.add(key.substring(7));
                } else if (key.startsWith("link.") && key.endsWith(".url")) {
                    String code = key.substring(5, key.length() - 4);
                    String base = "link." + code + ".";
                    Link link = new Link(code, Urls.parse(p.getProperty(key)),
                            UUID.fromString(p.getProperty(base + "owner")),
                            Instant.parse(p.getProperty(base + "expires")),
                            Integer.parseInt(p.getProperty(base + "limit")),
                            Integer.parseInt(p.getProperty(base + "clicks")));
                    if (link.limit() < 1 || link.clicks() < 0 || link.clicks() > link.limit()) {
                        throw new IllegalArgumentException("Некорректный счётчик");
                    }
                    links.put(code, link);
                }
            }
            int count = Integer.parseInt(p.getProperty("notice.count", "0"));
            if (count < 0) {
                throw new IllegalArgumentException("Некорректное число уведомлений");
            }
            for (int i = 0; i < count; i++) {
                String base = "notice." + i + ".";
                notices.add(new State.Notice(UUID.fromString(p.getProperty(base + "owner")),
                        java.util.Objects.requireNonNull(p.getProperty(base + "message"))));
            }
            for (Link link : links.values()) {
                if (!users.contains(link.owner()) || !issued.contains(link.code())) {
                    throw new IllegalArgumentException("Нарушена целостность ссылок");
                }
            }
            for (State.Notice notice : notices) {
                if (!users.contains(notice.owner())) {
                    throw new IllegalArgumentException("Неизвестный получатель уведомления");
                }
            }
            return new State(users, issued, links, notices);
        } catch (RuntimeException error) {
            throw new IOException("Повреждён файл данных: " + file, error);
        }
    }

    @Override
    public void save(State state) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1");
        state.users().forEach(id -> p.setProperty("user." + id, "true"));
        state.issued().forEach(code -> p.setProperty("issued." + code, "true"));
        for (Link link : state.links().values()) {
            String base = "link." + link.code() + ".";
            p.setProperty(base + "url", link.url().toString());
            p.setProperty(base + "owner", link.owner().toString());
            p.setProperty(base + "expires", link.expiresAt().toString());
            p.setProperty(base + "limit", Integer.toString(link.limit()));
            p.setProperty(base + "clicks", Integer.toString(link.clicks()));
        }
        p.setProperty("notice.count", Integer.toString(state.notices().size()));
        for (int i = 0; i < state.notices().size(); i++) {
            State.Notice notice = state.notices().get(i);
            String base = "notice." + i + ".";
            p.setProperty(base + "owner", notice.owner().toString());
            p.setProperty(base + "message", notice.message());
        }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".state-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp)) {
                p.store(writer, "Link shortener state");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException("Хранилище требует файловую систему с атомарным перемещением", error);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
