package ru.amereco.amerecolauncher.diag;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import ru.amereco.amerecolauncher.Config;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Диалог «лаунчер столкнулся с ошибкой — отправить отчёт?».
 *
 * <p>Показывается, когда автоотправка выключена (это состояние по умолчанию) и
 * накопились неодобренные отчёты. Решение принимает игрок, и до этого решения
 * на сервер не уходит ничего.
 *
 * <p>Обязательная часть — кнопка «Что будет отправлено»: она разворачивает точный
 * текст отчёта. Без возможности посмотреть содержимое согласие информированным
 * не является, а в русскоязычном Minecraft-сообществе лаунчер, который куда-то
 * молча стучится, доверия лишается мгновенно.
 */
public final class CrashDialog {

    /** Диалог уже на экране — второй поверх него не открываем. */
    private static final AtomicBoolean showing = new AtomicBoolean();

    private CrashDialog() {
    }

    /**
     * Показывает диалог по накопившимся отчётам.
     * Безопасно звать из любого потока: сам уйдёт в FX-поток.
     */
    public static void promptForPending() {
        if (!showing.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            try {
                show();
            } catch (Throwable t) {
                CrashReporter.reportQuietly("диалог отчёта не открылся", t);
            } finally {
                showing.set(false);
            }
        });
    }

    private static void show() {
        List<ReportQueue.Entry> entries = CrashReporter.pendingReports();
        if (entries.isEmpty()) {
            return;
        }

        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle("Ошибка в работе лаунчера");
        alert.setHeaderText(header(entries.size()));

        ButtonType send = new ButtonType("Отправить", ButtonBar.ButtonData.YES);
        ButtonType skip = new ButtonType("Не отправлять", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(send, skip);

        CheckBox always = new CheckBox("Впредь отправлять автоматически, не спрашивая");
        CheckBox withLogs = new CheckBox("Приложить лог лаунчера и игры");
        withLogs.setSelected(true);

        Label explanation = new Label(
                "Отправляются текст ошибки, стек вызовов, версии лаунчера, Java, ОС "
                + "и игры. Пароль, токен авторизации, e-mail, IP-адрес, имя пользователя "
                + "компьютера и игровой чат вырезаются автоматически до отправки.\n\n"
                + "Данные уходят на amereco.ru, хранятся не дольше 90 дней "
                + "и используются только для починки лаунчера.");
        explanation.setWrapText(true);
        explanation.setMaxWidth(520);

        VBox content = new VBox(10, explanation, withLogs, always);
        alert.getDialogPane().setContent(content);
        alert.getDialogPane().setExpandableContent(previewPane(entries));
        alert.getDialogPane().setPrefWidth(560);

        ButtonType answer = alert.showAndWait().orElse(skip);

        if (answer == send) {
            applyChoice(always.isSelected(), withLogs.isSelected());
            CrashReporter.approvePending();
        } else {
            CrashReporter.discardPending();
        }
    }

    /** Сохраняет выбор игрока в конфиг, если он согласился на автоотправку. */
    private static void applyChoice(boolean alwaysSend, boolean withLogs) {
        try {
            Config config = Config.get();
            config.diagSendLogs = withLogs;
            if (alwaysSend) {
                config.diagConsent = true;
                config.diagConsentVersion =
                        Config.properties.getProperty("diagConsentVersion");
                config.diagConsentTs = System.currentTimeMillis() / 1000L;
            }
            config.save();
        } catch (Throwable t) {
            CrashReporter.reportQuietly("не удалось сохранить согласие", t);
        }
    }

    private static String header(int count) {
        if (count == 1) {
            return "Лаунчер столкнулся с ошибкой.\nОтправить отчёт разработчику?";
        }
        return "Накопилось отчётов об ошибках: " + count + ".\nОтправить их разработчику?";
    }

    /**
     * Разворачиваемая панель с точным содержимым отчётов.
     *
     * <p>Показывается именно то, что уедет: текст берётся из тех же файлов очереди,
     * которые отправит {@link CrashReporter}, а не собирается заново.
     */
    private static GridPane previewPane(List<ReportQueue.Entry> entries) {
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (ReportQueue.Entry entry : entries) {
            CrashReport report = entry.read();
            if (report == null) {
                continue;
            }
            if (index++ > 0) {
                text.append("\n").append("─".repeat(60)).append("\n\n");
            }
            text.append(CrashReporter.preview(report));

            byte[] log = entry.readLog();
            if (log != null) {
                text.append("\n[к отчёту приложен сжатый лог, ")
                    .append(log.length / 1024).append(" КБ]\n");
            }
        }

        TextArea area = new TextArea(text.toString());
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefRowCount(18);
        area.setMaxWidth(Double.MAX_VALUE);
        area.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(area, Priority.ALWAYS);
        GridPane.setHgrow(area, Priority.ALWAYS);

        GridPane pane = new GridPane();
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.add(new Label("Что именно будет отправлено:"), 0, 0);
        pane.add(area, 0, 1);
        return pane;
    }
}
