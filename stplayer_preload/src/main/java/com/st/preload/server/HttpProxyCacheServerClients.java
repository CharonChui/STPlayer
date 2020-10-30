package com.st.preload.server;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.st.preload.GetRequest;
import com.st.preload.cache.CacheListener;
import com.st.preload.cache.FileCache;
import com.st.preload.config.Config;
import com.st.preload.exception.ProxyCacheException;
import com.st.preload.source.HttpUrlSource;
import com.st.preload.source.OkHttpSource;
import com.st.preload.utils.Preconditions;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 对应一个具体url视频的客户端，视频的下载，缓存，以及最后将数据交给播放器，都是在这里面处理的
 * Client for {@link HttpProxyCacheServer}
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
final class HttpProxyCacheServerClients {
    private static final String LOG_TAG = HttpProxyCacheServerClients.class.getSimpleName();
    private final AtomicInteger clientsCount = new AtomicInteger(0);
    // 视频资源的真实url
    private final String url;
    private volatile IProxyCache proxyCache;
    private final List<CacheListener> listeners = new CopyOnWriteArrayList<>();
    private final CacheListener uiCacheListener;
    private final Config config;

    public HttpProxyCacheServerClients(String url, Config config) {
        this.url = Preconditions.checkNotNull(url);
        this.config = Preconditions.checkNotNull(config);
        this.uiCacheListener = new UiListenerHandler(url, listeners);
    }

    public void processRequest(GetRequest request, Socket socket) throws ProxyCacheException, IOException {
        // 判断生成proxyCache的值
        startProcessRequest();
        try {
            clientsCount.incrementAndGet();
            // 处理转换逻辑
            proxyCache.processRequest(request, socket);
        } finally {
            finishProcessRequest();
        }
    }

    private synchronized void startProcessRequest() throws ProxyCacheException {
        proxyCache = proxyCache == null ? newHttpProxyCache(false) : proxyCache;
    }

    private synchronized void finishProcessRequest() {
        if (clientsCount.decrementAndGet() <= 0) {
            proxyCache.shutdown();
            proxyCache = null;
        }
    }

    public void registerCacheListener(CacheListener cacheListener) {
        listeners.add(cacheListener);
    }

    public void unregisterCacheListener(CacheListener cacheListener) {
        listeners.remove(cacheListener);
    }

    public void shutdown() {
        listeners.clear();
        if (proxyCache != null) {
            proxyCache.registerCacheListener(null);
            proxyCache.shutdown();
            proxyCache = null;
        }
        clientsCount.set(0);
    }

    public int getClientsCount() {
        return clientsCount.get();
    }

    // 创建一个处理当前请求的HttpProxyCache，而HttpProxyCache包含HttpUrlSource的文件下载和FileCache的文件缓存两部分
    private IProxyCache newHttpProxyCache(boolean useOkttp) throws ProxyCacheException {
        if (useOkttp) {
            OkHttpSource source = new OkHttpSource(url);
            FileCache cache = new FileCache(config.generateCacheFile(url), config.getDiskUsage());
            OkHttpProxyCache httpProxyCache = new OkHttpProxyCache(source, cache);
            httpProxyCache.registerCacheListener(uiCacheListener);
            return httpProxyCache;
        } else {
            HttpUrlSource source = new HttpUrlSource(url, config.getSourceInfoStorage(), config.getHeaderInjector());
            FileCache cache = new FileCache(config.generateCacheFile(url), config.getDiskUsage());
            HttpProxyCache httpProxyCache = new HttpProxyCache(source, cache);
            httpProxyCache.registerCacheListener(uiCacheListener);
            return httpProxyCache;
        }
    }

    private static final class UiListenerHandler extends Handler implements CacheListener {

        private final String url;
        private final List<CacheListener> listeners;

        public UiListenerHandler(String url, List<CacheListener> listeners) {
            super(Looper.getMainLooper());
            this.url = url;
            this.listeners = listeners;
        }

        @Override
        public void onCacheAvailable(File file, String url, int percentsAvailable) {
            Message message = obtainMessage();
            message.arg1 = percentsAvailable;
            message.obj = file;
            sendMessage(message);
        }

        @Override
        public void handleMessage(Message msg) {
            for (CacheListener cacheListener : listeners) {
                cacheListener.onCacheAvailable((File) msg.obj, url, msg.arg1);
            }
        }
    }
}
