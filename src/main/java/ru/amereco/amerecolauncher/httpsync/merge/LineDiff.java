package ru.amereco.amerecolauncher.httpsync.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Наибольшая общая подпоследовательность строк — основа построчного слияния.
 *
 * <p>Алгоритм Хиршберга: время O(n·m), память O(min(n,m)). Обычная таблица динамического
 * программирования на паре файлов по несколько тысяч строк съела бы сотни мегабайт, а
 * готовая реализация Майерса потянула бы за собой стороннюю зависимость — при явных
 * {@code requires} в module-info и упаковке в uber-jar это лишний риск сборки ради
 * полутора сотен строк кода.
 */
final class LineDiff {
    private LineDiff() {
    }

    /**
     * Сопоставляет строки двух списков.
     *
     * @return пары {@code {i, j}} — индексы совпавших строк, по возрастанию обоих индексов
     */
    static List<int[]> match(List<String> a, List<String> b) {
        List<int[]> head = new ArrayList<>();
        int aLo = 0;
        int bLo = 0;
        while (aLo < a.size() && bLo < b.size() && a.get(aLo).equals(b.get(bLo))) {
            head.add(new int[]{aLo, bLo});
            aLo++;
            bLo++;
        }

        List<int[]> tail = new ArrayList<>();
        int aHi = a.size();
        int bHi = b.size();
        while (aHi > aLo && bHi > bLo && a.get(aHi - 1).equals(b.get(bHi - 1))) {
            aHi--;
            bHi--;
            tail.add(new int[]{aHi, bHi});
        }
        Collections.reverse(tail);

        List<int[]> middle = new ArrayList<>();
        hirschberg(a, aLo, aHi, b, bLo, bHi, middle);

        List<int[]> out = new ArrayList<>(head.size() + middle.size() + tail.size());
        out.addAll(head);
        out.addAll(middle);
        out.addAll(tail);
        return out;
    }

    private static void hirschberg(List<String> a, int aLo, int aHi,
                                   List<String> b, int bLo, int bHi, List<int[]> out) {
        int n = aHi - aLo;
        int m = bHi - bLo;
        if (n == 0 || m == 0) {
            return;
        }
        if (n == 1) {
            String only = a.get(aLo);
            for (int j = bLo; j < bHi; j++) {
                if (only.equals(b.get(j))) {
                    out.add(new int[]{aLo, j});
                    return;
                }
            }
            return;
        }

        int mid = aLo + n / 2;
        int[] forward = lcsForward(a, aLo, mid, b, bLo, bHi);
        int[] backward = lcsBackward(a, mid, aHi, b, bLo, bHi);

        int best = -1;
        int split = bLo;
        for (int k = 0; k <= m; k++) {
            int total = forward[k] + backward[k];
            if (total > best) {
                best = total;
                split = bLo + k;
            }
        }

        hirschberg(a, aLo, mid, b, bLo, split, out);
        hirschberg(a, mid, aHi, b, split, bHi, out);
    }

    /** {@code result[j]} = длина НОП для {@code a[aLo..aHi)} и {@code b[bLo..bLo+j)}. */
    private static int[] lcsForward(List<String> a, int aLo, int aHi, List<String> b, int bLo, int bHi) {
        int m = bHi - bLo;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = aLo; i < aHi; i++) {
            String ai = a.get(i);
            cur[0] = 0;
            for (int j = 1; j <= m; j++) {
                cur[j] = ai.equals(b.get(bLo + j - 1))
                        ? prev[j - 1] + 1
                        : Math.max(prev[j], cur[j - 1]);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev;
    }

    /** {@code result[j]} = длина НОП для {@code a[aLo..aHi)} и {@code b[bLo+j..bHi)}. */
    private static int[] lcsBackward(List<String> a, int aLo, int aHi, List<String> b, int bLo, int bHi) {
        int m = bHi - bLo;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = aHi - 1; i >= aLo; i--) {
            String ai = a.get(i);
            cur[m] = 0;
            for (int j = m - 1; j >= 0; j--) {
                cur[j] = ai.equals(b.get(bLo + j))
                        ? prev[j + 1] + 1
                        : Math.max(prev[j], cur[j + 1]);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev;
    }
}
