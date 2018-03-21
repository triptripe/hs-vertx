/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.core.http.impl;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.vertx.core.AsyncResult;
import io.vertx.core.Closeable;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.impl.cgbystrom.FlashPolicyHandler;
import io.vertx.core.http.impl.ws.WebSocketFrameImpl;
import io.vertx.core.http.impl.ws.WebSocketFrameInternal;
import io.vertx.core.impl.ContextImpl;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.impl.AsyncResolveConnectHelper;
import io.vertx.core.net.impl.HandlerHolder;
import io.vertx.core.net.impl.HandlerManager;
import io.vertx.core.net.impl.PartialPooledByteBufAllocator;
import io.vertx.core.net.impl.SSLHelper;
import io.vertx.core.net.impl.ServerID;
import io.vertx.core.net.impl.SocketAddressImpl;
import io.vertx.core.net.impl.VertxEventLoopGroup;
import io.vertx.core.net.impl.VertxSniHandler;
import io.vertx.core.spi.metrics.HttpServerMetrics;
import io.vertx.core.spi.metrics.Metrics;
import io.vertx.core.spi.metrics.MetricsProvider;
import io.vertx.core.spi.metrics.VertxMetrics;
import io.vertx.core.streams.ReadStream;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.*;
import static io.vertx.core.spi.metrics.Metrics.METRICS_ENABLED;

/**
 * This class is thread-safe
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class HttpServerImpl implements HttpServer, Closeable, MetricsProvider {

  private static final Logger log = LoggerFactory.getLogger(HttpServerImpl.class);
  private static final String FLASH_POLICY_HANDLER_PROP_NAME = "vertx.flashPolicyHandler";
  private static final boolean USE_FLASH_POLICY_HANDLER = Boolean.getBoolean(FLASH_POLICY_HANDLER_PROP_NAME);
  private static final String DISABLE_WEBSOCKETS_PROP_NAME = "vertx.disableWebsockets";
  private static final boolean DISABLE_WEBSOCKETS = Boolean.getBoolean(DISABLE_WEBSOCKETS_PROP_NAME);
  private static final String DISABLE_H2C_PROP_NAME = "vertx.disableH2c";
  private final boolean DISABLE_HC2 = Boolean.getBoolean(DISABLE_H2C_PROP_NAME);
  private static final String[] H2C_HANDLERS_TO_REMOVE = { "idle", "flashpolicy", "deflater", "chunkwriter" };

  private final HttpServerOptions options;
  private final VertxInternal vertx;
  private final SSLHelper sslHelper;
  private final ContextImpl creatingContext;
  private final Map<Channel, ServerConnection> connectionMap = new ConcurrentHashMap<>();
  private final Map<Channel, Http2ServerConnection> connectionMap2 = new ConcurrentHashMap<>();
  private final VertxEventLoopGroup availableWorkers = new VertxEventLoopGroup();
  private final HandlerManager<HttpHandlers> httpHandlerMgr = new HandlerManager<>(availableWorkers);
  private final HttpStreamHandler<ServerWebSocket> wsStream = new HttpStreamHandler<>();
  private final HttpStreamHandler<HttpServerRequest> requestStream = new HttpStreamHandler<>();
  private Handler<HttpConnection> connectionHandler;
  private String serverOrigin;

  private ChannelGroup serverChannelGroup;
  private volatile boolean listening;
  private AsyncResolveConnectHelper bindFuture;
  private ServerID id;
  private HttpServerImpl actualServer;
  private volatile int actualPort;
  private ContextImpl listenContext;
  private HttpServerMetrics metrics;
  private boolean logEnabled;
  private Handler<Throwable> connectionExceptionHandler;

  public HttpServerImpl(VertxInternal vertx, HttpServerOptions options) {
    this.options = new HttpServerOptions(options);
    this.vertx = vertx;
    this.creatingContext = vertx.getContext();
    if (creatingContext != null) {
      if (creatingContext.isMultiThreadedWorkerContext()) {
        throw new IllegalStateException("Cannot use HttpServer in a multi-threaded worker verticle");
      }
      creatingContext.addCloseHook(this);
    }
    this.sslHelper = new SSLHelper(options, options.getKeyCertOptions(), options.getTrustOptions());
    this.logEnabled = options.getLogActivity();
    connectionExceptionHandler = t -> {log.trace("Connection failure", t);};
  }

  @Override
  public synchronized HttpServer requestHandler(Handler<HttpServerRequest> handler) {
    requestStream.handler(handler);
    return this;
  }

  @Override
  public ReadStream<HttpServerRequest> requestStream() {
    return requestStream;
  }

  @Override
  public HttpServer websocketHandler(Handler<ServerWebSocket> handler) {
    websocketStream().handler(handler);
    return this;
  }

  @Override
  public Handler<HttpServerRequest> requestHandler() {
    return requestStream.handler();
  }

  @Override
  public synchronized HttpServer connectionHandler(Handler<HttpConnection> handler) {
    if (listening) {
      throw new IllegalStateException("Please set handler before server is listening");
    }
    connectionHandler = handler;
    return this;
  }

  @Override
  public Handler<ServerWebSocket> websocketHandler() {
    return wsStream.handler();
  }

  @Override
  public ReadStream<ServerWebSocket> websocketStream() {
    return wsStream;
  }

  @Override
  public HttpServer listen() {
    return listen(options.getPort(), options.getHost(), null);
  }

  @Override
  public HttpServer listen(Handler<AsyncResult<HttpServer>> listenHandler) {
    return listen(options.getPort(), options.getHost(), listenHandler);
  }

  @Override
  public HttpServer listen(int port, String host) {
    return listen(port, host, null);
  }

  @Override
  public HttpServer listen(int port) {
    return listen(port, "0.0.0.0", null);
  }

  @Override
  public HttpServer listen(int port, Handler<AsyncResult<HttpServer>> listenHandler) {
    return listen(port, "0.0.0.0", listenHandler);
  }

  public synchronized HttpServer listen(int port, String host, Handler<AsyncResult<HttpServer>> listenHandler) {
    if (requestStream.handler() == null && wsStream.handler() == null) {
      throw new IllegalStateException("Set request or websocket handler first");
    }
    if (listening) {
      throw new IllegalStateException("Already listening");
    }
    listenContext = vertx.getOrCreateContext();
    listening = true;
    serverOrigin = (options.isSsl() ? "https" : "http") + "://" + host + ":" + port;
    List<HttpVersion> applicationProtocols = options.getAlpnVersions();
    if (listenContext.isWorkerContext()) {
      applicationProtocols =  applicationProtocols.stream().filter(v -> v != HttpVersion.HTTP_2).collect(Collectors.toList());
    }
    sslHelper.setApplicationProtocols(applicationProtocols);
    synchronized (vertx.sharedHttpServers()) {
      this.actualPort = port; // Will be updated on bind for a wildcard port
      id = new ServerID(port, host);
      HttpServerImpl shared = vertx.sharedHttpServers().get(id);
      if (shared == null || port == 0) {
        serverChannelGroup = new DefaultChannelGroup("vertx-acceptor-channels", GlobalEventExecutor.INSTANCE);
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(vertx.getAcceptorEventLoopGroup(), availableWorkers);
        bootstrap.channel(NioServerSocketChannel.class);
        applyConnectionOptions(bootstrap);
        sslHelper.validate(vertx);
        bootstrap.childHandler(new ChannelInitializer<Channel>() {
          @Override
            protected void initChannel(Channel ch) throws Exception {
              if (requestStream.isPaused() || wsStream.isPaused()) {
                ch.close();
                return;
              }
              ChannelPipeline pipeline = ch.pipeline();
              if (sslHelper.isSSL()) {
                if (options.isSni()) {
                  VertxSniHandler sniHandler = new VertxSniHandler(sslHelper, vertx);
                  pipeline.addLast(sniHandler);
                } else {
                  pipeline.addLast("ssl", new SslHandler(sslHelper.createEngine(vertx)));
                }
                postSSLConfig(pipeline);
              } else {
                if (DISABLE_HC2) {
                  configureHttp1(pipeline);
                } else {
                  pipeline.addLast(new Http1xOrHttp2Handler());
                }
              }
            }
        });

        addHandlers(this, listenContext);
        try {
          bindFuture = AsyncResolveConnectHelper.doBind(vertx, port, host, bootstrap);
          bindFuture.addListener(res -> {
            if (res.failed()) {
              vertx.sharedHttpServers().remove(id);
            } else {
              Channel serverChannel = res.result();
              HttpServerImpl.this.actualPort = ((InetSocketAddress)serverChannel.localAddress()).getPort();
              serverChannelGroup.add(serverChannel);
              VertxMetrics metrics = vertx.metricsSPI();
              this.metrics = metrics != null ? metrics.createMetrics(this, new SocketAddressImpl(port, host), options) : null;
            }
          });
        } catch (final Throwable t) {
          // Make sure we send the exception back through the handler (if any)
          if (listenHandler != null) {
            vertx.runOnContext(v -> listenHandler.handle(Future.failedFuture(t)));
          } else {
            // No handler - log so user can see failure
            log.error(t);
          }
          listening = false;
          return this;
        }
        vertx.sharedHttpServers().put(id, this);
        actualServer = this;
      } else {
        // Server already exists with that host/port - we will use that
        actualServer = shared;
        this.actualPort = shared.actualPort;
        addHandlers(actualServer, listenContext);
        VertxMetrics metrics = vertx.metricsSPI();
        this.metrics = metrics != null ? metrics.createMetrics(this, new SocketAddressImpl(port, host), options) : null;
      }
      actualServer.bindFuture.addListener(future -> {
        if (listenHandler != null) {
          final AsyncResult<HttpServer> res;
          if (future.succeeded()) {
            res = Future.succeededFuture(HttpServerImpl.this);
          } else {
            res = Future.failedFuture(future.cause());
            listening = false;
          }
          listenContext.runOnContext((v) -> listenHandler.handle(res));
        } else if (future.failed()) {
          listening  = false;
          // No handler - log so user can see failure
          log.error(future.cause());
        }
      });
    }
    return this;
  }

  // Visible for testing
  public HttpServerImpl setConnectionExceptionHandler(Handler<Throwable> connectionExceptionHandler) {
    Objects.requireNonNull(connectionExceptionHandler, "connectionExceptionHandler");
    this.connectionExceptionHandler = connectionExceptionHandler;
    return this;
  }

  private VertxHttp2ConnectionHandler<Http2ServerConnection> setHandler(HandlerHolder<HttpHandlers> holder, Http2Settings upgrade, Channel ch) {
    return new VertxHttp2ConnectionHandlerBuilder<Http2ServerConnection>(ch)
        .connectionMap(connectionMap2)
        .server(true)
        .serverUpgrade(upgrade)
        .useCompression(options.isCompressionSupported())
        .useDecompression(options.isDecompressionSupported())
        .compressionLevel(options.getCompressionLevel())
        .initialSettings(options.getInitialSettings())
        .connectionFactory(connHandler -> {
          Http2ServerConnection conn = new Http2ServerConnection(holder.context, serverOrigin, connHandler, options, holder.handler.requesthHandler, metrics);
          if (metrics != null) {
            conn.metric(metrics.connected(conn.remoteAddress(), conn.remoteName()));
          }
          if (options.getHttp2ConnectionWindowSize() > 0) {
            conn.setWindowSize(options.getHttp2ConnectionWindowSize());
          }
          return conn;
        })
        .logEnabled(logEnabled)
        .build();
  }

  private void postSSLConfig(ChannelPipeline pipeline) {
    if (options.isUseAlpn()) {
      pipeline.addLast("alpn", new ApplicationProtocolNegotiationHandler("http/1.1") {
        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
          if (protocol.equals("http/1.1")) {
            configureHttp1(pipeline);
          } else {
            handleHttp2(pipeline.channel());
          }
        }
      });
    } else {
      configureHttp1(pipeline);
    }
  }

  private void configureHttp1(ChannelPipeline pipeline) {
    if (logEnabled) {
      pipeline.addLast("logging", new LoggingHandler());
    }
    if (USE_FLASH_POLICY_HANDLER) {
      pipeline.addLast("flashpolicy", new FlashPolicyHandler());
    }
    pipeline.addLast("httpDecoder", new HttpRequestDecoder(options.getMaxInitialLineLength()
        , options.getMaxHeaderSize(), options.getMaxChunkSize(), false, options.getDecoderInitialBufferSize()));
    pipeline.addLast("httpEncoder", new VertxHttpResponseEncoder());
    if (options.isDecompressionSupported()) {
      pipeline.addLast("inflater", new HttpContentDecompressor(true));
    }
    if (options.isCompressionSupported()) {
      pipeline.addLast("deflater", new HttpChunkContentCompressor(options.getCompressionLevel()));
    }
    if (sslHelper.isSSL() || options.isCompressionSupported()) {
      // only add ChunkedWriteHandler when SSL is enabled otherwise it is not needed as FileRegion is used.
      pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());       // For large file / sendfile support
    }
    if (options.getIdleTimeout() > 0) {
      pipeline.addLast("idle", new IdleStateHandler(0, 0, options.getIdleTimeout()));
    }
    if (!DISABLE_HC2) {
      pipeline.addLast("h2c", new Http2UpgradeHandler());
    }
    HandlerHolder<HttpHandlers> holder = httpHandlerMgr.chooseHandler(pipeline.channel().eventLoop());
    ServerHandler handler;
    if (DISABLE_WEBSOCKETS) {
      // As a performance optimisation you can set a system property to disable websockets altogether which avoids
      // some casting and a header check
      handler = new ServerHandler(sslHelper, options, serverOrigin, holder, metrics);
    } else {
      handler = new ServerHandlerWithWebSockets(sslHelper, options, serverOrigin, holder, metrics);
    }
    handler.addHandler(conn -> {
      connectionMap.put(pipeline.channel(), conn);
    });
    handler.removeHandler(conn -> {
      connectionMap.remove(pipeline.channel());
    });
    pipeline.addLast("handler", handler);
  }

  public void handleHttp2(Channel ch) {
    HandlerHolder<HttpHandlers> holder = httpHandlerMgr.chooseHandler(ch.eventLoop());
    configureHttp2(ch.pipeline());
    VertxHttp2ConnectionHandler<Http2ServerConnection> handler = setHandler(holder, null, ch);
    if (holder.handler.connectionHandler != null) {
      holder.context.executeFromIO(() -> {
        holder.handler.connectionHandler.handle(handler.connection);
      });
    }
  }

  public void configureHttp2(ChannelPipeline pipeline) {
    if (options.getIdleTimeout() > 0) {
      pipeline.addLast("idle", new IdleStateHandler(0, 0, options.getIdleTimeout()));
    }
  }

  @Override
  public void close() {
    close(null);
  }

  @Override
  public synchronized void close(Handler<AsyncResult<Void>> done) {
    if (wsStream.endHandler() != null || requestStream.endHandler() != null) {
      Handler<Void> wsEndHandler = wsStream.endHandler();
      wsStream.endHandler(null);
      Handler<Void> requestEndHandler = requestStream.endHandler();
      requestStream.endHandler(null);
      Handler<AsyncResult<Void>> next = done;
      done = event -> {
          if (event.succeeded()) {
            if (wsEndHandler != null) {
              wsEndHandler.handle(event.result());
            }
            if (requestEndHandler != null) {
              requestEndHandler.handle(event.result());
            }
          }
          if (next != null) {
            next.handle(event);
          }
      };
    }

    ContextImpl context = vertx.getOrCreateContext();
    if (!listening) {
      executeCloseDone(context, done, null);
      return;
    }
    listening = false;

    synchronized (vertx.sharedHttpServers()) {

      if (actualServer != null) {

        actualServer.httpHandlerMgr.removeHandler(new HttpHandlers(requestStream.handler(), wsStream.handler(), connectionHandler), listenContext);

        if (actualServer.httpHandlerMgr.hasHandlers()) {
          // The actual server still has handlers so we don't actually close it
          if (done != null) {
            executeCloseDone(context, done, null);
          }
        } else {
          // No Handlers left so close the actual server
          // The done handler needs to be executed on the context that calls close, NOT the context
          // of the actual server
          actualServer.actualClose(context, done);
        }
      }
    }
    if (creatingContext != null) {
      creatingContext.removeCloseHook(this);
    }
  }

  @Override
  public Metrics getMetrics() {
    return metrics;
  }

  @Override
  public boolean isMetricsEnabled() {
    return metrics != null;
  }

  public SSLHelper getSslHelper() {
    return sslHelper;
  }

  private void applyConnectionOptions(ServerBootstrap bootstrap) {
    bootstrap.childOption(ChannelOption.TCP_NODELAY, options.isTcpNoDelay());
    if (options.getSendBufferSize() != -1) {
      bootstrap.childOption(ChannelOption.SO_SNDBUF, options.getSendBufferSize());
    }
    if (options.getReceiveBufferSize() != -1) {
      bootstrap.childOption(ChannelOption.SO_RCVBUF, options.getReceiveBufferSize());
      bootstrap.childOption(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(options.getReceiveBufferSize()));
    }

    if (options.getSoLinger() != -1) {
      bootstrap.option(ChannelOption.SO_LINGER, options.getSoLinger());
    }
    if (options.getTrafficClass() != -1) {
      bootstrap.childOption(ChannelOption.IP_TOS, options.getTrafficClass());
    }
    bootstrap.childOption(ChannelOption.ALLOCATOR, PartialPooledByteBufAllocator.INSTANCE);

    bootstrap.childOption(ChannelOption.SO_KEEPALIVE, options.isTcpKeepAlive());

    bootstrap.option(ChannelOption.SO_REUSEADDR, options.isReuseAddress());
    if (options.getAcceptBacklog() != -1) {
      bootstrap.option(ChannelOption.SO_BACKLOG, options.getAcceptBacklog());
    }
  }


  private void addHandlers(HttpServerImpl server, ContextImpl context) {
    server.httpHandlerMgr.addHandler(new HttpHandlers(requestStream.handler(), wsStream.handler(), connectionHandler), context);
  }

  private void actualClose(final ContextImpl closeContext, final Handler<AsyncResult<Void>> done) {
    if (id != null) {
      vertx.sharedHttpServers().remove(id);
    }

    ContextImpl currCon = vertx.getContext();

    for (ServerConnection conn : connectionMap.values()) {
      conn.close();
    }
    for (Http2ServerConnection conn : connectionMap2.values()) {
      conn.close();
    }

    // Sanity check
    if (vertx.getContext() != currCon) {
      throw new IllegalStateException("Context was changed");
    }

    if (metrics != null) {
      metrics.close();
    }

    ChannelGroupFuture fut = serverChannelGroup.close();
    fut.addListener(cgf -> executeCloseDone(closeContext, done, fut.cause()));
  }

  @Override
  public int actualPort() {
    return actualPort;
  }

  private void executeCloseDone(final ContextImpl closeContext, final Handler<AsyncResult<Void>> done, final Exception e) {
    if (done != null) {
      Future<Void> fut = e != null ? Future.failedFuture(e) : Future.succeededFuture();
      closeContext.runOnContext((v) -> done.handle(fut));
    }
  }

  public class ServerHandlerWithWebSockets extends ServerHandler {

    private boolean closeFrameSent;
    private FullHttpRequest wsRequest;
    private HttpResponseStatus handshakeErrorStatus;
    private String handshakeErrorMsg;

    public ServerHandlerWithWebSockets(SSLHelper sslHelper, HttpServerOptions options, String serverOrigin, HandlerHolder<HttpHandlers> holder, HttpServerMetrics metrics) {
      super(sslHelper, options, serverOrigin, holder, metrics);
    }

    @Override
    protected void handleMessage(ServerConnection conn, ContextImpl context, ChannelHandlerContext chctx, Object msg) throws Exception {
      Channel ch = chctx.channel();
      if (msg instanceof HttpRequest) {
        final HttpRequest request = (HttpRequest) msg;

        if (log.isTraceEnabled()) log.trace("Server received request: " + request.getUri());

        if (request.headers().contains(io.vertx.core.http.HttpHeaders.UPGRADE, io.vertx.core.http.HttpHeaders.WEBSOCKET, true)) {

          // As a fun part, Firefox 6.0.2 supports Websockets protocol '7'. But,
          // it doesn't send a normal 'Connection: Upgrade' header. Instead it
          // sends: 'Connection: keep-alive, Upgrade'. Brilliant.
          String connectionHeader = request.headers().get(io.vertx.core.http.HttpHeaders.CONNECTION);
          if (connectionHeader == null || !connectionHeader.toLowerCase().contains("upgrade")) {
            handshakeErrorStatus = BAD_REQUEST;
            handshakeErrorMsg = "\"Connection\" must be \"Upgrade\".";
            return;
          }

          if (request.getMethod() != HttpMethod.GET) {
            handshakeErrorStatus = METHOD_NOT_ALLOWED;
            sendError(null, METHOD_NOT_ALLOWED, ch);
            return;
          }

          if (wsRequest == null) {
            if (request instanceof FullHttpRequest) {
              handshake(conn, (FullHttpRequest) request, ch, chctx);
            } else {
              wsRequest = new DefaultFullHttpRequest(request.getProtocolVersion(), request.getMethod(), request.getUri());
              wsRequest.headers().set(request.headers());
            }
          }
        } else {
          //HTTP request
          conn.handleMessage(msg);
        }
      } else if (msg instanceof WebSocketFrameInternal) {
        //Websocket frame
        WebSocketFrameInternal wsFrame = (WebSocketFrameInternal) msg;
        switch (wsFrame.type()) {
          case BINARY:
          case CONTINUATION:
          case TEXT:
            conn.handleMessage(msg);
            break;
          case PING:
            // Echo back the content of the PING frame as PONG frame as specified in RFC 6455 Section 5.5.2
            ch.writeAndFlush(new WebSocketFrameImpl(FrameType.PONG, wsFrame.getBinaryData()));
            break;
          case PONG:
            // Just ignore it
            break;
          case CLOSE:
            if (!closeFrameSent) {
              // Echo back close frame and close the connection once it was written.
              // This is specified in the WebSockets RFC 6455 Section  5.4.1
              ch.writeAndFlush(wsFrame).addListener(ChannelFutureListener.CLOSE);
              closeFrameSent = true;
            }
            break;
          default:
            throw new IllegalStateException("Invalid type: " + wsFrame.type());
        }
      } else if (msg instanceof HttpContent) {
        if (wsRequest != null) {
          wsRequest.content().writeBytes(((HttpContent) msg).content());
          if (msg instanceof LastHttpContent) {
            FullHttpRequest req = wsRequest;
            wsRequest = null;
            handshake(conn, req, ch, chctx);
            return;
          }
        } else if (handshakeErrorStatus != null) {
          if (msg instanceof LastHttpContent) {
            sendError(handshakeErrorMsg, handshakeErrorStatus, ch);
            handshakeErrorMsg = null;
            handshakeErrorMsg = null;
          }
          return;
        }
        conn.handleMessage(msg);
      } else {
        throw new IllegalStateException("Invalid message " + msg);
      }
    }

    protected void handshake(ServerConnection conn, FullHttpRequest request, Channel ch, ChannelHandlerContext ctx) throws Exception {

      WebSocketServerHandshaker shake = createHandshaker(conn, ch, request);
      if (shake == null) {
        return;
      }
      HandlerHolder<HttpHandlers> wsHandler = httpHandlerMgr.chooseHandler(ch.eventLoop());

      if (wsHandler == null || wsHandler.handler.wsHandler == null) {
        conn.handleMessage(request);
      } else {

        wsHandler.context.executeFromIO(() -> {
          URI theURI;
          try {
            theURI = new URI(request.getUri());
          } catch (URISyntaxException e2) {
            throw new IllegalArgumentException("Invalid uri " + request.getUri()); //Should never happen
          }

          if (metrics != null) {
            conn.metric(metrics.connected(conn.remoteAddress(), conn.remoteName()));
          }
          conn.wsHandler(shake, wsHandler.handler.wsHandler);

          Runnable connectRunnable = () -> {
            try {
              shake.handshake(ch, request);
            } catch (WebSocketHandshakeException e) {
              conn.handleException(e);
            } catch (Exception e) {
              log.error("Failed to generate shake response", e);
            }
          };

          ServerWebSocketImpl ws = new ServerWebSocketImpl(vertx, theURI.toString(), theURI.getPath(),
              theURI.getQuery(), new HeadersAdaptor(request.headers()), conn, shake.version() != WebSocketVersion.V00,
              connectRunnable, options.getMaxWebsocketFrameSize(), options().getMaxWebsocketMessageSize());
          if (METRICS_ENABLED && metrics != null) {
            ws.setMetric(metrics.connected(conn.metric(), ws));
          }
          conn.handleWebsocketConnect(ws);
          if (!ws.isRejected()) {
            ChannelHandler handler = ctx.pipeline().get(HttpChunkContentCompressor.class);
            if (handler != null) {
              // remove compressor as its not needed anymore once connection was upgraded to websockets
              ctx.pipeline().remove(handler);
            }
            ws.connectNow();
          } else {
            ch.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, BAD_GATEWAY));
          }
        });
      }
    }
  }

  static void sendError(CharSequence err, HttpResponseStatus status, Channel ch) {
    FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, status);
    if (status.code() == METHOD_NOT_ALLOWED.code()) {
      // SockJS requires this
      resp.headers().set(io.vertx.core.http.HttpHeaders.ALLOW, io.vertx.core.http.HttpHeaders.GET);
    }
    if (err != null) {
      resp.content().writeBytes(err.toString().getBytes(CharsetUtil.UTF_8));
      HttpHeaders.setContentLength(resp, err.length());
    } else {
      HttpHeaders.setContentLength(resp, 0);
    }

    ch.writeAndFlush(resp);
  }

  static String getWebSocketLocation(ChannelPipeline pipeline, HttpRequest req) throws Exception {
    String prefix;
    if (pipeline.get(SslHandler.class) == null) {
      prefix = "ws://";
    } else {
      prefix = "wss://";
    }
    URI uri = new URI(req.getUri());
    String path = uri.getRawPath();
    String loc =  prefix + HttpHeaders.getHost(req) + path;
    String query = uri.getRawQuery();
    if (query != null) {
      loc += "?" + query;
    }
    return loc;
  }

  @Override
  protected void finalize() throws Throwable {
    // Make sure this gets cleaned up if there are no more references to it
    // so as not to leave connections and resources dangling until the system is shutdown
    // which could make the JVM run out of file handles.
    close();
    super.finalize();
  }

  HttpServerOptions options() {
    return options;
  }

  /*
    Needs to be protected using the HttpServerImpl monitor as that protects the listening variable
    In practice synchronized overhead should be close to zero assuming most access is from the same thread due
    to biased locks
  */
  private class HttpStreamHandler<C extends ReadStream<Buffer>> implements ReadStream<C> {

    private Handler<C> handler;
    private boolean paused;
    private Handler<Void> endHandler;

    Handler<C> handler() {
      synchronized (HttpServerImpl.this) {
        return handler;
      }
    }

    boolean isPaused() {
      synchronized (HttpServerImpl.this) {
        return paused;
      }
    }

    Handler<Void> endHandler() {
      synchronized (HttpServerImpl.this) {
        return endHandler;
      }
    }

    @Override
    public ReadStream handler(Handler<C> handler) {
      synchronized (HttpServerImpl.this) {
        if (listening) {
          throw new IllegalStateException("Please set handler before server is listening");
        }
        this.handler = handler;
        return this;
      }
    }

    @Override
    public ReadStream pause() {
      synchronized (HttpServerImpl.this) {
        if (!paused) {
          paused = true;
        }
        return this;
      }
    }

    @Override
    public ReadStream resume() {
      synchronized (HttpServerImpl.this) {
        if (paused) {
          paused = false;
        }
        return this;
      }
    }

    @Override
    public ReadStream endHandler(Handler<Void> endHandler) {
      synchronized (HttpServerImpl.this) {
        this.endHandler = endHandler;
        return this;
      }
    }

    @Override
    public ReadStream exceptionHandler(Handler<Throwable> handler) {
      // Should we use it in the server close exception handler ?
      return this;
    }
  }

  private class Http2UpgradeHandler extends ChannelInboundHandlerAdapter {

    private Http2Settings settings;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      if (msg instanceof HttpRequest) {
        HttpRequest request = (HttpRequest) msg;
        if (request.headers().contains(io.vertx.core.http.HttpHeaders.UPGRADE, Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, true)) {
          String connection = request.headers().get(io.vertx.core.http.HttpHeaders.CONNECTION);
          int found = 0;
          if (connection != null && connection.length() > 0) {
            StringBuilder buff = new StringBuilder();
            int pos = 0;
            int len = connection.length();
            while (pos < len) {
              char c = connection.charAt(pos++);
              if (c != ' ' && c != ',') {
                buff.append(Character.toLowerCase(c));
              }
              if (c == ',' || pos == len) {
                if (buff.indexOf("upgrade") == 0 && buff.length() == 7) {
                  found |= 1;
                } else if (buff.indexOf("http2-settings") == 0 && buff.length() == 14) {
                  found |= 2;
                }
                buff.setLength(0);
              }
            }
          }
          if (found == 3) {
            String settingsHeader = request.headers().get(Http2CodecUtil.HTTP_UPGRADE_SETTINGS_HEADER);
            if (settingsHeader != null) {
              settings = HttpUtils.decodeSettings(settingsHeader);
            }
          }
        } else {
          ctx.fireChannelRead(msg);
          ctx.pipeline().remove(this);
        }
      } else if (msg instanceof LastHttpContent) {
        if (settings != null) {
          HandlerHolder<HttpHandlers> reqHandler = httpHandlerMgr.chooseHandler(ctx.channel().eventLoop());
          if (reqHandler.context.isEventLoopContext()) {
            ChannelPipeline pipeline = ctx.pipeline();
            DefaultFullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, SWITCHING_PROTOCOLS, Unpooled.EMPTY_BUFFER, false);
            res.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE);
            res.headers().add(HttpHeaderNames.UPGRADE, Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME);
            res.headers().add(HttpHeaderNames.CONTENT_LENGTH, HttpHeaderValues.ZERO);
            ctx.writeAndFlush(res).addListener(v -> {
              // Clean more pipeline ?
              pipeline.remove("handler");
              pipeline.remove("httpDecoder");
              pipeline.remove("httpEncoder");
            });
            for (String name : H2C_HANDLERS_TO_REMOVE) {
              if (pipeline.get(name) != null) {
                pipeline.remove(name);
              }
            }
            configureHttp2(pipeline);
            setHandler(reqHandler, settings, ctx.channel());
            ctx.pipeline().remove(this);
            return;
          }
        }
        DefaultFullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST, Unpooled.EMPTY_BUFFER, false);
        res.headers().set(HttpHeaderNames.CONNECTION, "close");
        ctx.writeAndFlush(res);
      }
    }
  }

  /**
   * Handler that detects whether the HTTP/2 connection preface or just process the request
   * with the HTTP 1.x pipeline to support H2C with prior knowledge, i.e a client that connects
   * and uses HTTP/2 in clear text directly without an HTTP upgrade.
   */
  private class Http1xOrHttp2Handler extends Http1xOrH2CHandler {

    @Override
    protected void configure(ChannelHandlerContext ctx, boolean h2c) {
      if (h2c) {
        handleHttp2(ctx.channel());
      } else {
        configureHttp1(ctx.pipeline());
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      super.exceptionCaught(ctx, cause);
      HandlerHolder<HttpHandlers> reqHandler = httpHandlerMgr.chooseHandler(ctx.channel().eventLoop());
      reqHandler.context.executeFromIO(() -> HttpServerImpl.this.connectionExceptionHandler.handle(cause));
  }
}
}