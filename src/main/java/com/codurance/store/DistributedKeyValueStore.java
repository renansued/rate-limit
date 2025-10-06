package com.codurance.store;

import java.util.concurrent.CompletableFuture;

/**
 * External distributed key-value store abstraction. Implementation is provided externally
 * and not part of this exercise. Methods may perform network calls and are asynchronous.
 */
public interface DistributedKeyValueStore {
    /**
     * Atomically increments the integer value at key by delta and, if the key is being
     * initialized, sets an expiration on the key of expirationSeconds.
     * Returns the value AFTER increment.
     */
    CompletableFuture<Integer> incrementByAndExpire(String key, int delta, int expirationSeconds) throws Exception;
}
