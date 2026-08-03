package ru.amereco.amerecolauncher.httpsync.merge;

import java.util.List;

/** Построчное слияние — запасной путь для форматов, которые лаунчер не разбирает по ключам. */
final class LineMerger implements TextMerger {
    @Override
    public TextMergeOutcome merge(String base, String local, String remote) {
        List<String> localLines = TextCodec.lines(local);
        List<String> remoteLines = TextCodec.lines(remote);

        Diff3.Result result = base == null
                ? Diff3.merge2(localLines, remoteLines)
                : Diff3.merge3(TextCodec.lines(base), localLines, remoteLines);

        return new TextMergeOutcome(TextCodec.join(result.lines), result.conflicts, result.applied);
    }
}
