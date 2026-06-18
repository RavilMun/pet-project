package ru.ravil.petproject.telegram;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public class TelegramApiClient {

    private final RestClient restClient;
    private final RestClient fileClient;

    public TelegramApiClient(String token) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = RestClient.builder()
                .baseUrl("https://api.telegram.org/bot" + token)
                .requestFactory(requestFactory)
                .build();

        // File downloads use a different host path: https://api.telegram.org/file/bot<token>/<file_path>
        this.fileClient = RestClient.builder()
                .baseUrl("https://api.telegram.org/file/bot" + token)
                .requestFactory(requestFactory)
                .build();
    }

    public List<TelegramUpdate> getUpdates(long offset, int timeoutSeconds) {
        TelegramUpdatesResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", timeoutSeconds)
                        .queryParam("allowed_updates", "[\"message\"]")
                        .build())
                .retrieve()
                .body(TelegramUpdatesResponse.class);

        if (response == null || !response.ok() || response.result() == null) {
            return List.of();
        }

        return response.result();
    }

    public void sendMessage(long chatId, String text) {
        restClient.post()
                .uri("/sendMessage")
                .body(new TelegramSendMessageRequest(chatId, text))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Sets a single emoji reaction on a message (replaces any previous bot reaction). Used as
     * lightweight capture feedback: 👀 in progress → 👍 done / 👎 failed. Only emojis from Telegram's
     * allowed reaction set work (⏳/✅/❌ are not allowed reactions).
     */
    public void setMessageReaction(long chatId, long messageId, String emoji) {
        restClient.post()
                .uri("/setMessageReaction")
                .body(Map.of(
                        "chat_id", chatId,
                        "message_id", messageId,
                        "reaction", List.of(Map.of("type", "emoji", "emoji", emoji))
                ))
                .retrieve()
                .toBodilessEntity();
    }

    public void sendPhoto(long chatId, String fileId, String caption) {
        restClient.post()
                .uri("/sendPhoto")
                .body(new TelegramSendPhotoRequest(chatId, fileId, caption))
                .retrieve()
                .toBodilessEntity();
    }

    /** Resolves a {@code file_id} to a downloadable {@code file_path}; {@code null} if unavailable. */
    public TelegramFile getFile(String fileId) {
        TelegramFileResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/getFile")
                        .queryParam("file_id", fileId)
                        .build())
                .retrieve()
                .body(TelegramFileResponse.class);

        if (response == null || !response.ok() || response.result() == null) {
            return null;
        }
        return response.result();
    }

    /** Downloads raw file bytes for a {@code file_path} returned by {@link #getFile}. */
    public byte[] downloadFile(String filePath) {
        // filePath contains slashes (e.g. "photos/file_0.jpg") — pass as a literal path, not a template
        // variable, so the slashes are not percent-encoded.
        return fileClient.get()
                .uri("/" + filePath)
                .retrieve()
                .body(byte[].class);
    }
}
