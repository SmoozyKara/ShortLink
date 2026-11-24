package shortener.core;

import java.net.URI;
import java.time.Duration;

public record Settings(Duration ttl, int defaultLimit, int maxLimit, String prefix,
                       int codeLength, long cleanupMillis) {
    public Settings {
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofDays(3650)) > 0
                || defaultLimit < 1 || defaultLimit > maxLimit || cleanupMillis < 1
                || codeLength < 4 || codeLength > 32) {
            throw new IllegalArgumentException("Некорректные параметры конфигурации");
        }
        URI uri = Urls.parse(prefix);
        if (!prefix.endsWith("/") || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Префикс должен завершаться / и не содержать query/fragment");
        }
    }
}
