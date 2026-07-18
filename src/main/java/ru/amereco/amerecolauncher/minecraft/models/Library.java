/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher.minecraft.models;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 *
 * @author lanode
 */
public record Library(
    String name,
    Downloads downloads,
    Map<String, String> natives,
    Extract extract,
    List<Rule> rules
) {
    public record Downloads(
        LibraryDownload artifact,
        Map<String, LibraryDownload> classifiers
    ) {
        public record LibraryDownload(
            String path,
            String sha1,
            Integer size,
            URI url
        ) {}
    }

    public record Extract(
        List<String> exclude
    ) {}

    public boolean shouldUse() {
        return Rule.isAllowed(rules);
    }
}
