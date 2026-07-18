/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.utils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 *
 * @author lanode
 */
public class ProgressSupplier {
    // progress увеличивается из нескольких потоков загрузки — atomic, чтобы избежать
    // потерянных инкрементов (race на неатомарном progress++).
    protected final AtomicInteger progress = new AtomicInteger();
    protected volatile int maxProgress;
    protected volatile String stage;
    protected volatile String step;
    protected volatile Consumer<ProgressData> onProgress;

    public void setOnProgress(Consumer<ProgressData> callback) {
        this.onProgress = callback;
    }

    /** Атомарно задаёт границу прогресса и обнуляет счётчик для новой фазы. */
    protected void resetProgress(int max) {
        maxProgress = max;
        progress.set(0);
    }

    protected void updateStage(String stage) {
        this.stage = stage;
        Consumer<ProgressData> cb = onProgress;
        if (cb != null) {
            cb.accept(new ProgressData(maxProgress, progress.get(), stage, step));
        }
    }

    protected void updateStep(String step) {
        this.step = step;
        Consumer<ProgressData> cb = onProgress;
        if (cb != null) {
            cb.accept(new ProgressData(maxProgress, progress.get(), stage, step));
        }
    }

    protected void updateStepAndIncProgress(String step) {
        progress.incrementAndGet();
        updateStep(step);
    }
}
