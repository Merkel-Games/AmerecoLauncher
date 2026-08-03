package ru.amereco.amerecolauncher.httpsync.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LineDiffTest {
    private static List<String> lines(String csv) {
        return csv.isEmpty() ? List.of() : List.of(csv.split(",", -1));
    }

    private static String matched(String a, String b) {
        List<String> left = lines(a);
        List<int[]> pairs = LineDiff.match(left, lines(b));
        List<String> out = new ArrayList<>();
        for (int[] pair : pairs) {
            out.add(left.get(pair[0]));
        }
        return String.join(",", out);
    }

    @Test
    void identicalMatchesEverything() {
        assertEquals("a,b,c", matched("a,b,c", "a,b,c"));
    }

    @Test
    void nothingInCommon() {
        assertEquals("", matched("a,b", "x,y"));
    }

    @Test
    void findsLongestCommonSubsequence() {
        assertEquals("a,c,e", matched("a,b,c,d,e", "a,x,c,y,e"));
    }

    @Test
    void insertionOnOneSide() {
        assertEquals("a,b,c", matched("a,b,c", "a,новое,b,c"));
    }

    @Test
    void deletionOnOneSide() {
        assertEquals("a,c", matched("a,b,c", "a,c"));
    }

    @Test
    void emptySides() {
        assertEquals("", matched("", "a,b"));
        assertEquals("", matched("a,b", ""));
        assertEquals("", matched("", ""));
    }

    @Test
    @DisplayName("индексы строго возрастают по обеим сторонам")
    void indicesAreIncreasing() {
        List<int[]> pairs = LineDiff.match(lines("a,b,c,d,e,f"), lines("f,e,a,b,x,c,d"));
        int previousLeft = -1;
        int previousRight = -1;
        for (int[] pair : pairs) {
            assertTrue(pair[0] > previousLeft, "левый индекс не возрастает");
            assertTrue(pair[1] > previousRight, "правый индекс не возрастает");
            previousLeft = pair[0];
            previousRight = pair[1];
        }
    }

    @Test
    @DisplayName("повторяющиеся строки не ломают выравнивание")
    void repeatedLines() {
        assertEquals("x,x,x", matched("x,x,x", "x,x,x,x"));
    }

    @Test
    @DisplayName("длина совпадает с классической НОП")
    void lengthMatchesReferenceLcs() {
        String a = "a,b,c,b,d,a,b";
        String b = "b,d,c,a,b,a";
        assertEquals(referenceLcsLength(lines(a), lines(b)), LineDiff.match(lines(a), lines(b)).size());
    }

    /** Эталонная реализация на полной таблице — медленно, но заведомо верно. */
    private static int referenceLcsLength(List<String> a, List<String> b) {
        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                dp[i][j] = a.get(i - 1).equals(b.get(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp[a.size()][b.size()];
    }
}
