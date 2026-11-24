package shortener.ui;

import shortener.core.Link;
import shortener.core.LinkService;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.Scanner;
import java.util.UUID;

public final class Console {
    private final LinkService service;
    private final PrintWriter out;
    private UUID current;

    public Console(LinkService service, PrintWriter out) {
        this.service = service;
        this.out = out;
    }

    public void run(Reader input) {
        Scanner scanner = new Scanner(input);
        out.println("Сервис сокращения ссылок. Введите help для списка команд.");
        while (true) {
            out.print("> ");
            out.flush();
            if (!scanner.hasNextLine() || !execute(scanner.nextLine())) {
                break;
            }
        }
        out.println("До свидания!");
        out.flush();
    }

    public synchronized boolean execute(String line) {
        String clean = line.strip();
        if (clean.isEmpty()) {
            return true;
        }
        String[] args = clean.split("\\s+");
        try {
            switch (args[0]) {
                case "help" -> {
                    arity(args, 1, 1);
                    help();
                }
                case "create" -> {
                    arity(args, 2, 3);
                    boolean first = current == null;
                    var created = service.create(current, args[1], args.length == 3 ? number(args[2]) : null);
                    current = created.owner();
                    if (first) {
                        out.println("Ваш UUID (сохраните в секрете): " + current);
                    }
                    printLink(created.link());
                }
                case "login" -> {
                    arity(args, 2, 2);
                    UUID user = UUID.fromString(args[1]);
                    service.requireUser(user);
                    current = user;
                    out.println("Пользователь выбран.");
                }
                case "new", "logout" -> {
                    arity(args, 1, 1);
                    current = null;
                    out.println("Сессия завершена. Новый UUID будет создан командой create.");
                }
                case "whoami" -> {
                    arity(args, 1, 1);
                    out.println(current == null ? "UUID ещё не выбран" : current);
                }
                case "list" -> {
                    arity(args, 1, 1);
                    var links = service.list(current);
                    if (links.isEmpty()) {
                        out.println("У вас нет ссылок.");
                    }
                    links.forEach(this::printLink);
                }
                case "open" -> {
                    arity(args, 2, 2);
                    open(args[1]);
                }
                case "edit-limit" -> {
                    arity(args, 3, 3);
                    printLink(service.editLimit(current, args[1], number(args[2])));
                }
                case "edit-url" -> {
                    arity(args, 3, 3);
                    printLink(service.editUrl(current, args[1], args[2]));
                }
                case "delete" -> {
                    arity(args, 2, 2);
                    service.delete(current, args[1]);
                    out.println("Ссылка удалена.");
                }
                case "notifications" -> {
                    arity(args, 1, 1);
                    if (current == null) {
                        out.println("Сначала выберите пользователя.");
                    } else if (!showNotices()) {
                        out.println("Новых уведомлений нет.");
                    }
                }
                case "exit" -> {
                    arity(args, 1, 1);
                    return false;
                }
                default -> {
                    if (args.length == 1 && (args[0].startsWith(service.shortUrl(""))
                            || args[0].matches("[A-Za-z0-9]{4,32}"))) {
                        open(args[0]);
                    } else {
                        out.println("Неизвестная команда. Введите help.");
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            out.println("Ошибка: " + error.getMessage());
        }
        try {
            showNotices();
        } catch (IOException error) {
            out.println("Ошибка чтения уведомлений: " + error.getMessage());
        }
        out.flush();
        return true;
    }

    // Вызывается планировщиком, даже когда пользователь ничего не вводит.
    public synchronized void tick() {
        try {
            service.cleanup();
            showNotices();
        } catch (IOException | RuntimeException error) {
            out.println("Ошибка фоновой очистки: " + error.getMessage());
        }
        out.flush();
    }

    private boolean showNotices() throws IOException {
        if (current == null) {
            return false;
        }
        var notices = service.takeNotices(current);
        notices.forEach(message -> out.println("Уведомление: " + message));
        return !notices.isEmpty();
    }

    private void open(String value) throws IOException {
        Link link = service.open(value);
        out.println("Запрошено открытие браузера: " + link.url());
        out.println("Переходов: " + link.clicks() + "/" + link.limit());
        if (link.blocked()) {
            out.println("Лимит исчерпан, следующие переходы заблокированы.");
        }
    }

    private void printLink(Link link) {
        out.printf("%s -> %s | %d/%d | %s | до %s%n", service.shortUrl(link.code()), link.url(),
                link.clicks(), link.limit(), link.blocked() ? "ЗАБЛОКИРОВАНА" : "АКТИВНА", link.expiresAt());
    }

    private static int number(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Лимит должен быть целым числом");
        }
    }

    private static void arity(String[] args, int min, int max) {
        if (args.length < min || args.length > max) {
            throw new IllegalArgumentException("Неверное число аргументов. Введите help.");
        }
    }

    private void help() {
        out.println("""
                create URL [лимит]          создать ссылку (например create https://example.org 2)
                open КОД_ИЛИ_ССЫЛКА         открыть в браузере; можно просто ввести короткую ссылку
                list                        показать свои ссылки
                login UUID                  вернуться к своему пользователю
                new / logout                начать новую сессию без UUID
                whoami                      показать текущий UUID
                edit-limit ССЫЛКА ЛИМИТ      изменить лимит своей ссылки
                edit-url ССЫЛКА URL         изменить адрес своей ссылки
                delete ССЫЛКА               удалить свою ссылку
                notifications               показать новые уведомления
                help                        показать команды
                exit                        выйти
                """);
    }
}
