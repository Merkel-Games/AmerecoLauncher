package ru.amereco.amerecolauncher.minecraft;

public class MinecraftSession {

    private static MinecraftSession instance;

    private Thread minecraftThread;
    private Runnable onStopped;

    private MinecraftSession() {}

    public static MinecraftSession getInstance() {
        if (instance == null) {
            instance = new MinecraftSession();
        }
        return instance;
    }

    public boolean isRunning() {
        return minecraftThread != null && minecraftThread.isAlive();
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
                if (onStopped != null) {
                    onStopped.run();
                }
            }
        });
        minecraftThread.start();
    }

    public void stop() {
        if (isRunning()) {
            minecraftThread.interrupt();
        }
    }
}
