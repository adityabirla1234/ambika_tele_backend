package com.ambikatelecom.rewards.reporting.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal Telegram Bot API client (sendDocument and sendMessage) on the JDK HttpClient.
 * The bot token is part of the request URL, so it is never logged and is scrubbed from every error text.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    public record Sent(long messageId) {}

    /** retryable = worth trying again (outage, timeout, rate limit). Not retryable = our setup is wrong (token, chat). */
    public static class TelegramException extends RuntimeException {
        private final boolean retryable;
        private final Integer httpStatus;
        private final Long retryAfterSeconds;

        TelegramException(String message, boolean retryable, Integer httpStatus, Long retryAfterSeconds) {
            super(message);
            this.retryable = retryable;
            this.httpStatus = httpStatus;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public boolean isRetryable() { return retryable; }
        public Integer httpStatus() { return httpStatus; }
        public Long retryAfterSeconds() { return retryAfterSeconds; }
    }

    private final ReportSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public TelegramClient(ReportSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    public Sent sendDocument(Path file, String filename, String caption) {
        byte[] pdf;
        try {
            pdf = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TelegramException("Could not read the report file", true, null, null);
        }
        String boundary = "----ambika" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, settings.chatId(), caption, filename, pdf);
        HttpRequest req = HttpRequest.newBuilder(uri("sendDocument"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        JsonNode json = execute(req);
        JsonNode id = json.path("result").path("message_id");
        if (!id.isNumber()) {
            // Telegram said ok but we cannot record the id. Treat as a failure so it is visible, not silently lost.
            throw new TelegramException("Telegram accepted the file but returned no message id", false, 200, null);
        }
        return new Sent(id.asLong());
    }

    /** Failure alert. Never throws: if Telegram is down the alert cannot be sent either, and the ERROR log remains. */
    public void sendAlert(String text) {
        if (!settings.telegramConfigured()) return;
        try {
            String form = "chat_id=" + URLEncoder.encode(settings.alertChatId(), StandardCharsets.UTF_8)
                    + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(uri("sendMessage"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            execute(req);
        } catch (RuntimeException e) {
            log.error("Could not send the failure alert to Telegram: {}", scrub(e.getMessage()));
        }
    }

    private URI uri(String method) {
        return URI.create(settings.apiBase() + "/bot" + settings.botToken() + "/" + method);
    }

    private JsonNode execute(HttpRequest req) {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException("Interrupted while contacting Telegram", false, null, null);
        } catch (IOException e) {
            // Timeouts and dropped connections: we cannot know whether Telegram got the file, so these retry.
            throw new TelegramException("Could not reach Telegram (" + e.getClass().getSimpleName() + ")", true, null, null);
        }
        int status = res.statusCode();
        JsonNode json = parse(res.body());
        if (status == 200 && json.path("ok").asBoolean(false)) return json;

        String description = scrub(json.path("description").asText(""));
        Long retryAfter = json.path("parameters").path("retry_after").isNumber()
                ? json.path("parameters").path("retry_after").asLong() : null;
        String msg = "Telegram HTTP " + status + (description.isEmpty() ? "" : ": " + description);
        if (msg.length() > 200) msg = msg.substring(0, 200);
        boolean retryable = status == 429 || status >= 500;
        throw new TelegramException(msg, retryable, status, retryAfter);
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null ? "" : body);
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    private String scrub(String s) {
        if (s == null) return "";
        String token = settings.botToken();
        return token.isEmpty() ? s : s.replace(token, "***");
    }

    private static byte[] multipart(String boundary, String chatId, String caption, String filename, byte[] pdf) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 1024);
            field(out, boundary, "chat_id", chatId);
            field(out, boundary, "caption", caption);
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"document\"; filename=\""
                    + filename + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(pdf);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the upload", e);
        }
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }
}
