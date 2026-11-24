package shortener.core;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class LinkService {
    private final Store store;
    private final Settings settings;
    private final Clock clock;
    private final Supplier<String> codes;
    private final Browser browser;
    private State state;

    public LinkService(Store store, Settings settings, Clock clock, Supplier<String> codes,
                       Browser browser) throws IOException {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.codes = codes;
        this.browser = browser;
        state = store.load();
        cleanup();
    }

    public record Created(UUID owner, Link link) { }

    public synchronized Created create(UUID owner, String url, Integer requestedLimit) throws IOException {
        var uri = Urls.parse(url);
        int limit = requestedLimit == null ? settings.defaultLimit() : requestedLimit;
        checkLimit(limit);
        cleanup();
        Set<UUID> users = new HashSet<>(state.users());
        if (owner == null) {
            do {
                owner = UUID.randomUUID();
            } while (users.contains(owner));
            users.add(owner);
        } else {
            requireUser(owner);
        }
        String code = null;
        for (int i = 0; i < 1000; i++) {
            String candidate = codes.get();
            if (candidate != null && candidate.matches("[A-Za-z0-9]{4,32}")
                    && !state.issued().contains(candidate)) {
                code = candidate;
                break;
            }
        }
        if (code == null) {
            throw new IllegalStateException("Не удалось выделить уникальный код");
        }
        Link link = new Link(code, uri, owner, clock.instant().plus(settings.ttl()), limit, 0);
        Set<String> issued = new HashSet<>(state.issued());
        issued.add(code);
        Map<String, Link> links = new HashMap<>(state.links());
        links.put(code, link);
        commit(new State(users, issued, links, state.notices()));
        return new Created(owner, link);
    }

    public synchronized Link open(String shortLink) throws IOException {
        cleanup();
        Link link = find(shortLink);
        if (link.blocked()) {
            throw new IllegalStateException("Лимит переходов исчерпан. Попросите владельца создать новую ссылку.");
        }
        // Сначала резервируем переход на диске, чтобы сбой записи не позволил обойти лимит.
        State before = state;
        Link updated = link.withClicks(link.clicks() + 1);
        replace(updated, updated.blocked() ? limitNotice(updated) : null);
        try {
            browser.open(link.url());
        } catch (IOException | RuntimeException error) {
            try {
                commit(before);
            } catch (IOException rollbackError) {
                error.addSuppressed(rollbackError);
                throw new IOException("Браузер не открыт; резерв перехода не удалось отменить", error);
            }
            throw error;
        }
        return updated;
    }

    public synchronized Link editLimit(UUID owner, String shortLink, int limit) throws IOException {
        checkLimit(limit);
        cleanup();
        Link link = owned(owner, shortLink);
        if (limit < link.clicks()) {
            throw new IllegalArgumentException("Лимит не может быть меньше числа совершённых переходов");
        }
        Link updated = link.withLimit(limit);
        replace(updated, !link.blocked() && updated.blocked() ? limitNotice(updated) : null);
        return updated;
    }

    public synchronized Link editUrl(UUID owner, String shortLink, String url) throws IOException {
        var uri = Urls.parse(url);
        cleanup();
        Link updated = owned(owner, shortLink).withUrl(uri);
        replace(updated, null);
        return updated;
    }

    public synchronized void delete(UUID owner, String shortLink) throws IOException {
        cleanup();
        Link link = owned(owner, shortLink);
        Map<String, Link> links = new HashMap<>(state.links());
        links.remove(link.code());
        commit(new State(state.users(), state.issued(), links, state.notices()));
    }

    public synchronized List<Link> list(UUID owner) throws IOException {
        requireUser(owner);
        cleanup();
        return state.links().values().stream().filter(link -> link.owner().equals(owner))
                .sorted(Comparator.comparing(Link::code)).toList();
    }

    public synchronized void requireUser(UUID owner) {
        if (owner == null || !state.users().contains(owner)) {
            throw new IllegalArgumentException("Нет такого пользователя. Создайте ссылку или войдите по своему UUID.");
        }
    }

    public synchronized List<String> takeNotices(UUID owner) throws IOException {
        requireUser(owner);
        cleanup();
        List<String> result = state.notices().stream().filter(n -> n.owner().equals(owner))
                .map(State.Notice::message).toList();
        if (!result.isEmpty()) {
            List<State.Notice> remaining = state.notices().stream().filter(n -> !n.owner().equals(owner)).toList();
            commit(new State(state.users(), state.issued(), state.links(), remaining));
        }
        return result;
    }

    public synchronized int cleanup() throws IOException {
        Instant now = clock.instant();
        Map<String, Link> links = new HashMap<>(state.links());
        List<State.Notice> notices = new ArrayList<>(state.notices());
        int count = 0;
        for (Link link : state.links().values()) {
            if (!now.isBefore(link.expiresAt())) {
                links.remove(link.code());
                notices.add(new State.Notice(link.owner(), shortUrl(link.code())
                        + ": срок действия истёк, ссылка удалена. Создайте новую ссылку."));
                count++;
            }
        }
        if (count > 0) {
            commit(new State(state.users(), state.issued(), links, notices));
        }
        return count;
    }

    public String shortUrl(String code) {
        return settings.prefix() + code;
    }

    private Link find(String input) {
        String code = input.startsWith(settings.prefix()) ? input.substring(settings.prefix().length()) : input;
        Link link = state.links().get(code);
        if (link == null) {
            throw new IllegalArgumentException(state.issued().contains(code)
                    ? "Ссылка удалена или просрочена" : "Короткая ссылка не найдена");
        }
        return link;
    }

    private Link owned(UUID owner, String input) {
        requireUser(owner);
        Link link = find(input);
        if (!Objects.equals(owner, link.owner())) {
            throw new IllegalArgumentException("Нет прав: операция доступна только владельцу ссылки");
        }
        return link;
    }

    private void checkLimit(int limit) {
        if (limit < 1 || limit > settings.maxLimit()) {
            throw new IllegalArgumentException("Лимит должен быть от 1 до " + settings.maxLimit());
        }
    }

    private State.Notice limitNotice(Link link) {
        return new State.Notice(link.owner(), shortUrl(link.code())
                + ": лимит переходов исчерпан. Создайте новую ссылку или увеличьте лимит.");
    }

    private void replace(Link link, State.Notice notice) throws IOException {
        Map<String, Link> links = new HashMap<>(state.links());
        links.put(link.code(), link);
        List<State.Notice> notices = new ArrayList<>(state.notices());
        if (notice != null) {
            notices.add(notice);
        }
        commit(new State(state.users(), state.issued(), links, notices));
    }

    private void commit(State next) throws IOException {
        store.save(next);
        state = next;
    }
}
