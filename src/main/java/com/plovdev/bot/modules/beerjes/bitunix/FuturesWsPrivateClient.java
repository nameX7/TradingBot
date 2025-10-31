package com.plovdev.bot.modules.beerjes.bitunix;

import com.plovdev.bot.modules.beerjes.utils.BitUnixUtils;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FuturesWsPrivateClient {

    private final String apiKey;
    private final String apiSecret;

    private final Logger log = LoggerFactory.getLogger("Bitunix ws client");
    private OkHttpClient okHttpClient = null;

    // Атомарные флаги для управления состоянием
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    
    // Reconnection backoff state
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_DELAY = 60; // Max 60 seconds between reconnects
    private static final int INITIAL_RECONNECT_DELAY = 1; // Start with 1 second

    private ScheduledExecutorService reconnectExecutor;
    private Timer pingTimer;

    // Конструкторы с обратной совместимостью
    public FuturesWsPrivateClient(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.okHttpClient = createInfiniteTimeoutHttpClient();
    }

    public void close() {
        webSocket.close(1000, "Ok");
    }

    /**
     * Создает OkHttpClient с бесконечными таймаутами для WebSocket
     */
    private OkHttpClient createInfiniteTimeoutHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10)) // Установить разумный таймаут подключения
                .readTimeout(Duration.ofSeconds(300))  // 5 минут — явный таймаут чтения
                .writeTimeout(Duration.ofSeconds(300)) // 5 минут — явный таймаут записи
                .callTimeout(Duration.ZERO)           // Общий таймаут можно оставить 0
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(5, 10, TimeUnit.MINUTES))
                .build();
    }

    private WebSocket webSocket = null;
    private Request wsRequest = null;
    private WebSocketListener webSocketListener = null;
    private BitunixPrivateWsResponseListener bitunixPrivateWsResponseListener = null;

    public void connect(BitunixPrivateWsResponseListener listener) {
        this.bitunixPrivateWsResponseListener = listener;

        this.webSocketListener = new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                log.info("WebSocket connection opened successfully");
                active.set(true);
                reconnecting.set(false);
                reconnectAttempts = 0; // Reset reconnect attempts on successful connection
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String content) {
                try {
                    if (bitunixPrivateWsResponseListener == null) {
                        return;
                    }
                    String opOrCh = getOpOrCh(content);
                    if (opOrCh == null) {
                        return;
                    }

                    switch (opOrCh) {
                        case WsOpCh.CONNECT -> {
                            // Логин после подключения
                            login("defaultnonce");
                            bitunixPrivateWsResponseListener.onConnectSuccess(content);
                        }
                        case WsOpCh.PING -> {
                            sendPong(content);
                            bitunixPrivateWsResponseListener.onPong(content);
                        }
                        case WsOpCh.ORDER -> bitunixPrivateWsResponseListener.onOrderChange(content);
                        case WsOpCh.LOGIN -> {
                            log.info("Login success: {}", content);
                            bitunixPrivateWsResponseListener.afterLogin(content);
                        }
                        case WsOpCh.BALANCE -> bitunixPrivateWsResponseListener.onBalanceChange(content);
                        case WsOpCh.POSITION -> bitunixPrivateWsResponseListener.onPositionChange(content);
                        case WsOpCh.TPSL -> bitunixPrivateWsResponseListener.onTpslChange(content);
                        default -> log.warn("Unknown channel: {} message: {}", opOrCh, content);
                    }
                } catch (Exception e) {
                    log.warn("Error processing WebSocket message: {}", content, e);
                }
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                log.info("WebSocket closed - code: {}, reason: {}", code, reason);
                active.set(false);
                if (code != 1000) {
                    log.info("Abnormal closure, scheduling reconnect with backoff");
                    scheduleReconnect();
                } else {
                    log.info("Normal closure initiated by client");
                    reconnectAttempts = 0; // Reset attempts on normal close
                }
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                log.warn("WebSocket connection failure: {}", t.getMessage());
                active.set(false);
                scheduleReconnect();
            }
        };

        // Initialize ping timer if not already created
        if (pingTimer == null) {
            pingTimer = new Timer("BitunixWS-Ping", true);
            pingTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (isActive()) {
                        sendPing();
                    }
                }
            }, 1000, 10000);
        }

        // Создаем запрос для WebSocket соединения
        Request.Builder request = new Request.Builder().url("wss://fapi.bitunix.com/private/");
        wsRequest = request.build();

        // Устанавливаем WebSocket соединение
        this.webSocket = okHttpClient.newWebSocket(wsRequest, webSocketListener);
    }

    /**
     * Запускает планировщик пингов для поддержания соединения
     */
    public void sendPing() {
        if (active.get() && webSocket != null) {
            try {
                String pingMessage = String.format("""
                            {
                               "op": "ping",
                               "ping": %s
                            }
                            """, Instant.now().getEpochSecond());

                webSocket.send(pingMessage);
            } catch (Exception e) {
                log.error("Failed to send ping message", e);
                active.set(false);
                scheduleReconnect();
            }
        } else {
            log.warn("Not active, or websocket is null. Active? - {}, ws == null? - {}", active.get(), webSocket == null);
        }
    }

    public void subscribe(String symbol, String ch) {
        String sub = String.format("""
                {
                    "op":"subscribe",
                    "args":[
                        {
                            "symbol": "%s",
                            "ch": "%s"
                        }
                    ]
                }
                """, symbol, ch);
        webSocket.send(sub);

        log.info("Subscribed");
    }

    /**
     * Планирует переподключение с задержкой и экспоненциальным откатом
     */
    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            log.info("Reconnection already in progress");
            return; // Уже в процессе реконнекта
        }

        // Calculate delay with exponential backoff
        int delay = Math.min(INITIAL_RECONNECT_DELAY * (int) Math.pow(2, reconnectAttempts), MAX_RECONNECT_DELAY);
        reconnectAttempts++;
        
        log.info("Scheduling reconnection attempt {} in {} seconds...", reconnectAttempts, delay);

        // Останавливаем предыдущий планировщик реконнекта
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }

        reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
        reconnectExecutor.schedule(() -> {
            try {
                log.info("Attempting to reconnect (attempt {})...", reconnectAttempts);
                reconnecting.set(false);
                reconnect();
            } catch (Exception e) {
                log.error("Reconnection attempt {} failed: {}", reconnectAttempts, e.getMessage());
                reconnecting.set(false);
                
                // Schedule next attempt if we haven't exceeded reasonable limits
                if (reconnectAttempts < 10) {
                    scheduleReconnect();
                } else {
                    log.error("Max reconnection attempts reached. Manual intervention may be required.");
                    reconnectAttempts = 0; // Reset for future attempts
                }
            }
        }, delay, TimeUnit.SECONDS);
    }

    /**
     * Выполняет переподключение к WebSocket
     */
    public void reconnect() {
        log.info("Performing WebSocket reconnection...");

        // Останавливаем текущее соединение
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Reconnecting");
            } catch (Exception e) {
                log.error("Error while closing previous WebSocket connection", e);
            }
        }

        active.set(false);

        // Создаем новое соединение
        try {
            this.webSocket = okHttpClient.newWebSocket(wsRequest, webSocketListener);
            log.info("New WebSocket connection initiated");
        } catch (Exception e) {
            log.error("Failed to create new WebSocket connection", e);
            scheduleReconnect();
        }
    }

    /**
     * Полностью отключает WebSocket соединение
     */
    public void disconnect() {
        log.info("Disconnecting WebSocket...");
        active.set(false);
        reconnecting.set(false);
        reconnectAttempts = 0; // Reset reconnect attempts

        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
            reconnectExecutor = null;
        }
        
        if (pingTimer != null) {
            pingTimer.cancel();
            pingTimer = null;
        }

        if (webSocket != null) {
            try {
                webSocket.close(1000, "Normal closure");
            } catch (Exception e) {
                log.error("Error while closing WebSocket", e);
            }
            webSocket = null;
        }
    }

    /**
     * Отправляет PONG в ответ на PING от сервера Bitunix.
     * Формат PONG обычно должен содержать поле "ping" из пришедшего PING-сообщения.
     */
    public void sendPong(String pingContent) {
        if (!active.get() || webSocket == null) {
            log.warn("Cannot send pong - WebSocket is not active");
            return;
        }

        try {
            JSONObject pingJson = new JSONObject(pingContent);
            long pingValue = pingJson.optLong("ping", Instant.now().getEpochSecond());

            String pongMessage = String.format("""
                    {
                       "op": "pong",
                       "ping": %s
                    }
                    """, pingValue);

            webSocket.send(pongMessage);
        } catch (Exception e) {
            log.error("Failed to send pong message", e);
        }
    }


    /**
     * Возвращает текущее состояние соединения
     */
    public boolean isActive() {
        return active.get();
    }

    /**
     * Выполняет логин в WebSocket
     */
    private void login(String nonce) {
        if (!active.get() || webSocket == null) {
            log.warn("Cannot login - WebSocket is not active");
            return;
        }

        long now = System.currentTimeMillis();
        String timestamp = String.valueOf(now);
        String sign = BitUnixUtils.generateSign(nonce, timestamp, apiKey, new TreeMap<>(), "", apiSecret);


        String json = String.format("""
                {
                    "op":"login",
                    "args":[
                        {
                            "apiKey": "%s",
                            "timestamp": %s,
                            "nonce": "%s",
                            "sign": "%s"
                        }
                    ]
                }
                """, apiKey, timestamp, nonce, sign);

        try {
            webSocket.send(json);
        } catch (Exception e) {
            log.error("Failed to send login request", e);
        }
    }

    /**
     * Извлекает операцию или канал из сообщения WebSocket
     */
    private String getOpOrCh(String content) {
        try {
            if (content == null) {
                return null;
            }
            JSONObject op = new JSONObject(content);

            return op.optString("op", op.optString("ch", null));
        } catch (Exception e) {
            log.error("Error parsing WebSocket message: {}", content, e);
        }
        return null;
    }
}