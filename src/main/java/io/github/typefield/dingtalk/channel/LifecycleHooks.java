package io.github.typefield.dingtalk.channel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 连接生命周期钩子。
 */
public final class LifecycleHooks {
    private final List<Runnable> onReadyHandlers = new CopyOnWriteArrayList<>();
    private final List<Consumer<Throwable>> onErrorHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> onReconnectingHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> onReconnectedHandlers = new CopyOnWriteArrayList<>();
    private final List<Runnable> onDisconnectedHandlers = new CopyOnWriteArrayList<>();

    public void onReady(Runnable handler) {
        onReadyHandlers.add(handler);
    }

    public void onError(Consumer<Throwable> handler) {
        onErrorHandlers.add(handler);
    }

    public void onReconnecting(Runnable handler) {
        onReconnectingHandlers.add(handler);
    }

    public void onReconnected(Runnable handler) {
        onReconnectedHandlers.add(handler);
    }

    public void onDisconnected(Runnable handler) {
        onDisconnectedHandlers.add(handler);
    }

    public void fireReady() {
        for (Runnable h : onReadyHandlers) {
            try {
                h.run();
            } catch (Exception e) {
                // 忽略钩子异常
            }
        }
    }

    public void fireError(Throwable err) {
        for (Consumer<Throwable> h : onErrorHandlers) {
            try {
                h.accept(err);
            } catch (Exception e) {
                // 忽略钩子异常
            }
        }
    }

    public void fireReconnecting() {
        for (Runnable h : onReconnectingHandlers) {
            try {
                h.run();
            } catch (Exception e) {
                // 忽略钩子异常
            }
        }
    }

    public void fireReconnected() {
        for (Runnable h : onReconnectedHandlers) {
            try {
                h.run();
            } catch (Exception e) {
                // 忽略钩子异常
            }
        }
    }

    public void fireDisconnected() {
        for (Runnable h : onDisconnectedHandlers) {
            try {
                h.run();
            } catch (Exception e) {
                // 忽略钩子异常
            }
        }
    }
}
