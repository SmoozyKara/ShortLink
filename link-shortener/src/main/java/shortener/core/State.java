package shortener.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record State(Set<UUID> users, Set<String> issued, Map<String, Link> links,
                    List<Notice> notices) {
    public State {
        users = Set.copyOf(users);
        issued = Set.copyOf(issued);
        links = Map.copyOf(links);
        notices = List.copyOf(notices);
    }

    public static State empty() {
        return new State(Set.of(), Set.of(), Map.of(), List.of());
    }

    public record Notice(UUID owner, String message) { }
}
