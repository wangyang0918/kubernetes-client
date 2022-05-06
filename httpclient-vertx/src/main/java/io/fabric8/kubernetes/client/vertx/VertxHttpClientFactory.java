package io.fabric8.kubernetes.client.vertx;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.http.Interceptor;
import io.fabric8.kubernetes.client.http.TlsVersion;
import io.fabric8.kubernetes.client.http.WebSocket;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import io.vertx.ext.web.client.impl.WebClientBase;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class VertxHttpClientFactory implements io.fabric8.kubernetes.client.http.HttpClient.Factory {

  private Vertx vertx;

  public VertxHttpClientFactory() {
    this.vertx = Vertx.vertx();
  }

  @Override
  public io.fabric8.kubernetes.client.http.HttpClient createHttpClient(Config config) {
    io.fabric8.kubernetes.client.http.HttpClient.Builder builder = newBuilder();
    return builder.build();
  }

  private final class VertxHttpClientBuilder implements io.fabric8.kubernetes.client.http.HttpClient.Builder {
    final WebClientOptions options;
    final LinkedHashMap<String, Interceptor> interceptors = new LinkedHashMap<>();
    Long readTimeout;

    private VertxHttpClientBuilder(WebClientOptions options) {
      this.options = options;
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient build() {
      return new VertxHttpClient(this);
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder readTimeout(long l, TimeUnit timeUnit) {
      this.readTimeout = TimeUnit.MILLISECONDS.convert(l, timeUnit);
      // TODO: should be used for regular http as well
      throw new UnsupportedOperationException();
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder connectTimeout(long l, TimeUnit timeUnit) {
      options.setConnectTimeout((int) TimeUnit.MILLISECONDS.convert(l, timeUnit));
      return this;
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder forStreaming() {
      // TODO: confirm not needed
      return this;
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder writeTimeout(long l, TimeUnit timeUnit) {
      // TODO: confirm not needed
      return this;
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder addOrReplaceInterceptor(String name, Interceptor interceptor) {
      if (interceptor == null) {
        interceptors.remove(name);
      } else {
        interceptors.put(name, interceptor);
      }
      return this;
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder authenticatorNone() {
      // TODO: confirm not needed
      return this;
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder sslContext(KeyManager[] keyManagers,
        TrustManager[] trustManagers) {
      // TODO: how should this be applied? Even if they are both empty, you should still use ssl
      /*
       * if (trustManagers.length > 0) {
       * options.setTrustOptions(TrustOptions.wrap(trustManagers[0]));
       * }
       * if (keyManagers.length > 0) {
       * options.setKeyCertOptions(KeyCertOptions.wrap((X509KeyManager) keyManagers[0]));
       * }
       */
      throw new UnsupportedOperationException();
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder followAllRedirects() {
      // TODO: confirm not needed
      return this;
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder proxyAddress(InetSocketAddress inetSocketAddress) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder proxyAuthorization(String s) {
      throw new UnsupportedOperationException();
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder tlsVersions(TlsVersion[] tlsVersions) {
      if (tlsVersions != null && tlsVersions.length > 0) {
        Stream.of(tlsVersions).map(TlsVersion::javaName).forEach(tls -> options.addEnabledCipherSuite(tls));
      }
      return this;
    }

    @Override
    public io.fabric8.kubernetes.client.http.HttpClient.Builder preferHttp11() {
      this.options.setProtocolVersion(HttpVersion.HTTP_1_1);
      return this;
    }
  }

  private class VertxHttpClient implements io.fabric8.kubernetes.client.http.HttpClient {

    private WebClientBase webClient;
    private HttpClient client;
    private VertxHttpClientBuilder builder;

    private VertxHttpClient(VertxHttpClientBuilder vertxHttpClientBuilder) {
      this.client = vertx.createHttpClient(vertxHttpClientBuilder.options);
      this.webClient = new WebClientBase(this.client, vertxHttpClientBuilder.options);
      // TODO: move per request for derived builders
      for (Interceptor i : vertxHttpClientBuilder.interceptors.values()) {
        Handler<HttpContext<?>> interceptor = event -> {
          if (event.phase() == ClientPhase.PREPARE_REQUEST) {
            // TODO: modify the request
            i.before(null, null);
          } else if (event.phase() == ClientPhase.FAILURE) {
            // TODO: pass in the state
            i.afterFailure(null, null).whenComplete((resubmit, t) -> {
              if (resubmit) {
                // TODO: submit the modified request
              }
            });
          }
        };
        this.webClient.addInterceptor(interceptor);
      }
      this.builder = vertxHttpClientBuilder;
    }

    @Override
    public void close() {
      client.close();
    }

    @Override
    public DerivedClientBuilder newBuilder() {
      throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<HttpResponse<AsyncBody>> consumeBytes(HttpRequest request,
        BodyConsumer<List<ByteBuffer>> consumer) {
      VertxHttpRequest vertxHttpRequest = (VertxHttpRequest) request;
      return vertxHttpRequest.consumeBytes(client, consumer);
    }

    @Override
    public CompletableFuture<HttpResponse<AsyncBody>> consumeLines(HttpRequest request, BodyConsumer<String> consumer) {
      VertxHttpRequest vertxHttpRequest = (VertxHttpRequest) request;
      return vertxHttpRequest.consumeLines(client, consumer);
    }

    @Override
    public Factory getFactory() {
      return VertxHttpClientFactory.this;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest httpRequest, Class<T> clazz) {
      VertxHttpRequest vertxHttpRequest = (VertxHttpRequest) httpRequest;
      return vertxHttpRequest.sendAsync(client, clazz);
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
      return new WebSocket.Builder() {
        WebSocketConnectOptions options = new WebSocketConnectOptions();

        @Override
        public CompletableFuture<io.fabric8.kubernetes.client.http.WebSocket> buildAsync(WebSocket.Listener listener) {
          // TODO: the interceptors need applied here too
          
          if (builder.readTimeout != null) {
            options.setTimeout(builder.readTimeout);
          }
          Future<io.fabric8.kubernetes.client.http.WebSocket> map = client
              .webSocket(options)
              .map(ws -> {
                VertxWebSocket ret = new VertxWebSocket(ws, listener);
                ret.init();
                return ret;
              });
          return map.toCompletionStage().toCompletableFuture();
        }

        @Override
        public WebSocket.Builder subprotocol(String protocol) {
          options.setSubProtocols(Collections.singletonList(protocol));
          return this;
        }

        @Override
        public WebSocket.Builder header(String name, String value) {
          options.addHeader(name, value);
          return this;
        }

        @Override
        public WebSocket.Builder setHeader(String k, String v) {
          options.putHeader(k, v);
          return this;
        }

        @Override
        public WebSocket.Builder uri(URI uri) {
          options.setAbsoluteURI(uri.toString());
          return this;
        }
      };
    }

    @Override
    public HttpRequest.Builder newHttpRequestBuilder() {
      return new HttpRequest.Builder() {

        private URI uri;
        private RequestOptions options = new RequestOptions();
        private Buffer body;

        @Override
        public HttpRequest build() {
          return new VertxHttpRequest(uri, new RequestOptions(options).setAbsoluteURI(uri.toString()), body);
        }

        @Override
        public HttpRequest.Builder uri(String uri) {
          return uri(URI.create(uri));
        }

        @Override
        public HttpRequest.Builder url(URL url) {
          return uri(url.toString());
        }

        @Override
        public HttpRequest.Builder uri(URI uri) {
          this.uri = uri;
          return this;
        }

        @Override
        public HttpRequest.Builder post(String contentType, byte[] bytes) {
          options.setMethod(HttpMethod.POST);
          options.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
          body = Buffer.buffer(bytes);
          return this;
        }

        @Override
        public HttpRequest.Builder post(String contentType, InputStream stream, long length) {
          // The client calling logic supports two calls here, the user passing in an arbitrary inputstream
          // or a file - we could split off the file handling
          
          // TODO the inputstream seems problematic - seems like it needs converted into a ReadStream

          options.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(length));
          throw new UnsupportedOperationException();
        }

        @Override
        public HttpRequest.Builder method(String method, String contentType, String s) {
          options.setMethod(HttpMethod.valueOf(method));
          options.putHeader(HttpHeaders.CONTENT_TYPE, contentType);
          body = Buffer.buffer(s);
          return this;
        }

        @Override
        public HttpRequest.Builder header(String k, String v) {
          options.addHeader(k, v);
          return this;
        }

        @Override
        public HttpRequest.Builder setHeader(String k, String v) {
          options.putHeader(k, v);
          return this;
        }

        @Override
        public HttpRequest.Builder expectContinue() {
          // TODO: determine if this is enforced by the client
          // seems like a continue handler is needed
          options.putHeader("Expect", "100-continue");
          return this;
        }
      };
    }
  }

  @Override
  public io.fabric8.kubernetes.client.http.HttpClient.Builder newBuilder() {
    WebClientOptions options = new WebClientOptions();
    return new VertxHttpClientBuilder(options);
  }

}