package ru.amereco.amerecolauncher.minecraft;

public class MinecraftSession {

    private static MinecraftSession instance;

    private volatile Thread minecraftThread;
    private volatile Runnable onStopped;

    private MinecraftSession() {}

    public static synchronized MinecraftSession getInstance() {
        if (instance == null) {
            instance = new MinecraftSession();
        }
        return instance;
    }

    public boolean isRunning() {
        Thread t = minecraftThread;
        return t != null && t.isAlive();
    }

    public void setOnStopped(Runnable callback) {
        this.onStopped = callback;
    }

    public void launch(Runnable task) {
        if (isRunning()) return;
        minecraftThread = new Thread(() -> {
            try {
                task.run();
            } finally {
                minecraftThread = null;
                Runnable cb = onStopped;
                if (cb != null) {
                    cb.run();
                }
            }
        });
        minecraftThread.start();
    }

    public void stop() {
        Thread t = minecraftThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }
}
