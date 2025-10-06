package com.codurance.store;

import java.util.concurrent.CompletableFuture;

public interface DistributedKeyValueStore {
    /**
     * External - Atomically increments the integer value at key by delta and, if the key is being
     * initialized, sets an expiration on the key of expirationSeconds.
     * Returns the value AFTER increment.
     */
    CompletableFuture<Integer> incrementByAndExpire(String key, int delta, int expirationSeconds) throws Exception;
}
