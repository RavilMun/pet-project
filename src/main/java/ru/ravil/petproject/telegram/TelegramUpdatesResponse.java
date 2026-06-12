package ru.ravil.petproject.telegram;

import java.util.List;

public record TelegramUpdatesResponse(
        boolean ok,
        List<TelegramUpdate> result
) {
}
