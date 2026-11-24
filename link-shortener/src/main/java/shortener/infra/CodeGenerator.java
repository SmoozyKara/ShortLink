package shortener.infra;

import java.security.SecureRandom;
import java.util.function.Supplier;

public final class CodeGenerator implements Supplier<String> {
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final SecureRandom random = new SecureRandom();
    private final int length;

    public CodeGenerator(int length) {
        this.length = length;
    }

    @Override
    public String get() {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }
}
