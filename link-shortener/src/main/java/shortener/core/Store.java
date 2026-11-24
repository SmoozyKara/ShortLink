package shortener.core;

import java.io.IOException;

public interface Store {
    State load() throws IOException;
    void save(State state) throws IOException;
}
