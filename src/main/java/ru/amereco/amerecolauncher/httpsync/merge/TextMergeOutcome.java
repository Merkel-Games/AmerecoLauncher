package ru.amereco.amerecolauncher.httpsync.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Результат работы конкретного {@link TextMerger}: текст плюс перечень разногласий. */
final class TextMergeOutcome {
    private final String content;
    private final List<String> conflicts;
    private final int applied;

    TextMergeOutcome(String content, List<String> conflicts, int applied) {
        this.content = content;
        this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
        this.applied = applied;
    }

    String content() {
        return content;
    }

    /** Места, где сохранено локальное значение вопреки серверному. */
    List<String> conflicts() {
        return conflicts;
    }

    /** Сколько серверных изменений перенесено. */
    int applied() {
        return applied;
    }
}
