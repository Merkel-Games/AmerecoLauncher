/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft.models;

import com.google.gson.JsonElement;
import java.net.URI;
import java.util.List;

/**
 *
 * @author lanode
 */
public record Version(
    Arguments arguments,
    Downloads downloads,
    AssetIndexInfo assetIndex,
    List<Library> libraries,
    String mainClass,
    String id,
    String type
) {
    public record Arguments(
        List<JsonElement> game,
        List<JsonElement> jvm
    ) {}

    public record AssetIndexInfo(
        String id,
        String sha1,
        int size,
        int totalSize,
        URI url
    ) {}

    public record Downloads(
        Download client
    ) {
        public record Download(
            String sha1,
            int size,
            URI url
        ) {}
    }
}
