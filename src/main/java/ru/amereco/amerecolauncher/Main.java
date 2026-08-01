/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ru.amereco.amerecolauncher;

import ru.amereco.amerecolauncher.diag.CrashReporter;

/**
 *
 * @author lanode
 */
public class Main {
    public static void main(String[] args) {
        // Первым делом — лог, чтобы в него попало всё, включая ошибки старта.
        Log.init();

        // Сразу за логом — диагностика. Она вешает перехватчик исключений,
        // разбирает дампы прошлого запуска и ставит маркер текущего, поэтому
        // должна успеть до первого кода, способного упасть.
        CrashReporter.install();

        System.setProperty("prism.texture.filter", "nearest");

        App.main(args);
    }
}
