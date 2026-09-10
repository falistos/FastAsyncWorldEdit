package com.fastasyncworldedit.core.util.task;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * CompletionStage whose implicit continuations run as isolated operation-completion notifications.
 * Late continuations remain isolated after the control service has terminated.
 */
final class CompletionServiceStage<T> implements CompletionStage<T> {

    private final CompletionStage<T> delegate;
    private final OperationCompletionService completionService;
    private final Executor executor;

    CompletionServiceStage(CompletionStage<T> delegate, OperationCompletionService completionService) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.completionService = Objects.requireNonNull(completionService, "completionService");
        this.executor = completionService.notificationExecutor();
    }

    private <U> CompletionStage<U> wrap(CompletionStage<U> stage) {
        return new CompletionServiceStage<>(stage, completionService);
    }

    private Executor continuationExecutor(Executor requested) {
        return completionService.isolatedContinuationExecutor(requested);
    }

    @Override
    public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> function) {
        return wrap(delegate.thenApplyAsync(function, executor));
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> function) {
        return thenApply(function);
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> function, Executor executor) {
        return wrap(delegate.thenApplyAsync(function, continuationExecutor(executor)));
    }

    @Override
    public CompletionStage<Void> thenAccept(Consumer<? super T> action) {
        return wrap(delegate.thenAcceptAsync(action, executor));
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) {
        return thenAccept(action);
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return wrap(delegate.thenAcceptAsync(action, continuationExecutor(executor)));
    }

    @Override
    public CompletionStage<Void> thenRun(Runnable action) {
        return wrap(delegate.thenRunAsync(action, executor));
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action) {
        return thenRun(action);
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) {
        return wrap(delegate.thenRunAsync(action, continuationExecutor(executor)));
    }

    @Override
    public <U, V> CompletionStage<V> thenCombine(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function
    ) {
        return wrap(delegate.thenCombineAsync(other, function, executor));
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function
    ) {
        return thenCombine(other, function);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function,
            Executor executor
    ) {
        return wrap(delegate.thenCombineAsync(other, function, continuationExecutor(executor)));
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBoth(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action
    ) {
        return wrap(delegate.thenAcceptBothAsync(other, action, executor));
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action
    ) {
        return thenAcceptBoth(other, action);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action,
            Executor executor
    ) {
        return wrap(delegate.thenAcceptBothAsync(other, action, continuationExecutor(executor)));
    }

    @Override
    public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return wrap(delegate.runAfterBothAsync(other, action, executor));
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return runAfterBoth(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return wrap(delegate.runAfterBothAsync(other, action, continuationExecutor(executor)));
    }

    @Override
    public <U> CompletionStage<U> applyToEither(
            CompletionStage<? extends T> other,
            Function<? super T, U> function
    ) {
        return wrap(delegate.applyToEitherAsync(other, function, executor));
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(
            CompletionStage<? extends T> other,
            Function<? super T, U> function
    ) {
        return applyToEither(other, function);
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(
            CompletionStage<? extends T> other,
            Function<? super T, U> function,
            Executor executor
    ) {
        return wrap(delegate.applyToEitherAsync(other, function, continuationExecutor(executor)));
    }

    @Override
    public CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return wrap(delegate.acceptEitherAsync(other, action, executor));
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return acceptEither(other, action);
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(
            CompletionStage<? extends T> other,
            Consumer<? super T> action,
            Executor executor
    ) {
        return wrap(delegate.acceptEitherAsync(other, action, continuationExecutor(executor)));
    }

    @Override
    public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return wrap(delegate.runAfterEitherAsync(other, action, executor));
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return runAfterEither(other, action);
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return wrap(delegate.runAfterEitherAsync(other, action, continuationExecutor(executor)));
    }

    @Override
    public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> function) {
        return wrap(delegate.thenComposeAsync(function, executor));
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> function) {
        return thenCompose(function);
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(
            Function<? super T, ? extends CompletionStage<U>> function,
            Executor executor
    ) {
        return wrap(delegate.thenComposeAsync(function, continuationExecutor(executor)));
    }

    @Override
    public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> function) {
        return wrap(delegate.exceptionallyAsync(function, executor));
    }

    @Override
    public CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> function) {
        return exceptionally(function);
    }

    @Override
    public CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> function, Executor executor) {
        return wrap(delegate.exceptionallyAsync(function, continuationExecutor(executor)));
    }

    @Override
    public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return wrap(delegate.whenCompleteAsync(action, executor));
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return whenComplete(action);
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(
            BiConsumer<? super T, ? super Throwable> action,
            Executor executor
    ) {
        return wrap(delegate.whenCompleteAsync(action, continuationExecutor(executor)));
    }

    @Override
    public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> function) {
        return wrap(delegate.handleAsync(function, executor));
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> function) {
        return handle(function);
    }

    @Override
    public <U> CompletionStage<U> handleAsync(
            BiFunction<? super T, Throwable, ? extends U> function,
            Executor executor
    ) {
        return wrap(delegate.handleAsync(function, continuationExecutor(executor)));
    }

    @Override
    public CompletionStage<T> exceptionallyCompose(
            Function<Throwable, ? extends CompletionStage<T>> function
    ) {
        return wrap(delegate.exceptionallyComposeAsync(function, executor));
    }

    @Override
    public CompletionStage<T> exceptionallyComposeAsync(
            Function<Throwable, ? extends CompletionStage<T>> function
    ) {
        return exceptionallyCompose(function);
    }

    @Override
    public CompletionStage<T> exceptionallyComposeAsync(
            Function<Throwable, ? extends CompletionStage<T>> function,
            Executor executor
    ) {
        return wrap(delegate.exceptionallyComposeAsync(function, continuationExecutor(executor)));
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
        CompletableFuture<T> future = new CompletionServiceFuture<>(completionService);
        delegate.whenCompleteAsync((value, failure) -> {
            if (failure == null) {
                future.complete(value);
            } else {
                future.completeExceptionally(failure);
            }
        }, executor);
        return future;
    }

}
