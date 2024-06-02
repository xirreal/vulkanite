package me.cortex.vulkanite.lib.base;

import java.util.concurrent.atomic.AtomicInteger;

public abstract class VObject {
    protected final AtomicInteger refCount = new AtomicInteger(0);
    protected Object heap = null;

    protected abstract void free();

    protected void incRef() {
        if (refCount.incrementAndGet() == 1) {
            // First reference, put into registry
            // So that the object is kept alive until we finish running `free()`
            VRegistry.INSTANCE.register(this);
        }
    }

    protected void decRef() {
        if (refCount.decrementAndGet() == 0) {
            VRegistry.INSTANCE.unregister(this);
        }
    }
}
