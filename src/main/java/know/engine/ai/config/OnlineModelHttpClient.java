package know.engine.ai.config;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import know.engine.chat.service.OnlineChatWork;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Shared JSON transport for model calls. Streaming reads run on the caller's bounded worker. */
@Component
public class OnlineModelHttpClient implements DisposableBean {
    private final CloseableHttpClient client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setMaxConnTotal(32)
                    .setMaxConnPerRoute(24)
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(2))
                            .setSocketTimeout(Timeout.ofSeconds(10))
                            .build())
                    .build())
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build();

    public RestClient.Builder restClient(Duration readTimeout) {
        return restClient(Duration.ofSeconds(2), readTimeout);
    }

    private RestClient.Builder restClient(Duration connectTimeout, Duration readTimeout) {
        var factory = new HttpComponentsClientHttpRequestFactory(client) {
            @Override
            protected ClassicHttpRequest createHttpUriRequest(HttpMethod method, URI uri) {
                var request = (HttpUriRequestBase) super.createHttpUriRequest(method, uri);
                OnlineChatWork.onTransportCancel(request::cancel);
                return request;
            }
        };
        factory.setConnectionRequestTimeout(Duration.ofMillis(300));
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }

    public HttpClientBuilder builder() {
        return new HttpClientBuilder() {
            private Duration connectTimeout = Duration.ofSeconds(2);
            private Duration readTimeout = Duration.ofSeconds(10);

            @Override public Duration connectTimeout() { return connectTimeout; }
            @Override public Duration readTimeout() { return readTimeout; }
            @Override public HttpClientBuilder connectTimeout(Duration timeout) {
                connectTimeout = timeout;
                return this;
            }
            @Override public HttpClientBuilder readTimeout(Duration timeout) {
                readTimeout = timeout;
                return this;
            }
            @Override public HttpClient build() { return new JsonClient(restClient(connectTimeout, readTimeout).build()); }
        };
    }

    @Override
    public void destroy() throws IOException {
        client.close();
    }

    private record JsonClient(RestClient rest) implements HttpClient {
        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            try {
                var response = toRequest(request).retrieve().toEntity(String.class);
                return SuccessfulHttpResponse.builder().statusCode(response.getStatusCode().value())
                        .headers(response.getHeaders()).body(response.getBody()).build();
            } catch (RestClientResponseException error) {
                throw new HttpException(error.getStatusCode().value(), "Model request failed");
            }
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            try {
                toRequest(request).exchange((ignored, response) -> {
                    int status = response.getStatusCode().value();
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new HttpException(status, "Model stream failed");
                    }
                    listener.onOpen(SuccessfulHttpResponse.builder().statusCode(status)
                            .headers(response.getHeaders()).build());
                    parser.parse(response.getBody(), listener);
                    listener.onClose();
                    return null;
                });
            } catch (Exception error) {
                listener.onError(error);
            }
        }

        private RestClient.RequestBodySpec toRequest(HttpRequest request) {
            var spec = rest.method(HttpMethod.valueOf(request.method().name())).uri(URI.create(request.url()))
                    .headers(headers -> headers.putAll(request.headers()));
            if (request.body() != null) spec.body(request.body());
            return spec;
        }
    }
}
