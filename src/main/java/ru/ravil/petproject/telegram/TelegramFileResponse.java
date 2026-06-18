package ru.ravil.petproject.telegram;

public record TelegramFileResponse(
        boolean ok,
        TelegramFile result
) {
}
