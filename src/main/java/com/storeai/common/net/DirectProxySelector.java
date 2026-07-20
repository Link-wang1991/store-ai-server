package com.storeai.common.net;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

/** Explicitly bypasses the macOS system proxy for services reachable directly. */
public final class DirectProxySelector {
    private DirectProxySelector() { }

    public static final ProxySelector INSTANCE = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress socketAddress, IOException exception) {
            // The caller owns logging and retry policy.
        }
    };
}
