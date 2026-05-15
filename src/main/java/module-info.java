open module ru.amereco.amerecolauncher {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires org.json;
    requires transitive dev.dirs;
    requires com.google.gson;
    requires java.base;
    requires org.apache.commons.exec;

    exports ru.amereco.amerecolauncher;
    exports ru.amereco.amerecolauncher.httpsync;
    exports ru.amereco.amerecolauncher.minecraft;
    exports ru.amereco.amerecolauncher.minecraft.fabric;
    exports ru.amereco.amerecolauncher.minecraft.fabric.models;
    exports ru.amereco.amerecolauncher.minecraft.authlibinjector;
    exports ru.amereco.amerecolauncher.minecraft.mixins;
    exports ru.amereco.amerecolauncher.minecraft.models;
    exports ru.amereco.amerecolauncher.utils;
}
