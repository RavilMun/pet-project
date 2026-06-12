package ru.ravil.petproject.telegram;

public record TelegramIntent(
        TelegramIntentType type,
        String query
) {

    public static TelegramIntent capture() {
        return new TelegramIntent(TelegramIntentType.CAPTURE, null);
    }

    public static TelegramIntent search(String query) {
        return new TelegramIntent(TelegramIntentType.SEARCH, query);
    }

    public static TelegramIntent recent() {
        return new TelegramIntent(TelegramIntentType.RECENT, null);
    }

    public static TelegramIntent today() {
        return new TelegramIntent(TelegramIntentType.TODAY, null);
    }

    public static TelegramIntent help() {
        return new TelegramIntent(TelegramIntentType.HELP, null);
    }
}
