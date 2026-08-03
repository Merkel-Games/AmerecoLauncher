package ru.amereco.amerecolauncher.utils;

/**
 * Обёртка над {@link javafx.application.Platform#runLater}, переживающая отсутствие тулкита.
 *
 * <p>В приложении тулкит поднят всегда, и поведение не отличается от прямого вызова.
 * Но {@code Platform.runLater} бросает {@link IllegalStateException} без запущенного тулкита,
 * а загрузчик вызывает его на каждой завершённой закачке — из-за этого синхронизацию
 * невозможно было бы прогнать в тестах. Здесь такой вызов просто выполняется на месте.
 */
public final class Fx {
    private Fx() {
    }

    public static void runLater(Runnable action) {
        try {
            javafx.application.Platform.runLater(action);
        } catch (IllegalStateException notStarted) {
            action.run();
        }
    }
}
