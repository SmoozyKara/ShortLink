package shortener.core;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record Link(String code, URI url, UUID owner, Instant expiresAt, int limit, int clicks) {
    public boolean blocked() {
        return clicks >= limit;
    }

    public Link withClicks(int value) {
        return new Link(code, url, owner, expiresAt, limit, value);
    }

    public Link withLimit(int value) {
        return new Link(code, url, owner, expiresAt, value, clicks);
    }

    public Link withUrl(URI value) {
        return new Link(code, value, owner, expiresAt, limit, clicks);
    }
}
