/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft.models;

import com.google.gson.annotations.SerializedName;

import ru.amereco.amerecolauncher.Config;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 *
 * @author lanode
 */
public record Rule(
    Action action,
    Map<String, Boolean> features,
    OS os
) {
    public enum Action {
        @SerializedName("allow") ALLOW,
        @SerializedName("disallow") DISALLOW
    }

    public record OS(
        OSName name,
        String version,
        String arch
    ) {
        public enum OSName {
            @SerializedName("osx") OSX,
            @SerializedName("windows") WINDOWS,
            @SerializedName("linux") LINUX
        }
    }

    /**
     * Вычисляет итоговое разрешение по списку правил по семантике Mojang
     * («последнее совпавшее правило решает», старт — запрещено).
     * Пустой/null список — разрешено.
     */
    public static boolean isAllowed(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule rule : rules) {
            if (rule.matches()) {
                allowed = rule.action() == Action.ALLOW;
            }
        }
        return allowed;
    }

    /**
     * Проверяет, применимо ли правило к текущему окружению (ОС/архитектура/версия/features),
     * НЕ учитывая action. Правило без ограничений применимо всегда.
     */
    public boolean matches() {
        if (os != null) {
            if (os.name() != null) {
                boolean ok = switch (os.name()) {
                    case WINDOWS -> org.apache.commons.exec.OS.isFamilyWindows();
                    case OSX -> org.apache.commons.exec.OS.isFamilyMac();
                    // isFamilyUnix() истинна и на macOS — исключаем Mac явно.
                    case LINUX -> org.apache.commons.exec.OS.isFamilyUnix()
                            && !org.apache.commons.exec.OS.isFamilyMac();
                };
                if (!ok) {
                    return false;
                }
            }
            if (os.arch() != null && !os.arch().isEmpty()) {
                if (!normArch(System.getProperty("os.arch", "")).equals(normArch(os.arch()))) {
                    return false;
                }
            }
            if (os.version() != null && !os.version().isEmpty()) {
                // В манифесте os.version — регулярное выражение, а не точная строка.
                try {
                    if (!Pattern.compile(os.version())
                            .matcher(System.getProperty("os.version", "")).find()) {
                        return false;
                    }
                } catch (RuntimeException ignored) {
                    // некорректный regex — не накладываем ограничение по версии
                }
            }
        }

        if (features != null) {
            Config config = Config.get();
            for (var entry : features.entrySet()) {
                boolean want = entry.getValue() != null && entry.getValue();
                boolean have = config.features.getOrDefault(entry.getKey(), false);
                if (want != have) {
                    return false;
                }
            }
        }

        return true;
    }

    private static String normArch(String arch) {
        String a = arch.toLowerCase();
        if (a.contains("aarch64") || a.contains("arm64")) return "arm64";
        if (a.contains("arm")) return "arm";
        if (a.contains("64") || a.equals("x64")) return "x64";
        if (a.contains("86") || a.equals("x32")) return "x86";
        return a;
    }
}
