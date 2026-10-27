package ru.smart.factoring.backend.factoring.run;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Прогресс долгой части шага (тексты LLM, оценка моделей): сколько из скольких сделано и с какого момента -
 * экран процесса показывает процент и оставшееся время. Потокобезопасен: тексты LLM считаются параллельно.
 * В БД пишется не чаще раза в секунду, плюс в начале и в конце.
 */
public final class StepProgress {
    private static final long FLUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final String label;
    private final int total;
    private final OffsetDateTime since = OffsetDateTime.now();
    private final Consumer<Snapshot> sink;
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicLong flushedAt = new AtomicLong(System.nanoTime());

    StepProgress(final String label, final int total, final Consumer<Snapshot> sink) {
        this.label = label;
        this.total = total;
        this.sink = sink;
        sink.accept(new Snapshot(label, 0, total, since));
    }

    public void advance() {
        advance(1);
    }

    public void advance(final int count) {
        final int current = done.addAndGet(count);
        final long now = System.nanoTime();
        final long flushed = flushedAt.get();
        if (current >= total || now - flushed >= FLUSH_INTERVAL_NANOS && flushedAt.compareAndSet(flushed, now)) {
            sink.accept(new Snapshot(label, Math.min(current, total), total, since));
        }
    }

    /**
     * @param label что считается («Тексты карточек (LLM)»)
     * @param since начало этой части шага: от него экран считает скорость и оставшееся время
     */
    public record Snapshot(String label, int done, int total, OffsetDateTime since) {
    }
}
