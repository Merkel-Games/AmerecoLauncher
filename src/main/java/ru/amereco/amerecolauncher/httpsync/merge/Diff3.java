package ru.amereco.amerecolauncher.httpsync.merge;

import java.util.ArrayList;
import java.util.List;

/**
 * Построчное слияние.
 *
 * <p>Спорные участки выделяются как в diff3: сначала для каждой стороны считаются области,
 * которыми она отличается от эталона, и лишь <b>пересекающиеся</b> области двух сторон
 * объединяются в один участок. Соседство не считается пересечением — иначе добавленная
 * сервером строка сразу под правкой игрока попадала бы с ней в один участок и терялась,
 * а именно так и выглядит типичное обновление конфига мода.
 *
 * <p>Маркеры вида {@code <<<<<<<} в файл не пишутся никогда: в {@code .toml} или {@code .cfg}
 * они ломают разбор и мод падает на старте. Вместо этого в спорном месте остаются строки игрока,
 * а серверная версия целиком кладётся рядом в {@code .new}.
 */
final class Diff3 {
    private Diff3() {
    }

    static final class Result {
        final List<String> lines;
        final List<String> conflicts;
        final int applied;

        Result(List<String> lines, List<String> conflicts, int applied) {
            this.lines = lines;
            this.conflicts = conflicts;
            this.applied = applied;
        }
    }

    /**
     * Трёхстороннее слияние относительно эталона.
     *
     * <p>Изменилась одна сторона — берётся она; обе одинаково — берётся любая;
     * обе по-разному — остаются строки локального файла и засчитывается конфликт.
     */
    static Result merge3(List<String> base, List<String> local, List<String> remote) {
        List<Region> localRegions = regions(base.size(), local.size(), LineDiff.match(base, local));
        List<Region> remoteRegions = regions(base.size(), remote.size(), LineDiff.match(base, remote));

        Sink sink = new Sink();
        int baseCursor = 0;
        int localCursor = 0;
        int remoteCursor = 0;
        int li = 0;
        int ri = 0;

        while (li < localRegions.size() || ri < remoteRegions.size()) {
            boolean takeLocal = ri >= remoteRegions.size()
                    || (li < localRegions.size()
                        && localRegions.get(li).baseLo <= remoteRegions.get(ri).baseLo);
            Region seed = takeLocal ? localRegions.get(li++) : remoteRegions.get(ri++);

            int groupLo = seed.baseLo;
            int groupHi = seed.baseHi;
            List<Region> inLocal = new ArrayList<>();
            List<Region> inRemote = new ArrayList<>();
            (takeLocal ? inLocal : inRemote).add(seed);

            boolean grown = true;
            while (grown) {
                grown = false;
                while (li < localRegions.size() && overlaps(localRegions.get(li), groupLo, groupHi)) {
                    Region region = localRegions.get(li++);
                    groupHi = Math.max(groupHi, region.baseHi);
                    inLocal.add(region);
                    grown = true;
                }
                while (ri < remoteRegions.size() && overlaps(remoteRegions.get(ri), groupLo, groupHi)) {
                    Region region = remoteRegions.get(ri++);
                    groupHi = Math.max(groupHi, region.baseHi);
                    inRemote.add(region);
                    grown = true;
                }
            }

            // Устойчивый кусок до участка совпадает во всех трёх версиях — переносим как есть.
            int stable = groupLo - baseCursor;
            sink.lines.addAll(base.subList(baseCursor, groupLo));
            localCursor += stable;
            remoteCursor += stable;
            baseCursor = groupLo;

            int localHi = sideEnd(localCursor, groupLo, groupHi, inLocal);
            int remoteHi = sideEnd(remoteCursor, groupLo, groupHi, inRemote);

            resolve3(base.subList(groupLo, groupHi),
                    local.subList(localCursor, localHi),
                    remote.subList(remoteCursor, remoteHi), sink);

            baseCursor = groupHi;
            localCursor = localHi;
            remoteCursor = remoteHi;
        }

        sink.lines.addAll(base.subList(baseCursor, base.size()));
        return sink.toResult();
    }

    /**
     * Двустороннее слияние — когда эталонного снимка нет и «кто именно менял» неизвестно.
     *
     * <p>Общие строки сохраняются, дописанное игроком остаётся, дописанное сервером добавляется,
     * а участок, переписанный с обеих сторон, остаётся локальным и считается конфликтом.
     */
    static Result merge2(List<String> local, List<String> remote) {
        Sink sink = new Sink();
        int li = 0;
        int ri = 0;
        for (int[] pair : LineDiff.match(local, remote)) {
            resolve2(local.subList(li, pair[0]), remote.subList(ri, pair[1]), sink);
            sink.lines.add(local.get(pair[0]));
            li = pair[0] + 1;
            ri = pair[1] + 1;
        }
        resolve2(local.subList(li, local.size()), remote.subList(ri, remote.size()), sink);
        return sink.toResult();
    }

    /**
     * Куда доезжает курсор стороны на участке эталона {@code [groupLo, groupHi)}.
     * Строки эталона, не покрытые областями этой стороны, идут один к одному.
     */
    private static int sideEnd(int cursor, int groupLo, int groupHi, List<Region> regions) {
        int end = cursor;
        int baseAt = groupLo;
        for (Region region : regions) {
            end += region.baseLo - baseAt;
            end += region.sideHi - region.sideLo;
            baseAt = region.baseHi;
        }
        return end + (groupHi - baseAt);
    }

    /** Строгое пересечение по эталону; полностью совпадающие пустые вставки тоже объединяются. */
    private static boolean overlaps(Region region, int groupLo, int groupHi) {
        if (Math.max(region.baseLo, groupLo) < Math.min(region.baseHi, groupHi)) {
            return true;
        }
        return region.baseLo == groupLo && region.baseHi == groupHi;
    }

    /** Области, которыми сторона отличается от эталона. */
    private static List<Region> regions(int baseSize, int sideSize, List<int[]> matches) {
        List<Region> out = new ArrayList<>();
        int base = 0;
        int side = 0;
        for (int[] pair : matches) {
            if (pair[0] > base || pair[1] > side) {
                out.add(new Region(base, pair[0], side, pair[1]));
            }
            base = pair[0] + 1;
            side = pair[1] + 1;
        }
        if (baseSize > base || sideSize > side) {
            out.add(new Region(base, baseSize, side, sideSize));
        }
        return out;
    }

    private static void resolve3(List<String> base, List<String> local, List<String> remote, Sink sink) {
        if (local.equals(remote)) {
            sink.lines.addAll(local);
        } else if (local.equals(base)) {
            sink.lines.addAll(remote);
            sink.applied++;
        } else if (remote.equals(base)) {
            sink.lines.addAll(local);
        } else {
            sink.conflict();
            sink.lines.addAll(local);
        }
    }

    private static void resolve2(List<String> local, List<String> remote, Sink sink) {
        if (local.isEmpty() && remote.isEmpty()) {
            return;
        }
        if (local.isEmpty()) {
            // Строки, дописанные на сервере.
            sink.lines.addAll(remote);
            sink.applied++;
        } else if (remote.isEmpty()) {
            // Строки, дописанные игроком.
            sink.lines.addAll(local);
        } else {
            sink.conflict();
            sink.lines.addAll(local);
        }
    }

    private static final class Region {
        final int baseLo;
        final int baseHi;
        final int sideLo;
        final int sideHi;

        Region(int baseLo, int baseHi, int sideLo, int sideHi) {
            this.baseLo = baseLo;
            this.baseHi = baseHi;
            this.sideLo = sideLo;
            this.sideHi = sideHi;
        }
    }

    private static final class Sink {
        final List<String> lines = new ArrayList<>();
        final List<String> conflicts = new ArrayList<>();
        int applied;

        /** Номер строки в получившемся файле — по нему игрок найдёт спорное место. */
        void conflict() {
            conflicts.add("строка " + (lines.size() + 1));
        }

        Result toResult() {
            return new Result(lines, conflicts, applied);
        }
    }
}
