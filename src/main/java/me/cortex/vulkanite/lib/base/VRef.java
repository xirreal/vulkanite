package me.cortex.vulkanite.lib.base;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;

public class VRef<T extends VObject> implements Closeable {
    private static final Cleaner cleaner = Cleaner.create();
    private final State<T> state;
    private final Cleaner.Cleanable cleanable;

    public VRef(T ref) {
        if (ref == null) {
            throw new NullPointerException("VRef to null object");
        }
        ref.incRef();

        state = new State<>(ref);
        cleanable = cleaner.register(this, state);
    }

    /**
     * (Optional) Decrement the reference count and release the object if the reference count reaches 0.
     * This method can be called multiple times, but the object will only be released once.
     * If this method is not called, the object will be released when the VRef is garbage collected.
     */
    @Override
    public void close() {
        cleanable.clean();
    }

    @NotNull
    public VRef<T> addRef() {
        return new VRef<>(state.get());
    }

    @NotNull
    public VRef<VObject> addRefGeneric() {
        return new VRef<>(state.get());
    }

    @NotNull
    public T get() {
        return state.get();
    }

    static class State<T extends VObject> extends WeakReference<T> implements Runnable {
        State(T ref) {
            super(ref);
        }

        @NotNull
        @Override
        public T get() {
            T ref = super.get();
            if (ref == null) {
                throw new NullPointerException("Referenced object has been garbage collected while VRef was still active");
            }
            return ref;
        }

        @Override
        public void run() {
            get().decRef();
        }
    }
}
