package mail.sender.util;

import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.HttpStatus;

import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON;

public class WebClientTest {

    private final MockWebServer server;
    private final AtomicInteger preparedResponses;

    @SneakyThrows
    protected WebClientTest() {
        server = new MockWebServer();
        preparedResponses = new AtomicInteger(0);
        server.start();
    }

    @SneakyThrows
    @AfterEach
    protected final void resetServer() {
        server.shutdown();
    }

    protected final URL getServerUrl() {
        return server.url("/").url();
    }

    protected final void prepareResponse(MockResponse response) {
        server.enqueue(response);
        preparedResponses.incrementAndGet();
    }

    @SneakyThrows
    protected final RecordedRequest awaitRequest() {
        return server.takeRequest();
    }

    protected static MockResponse createResponse(HttpStatus status) {
        return new MockResponse().setResponseCode(status.value());
    }

    protected static MockResponse createJsonResponse(HttpStatus status, String body) {
        return createResponse(status)
                .setHeader(CONTENT_TYPE, APPLICATION_JSON)
                .setBody(body);
    }
}
