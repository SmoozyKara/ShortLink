package shortener.core;

import java.net.URI;
import java.util.Locale;

public final class Urls {
    private Urls() { }

    public static URI parse(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme != null && (scheme.toLowerCase(Locale.ROOT).equals("http")
                    || scheme.toLowerCase(Locale.ROOT).equals("https"))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getPort() <= 65535 && uri.getPort() != 0) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Общая понятная ошибка ниже.
        }
        throw new IllegalArgumentException("Введите корректный абсолютный HTTP(S) URL");
    }
}
