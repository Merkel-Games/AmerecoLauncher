/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft.models;

import com.google.gson.annotations.SerializedName;

import ru.amereco.amerecolauncher.Config;

import java.util.Map;

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
    
    public boolean allows() {
        boolean shouldDisallow = action == Action.DISALLOW;

        // Проверяем семейство ОС
        if (os != null) {
            if (os.name() != null) {
                switch (os.name()) {
                    case WINDOWS -> {
                        if (!org.apache.commons.exec.OS.isFamilyWindows())
                            return shouldDisallow;
                    }
                    case OSX -> {
                        if (!org.apache.commons.exec.OS.isFamilyMac())
                            return shouldDisallow;
                    }
                    case LINUX -> {
                        if (!org.apache.commons.exec.OS.isFamilyUnix())
                            return shouldDisallow;
                    }
                }
            }
            // Проверяем архитектуру, если указана
            if (os.arch() != null && !os.arch().isEmpty()) {
                if (!org.apache.commons.exec.OS.isArch(os.arch())) {
                    return shouldDisallow;
                }
            }
            // Проверяем версию, если указана
            if (os.version() != null && !os.version().isEmpty()) {
                if (!org.apache.commons.exec.OS.isVersion(os.version())) {
                    return shouldDisallow;
                }
            }
        }

        if (features != null) {
            Config config = Config.get();
            for (var entry : features.entrySet()) {
                if (!(entry.getValue() && config.features.getOrDefault(entry.getKey(), false))) {
                    return shouldDisallow;
                }
            }
        }

        return !shouldDisallow;
    }
}
