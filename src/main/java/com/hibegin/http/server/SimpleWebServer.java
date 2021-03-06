package com.hibegin.http.server;

import com.hibegin.common.util.EnvKit;
import com.hibegin.common.util.LoggerUtil;
import com.hibegin.http.HttpMethod;
import com.hibegin.http.server.api.ISocketServer;
import com.hibegin.http.server.config.ConfigKit;
import com.hibegin.http.server.config.RequestConfig;
import com.hibegin.http.server.config.ResponseConfig;
import com.hibegin.http.server.config.ServerConfig;
import com.hibegin.http.server.handler.*;
import com.hibegin.http.server.impl.ServerContext;
import com.hibegin.http.server.util.PathUtil;
import com.hibegin.http.server.util.ServerInfo;
import com.hibegin.http.server.web.MethodInterceptor;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SimpleWebServer implements ISocketServer {


    private static final Logger LOGGER = LoggerUtil.getLogger(SimpleWebServer.class);
    private CheckRequestRunnable checkRequestRunnable;

    private Selector selector;
    private ServerConfig serverConfig;
    private RequestConfig requestConfig;
    private ResponseConfig responseConfig;
    private ServerContext serverContext = new ServerContext();
    private File pidFile;
    private Map<Socket, HttpRequestHandlerThread> socketHttpRequestHandlerThreadMap = new ConcurrentHashMap<>();
    private HttpDecodeRunnable httpDecodeRunnable;

    public SimpleWebServer() {
        this(null, null, null);
    }

    public SimpleWebServer(ServerConfig serverConfig, RequestConfig requestConfig, ResponseConfig responseConfig) {
        if (serverConfig == null) {
            serverConfig = new ServerConfig();
            serverConfig.setDisableCookie(Boolean.valueOf(ConfigKit.get("server.disableCookie", requestConfig.isDisableCookie()).toString()));
        }
        if (serverConfig.getTimeOut() == 0 && ConfigKit.contains("server.timeout")) {
            serverConfig.setTimeOut(Integer.parseInt(ConfigKit.get("server.timeout", 60).toString()));
        }
        if (serverConfig.getPort() == 0) {
            serverConfig.setPort(ConfigKit.getServerPort());
        }
        this.serverConfig = serverConfig;
        if (requestConfig == null) {
            this.requestConfig = getDefaultRequestConfig();
        } else {
            this.requestConfig = requestConfig;
        }
        if (responseConfig == null) {
            this.responseConfig = getDefaultResponseConfig();
        } else {
            this.responseConfig = responseConfig;
        }
        serverContext.setServerConfig(serverConfig);
        Runtime rt = Runtime.getRuntime();
        rt.addShutdownHook(new Thread() {
            @Override
            public void run() {
                SimpleWebServer.this.destroy();
            }
        });
    }

    public ReadWriteSelectorHandler getReadWriteSelectorHandlerInstance(SocketChannel channel, SelectionKey key) throws IOException {
        return new PlainReadWriteSelectorHandler(channel);
    }

    @Override
    public void listener() {
        if (selector == null) {
            return;
        }
        //开始初始化一些配置
        serverContext.init();
        LOGGER.info(ServerInfo.getName() + " is run version -> " + ServerInfo.getVersion());
        if (serverContext.getServerConfig().getInterceptors().contains(MethodInterceptor.class)) {
            LOGGER.info(serverConfig.getRouter().toString());
        }
        try {
            if (pidFile == null) {
                pidFile = new File(PathUtil.getRootPath() + "/sim.pid");
            }
            EnvKit.savePid(pidFile.toString());
            pidFile.deleteOnExit();
        } catch (Throwable e) {
            LOGGER.log(Level.WARNING, "save pid error " + e.getMessage());
        }
        startExecHttpRequestThread();
        while (selector.isOpen()) {
            try {
                selector.select();
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();

                while (iterator.hasNext()) {
                    try {
                        SelectionKey key = iterator.next();
                        SocketChannel channel = null;
                        if (!key.isValid() || !key.channel().isOpen()) {
                            continue;
                        } else if (key.isAcceptable()) {
                            ServerSocketChannel server = (ServerSocketChannel) key.channel();
                            try {
                                channel = server.accept();
                                if (channel != null) {
                                    channel.configureBlocking(false);
                                    channel.register(selector, SelectionKey.OP_READ);
                                }
                            } catch (IOException e) {
                                LOGGER.log(Level.SEVERE, "accept connect error", e);
                                if (channel != null) {
                                    key.cancel();
                                    channel.close();
                                }
                            }
                        } else if (key.isReadable()) {
                            httpDecodeRunnable.addTask((SocketChannel) key.channel(), key);
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "", e);
                    } finally {
                        iterator.remove();
                    }
                }
            } catch (Throwable e) {
                LOGGER.log(Level.SEVERE, "", e);
            }
        }
    }

    /**
     * 初始化处理请求的请求
     */
    private void startExecHttpRequestThread() {
        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);
        checkRequestRunnable = new CheckRequestRunnable(serverConfig.getTimeOut(), serverContext, socketHttpRequestHandlerThreadMap);
        httpDecodeRunnable = new HttpDecodeRunnable(serverContext, this, requestConfig, responseConfig, serverConfig);
        scheduledExecutorService.scheduleAtFixedRate(checkRequestRunnable, 0, 100, TimeUnit.MILLISECONDS);
        scheduledExecutorService.scheduleAtFixedRate(httpDecodeRunnable, 0, 1, TimeUnit.MILLISECONDS);
        new Thread(ServerInfo.getName().toLowerCase() + "-http-request-exec-thread") {
            @Override
            public void run() {
                while (true) {
                    HttpRequestHandlerThread requestHandlerThread = httpDecodeRunnable.getHttpRequestHandlerThread();
                    if (requestHandlerThread != null) {
                        Socket socket = requestHandlerThread.getRequest().getHandler().getChannel().socket();
                        if (requestHandlerThread.getRequest().getMethod() != HttpMethod.CONNECT) {
                            HttpRequestHandlerThread oldHttpRequestHandlerThread = socketHttpRequestHandlerThreadMap.get(socket);
                            //清除老的请求
                            if (oldHttpRequestHandlerThread != null) {
                                oldHttpRequestHandlerThread.interrupt();
                            }
                            socketHttpRequestHandlerThreadMap.put(socket, requestHandlerThread);
                            serverConfig.getExecutor().execute(requestHandlerThread);
                            serverContext.getHttpDeCoderMap().remove(socket);
                        } else {
                            HttpRequestHandlerThread oldHttpRequestHandlerThread = socketHttpRequestHandlerThreadMap.get(socket);
                            if (oldHttpRequestHandlerThread == null) {
                                socketHttpRequestHandlerThreadMap.put(socket, requestHandlerThread);
                                serverConfig.getExecutor().execute(requestHandlerThread);
                            }
                        }
                    }
                }
            }
        }.start();
    }

    @Override
    public void destroy() {
        if (selector == null) {
            return;
        }
        try {
            selector.close();
            LOGGER.info("close webServer success");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "close selector error");
        }
    }

    @Override
    public boolean create() {
        return create(serverConfig.getPort());
    }

    public boolean create(int port) {
        try {
            final ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.socket().bind(new InetSocketAddress(serverConfig.getHost(), port));
            serverChannel.configureBlocking(false);
            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            LOGGER.info(ServerInfo.getName() + " listening on port -> " + port);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "", e);
            return false;
        }
    }

    private ResponseConfig getDefaultResponseConfig() {
        ResponseConfig config = new ResponseConfig();
        config.setCharSet("UTF-8");
        if (responseConfig == null) {
            config.setIsGzip(false);
        } else {
            config.setIsGzip(responseConfig.isGzip());
        }
        config.setDisableCookie(serverConfig.isDisableCookie());
        return config;
    }

    private RequestConfig getDefaultRequestConfig() {
        RequestConfig config = new RequestConfig();
        config.setDisableCookie(serverConfig.isDisableCookie());
        config.setRouter(serverConfig.getRouter());
        config.setIsSsl(serverConfig.isSsl());
        return config;
    }

    public CheckRequestRunnable getCheckRequestRunnable() {
        return checkRequestRunnable;
    }
}
