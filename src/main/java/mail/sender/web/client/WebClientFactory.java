package mail.sender.web.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.URL;
import java.time.Duration;

import static io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static java.lang.Math.toIntExact;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Component
@RequiredArgsConstructor
public class WebClientFactory {

    private static final int PENDING_ACQUIRES_MAX_COUNT = -1;

    private final ObjectMapper objectMapper;

    public WebClient createWebClient(String name, URL baseUrl, int connections, Duration timeout) {
        return WebClient.builder()
                .baseUrl(baseUrl.toString())
                .clientConnector(new ReactorClientHttpConnector(createHttpClient(name, connections, timeout)))
                .codecs(configurer -> {
                    configurer.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(objectMapper));
                    configurer.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(objectMapper));
                })
                .build();

    }

    private static HttpClient createHttpClient(String name, int maxConnections, Duration timeout) {
        return HttpClient
                .create(ConnectionProvider.builder(name)
                        .maxConnections(maxConnections)
                        .pendingAcquireMaxCount(PENDING_ACQUIRES_MAX_COUNT)
                        .build())
                .option(CONNECT_TIMEOUT_MILLIS, toIntExact(timeout.toMillis()))
                .doOnConnected(connection -> connection
                        .addHandlerLast(new WriteTimeoutHandler(timeout.toMillis(), MILLISECONDS))
                        .addHandlerLast(new ReadTimeoutHandler(timeout.toMillis(), MILLISECONDS)));
    }
}
