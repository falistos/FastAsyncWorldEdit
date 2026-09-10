package com.fastasyncworldedit.core.util.task;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** CompletableFuture view which keeps implicit and late continuations on isolated notification tasks. */
final class CompletionServiceFuture<T> extends CompletableFuture<T> {

    private final OperationCompletionService completionService;
    private final Executor executor;

    CompletionServiceFuture(OperationCompletionService completionService) {
        this.completionService = Objects.requireNonNull(completionService, "completionService");
        this.executor = completionService.notificationExecutor();
    }

    @Override
    public Executor defaultExecutor() {
        return executor;
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new CompletionServiceFuture<>(completionService);
    }

    private Executor continuationExecutor(Executor requested) {
        return completionService.isolatedContinuationExecutor(requested);
    }

    @Override
    public <U> CompletableFuture<U> thenApply(Function<? super T, ? extends U> function) {
        return super.thenApplyAsync(function, executor);
    }

    @Override
    public <U> CompletableFuture<U> thenApplyAsync(
            Function<? super T, ? extends U> function,
            Executor executor
    ) {
        return super.thenApplyAsync(function, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<Void> thenAccept(Consumer<? super T> action) {
        return super.thenAcceptAsync(action, executor);
    }

    @Override
    public CompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return super.thenAcceptAsync(action, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<Void> thenRun(Runnable action) {
        return super.thenRunAsync(action, executor);
    }

    @Override
    public CompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        return super.thenRunAsync(action, continuationExecutor(executor));
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombine(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function
    ) {
        return super.thenCombineAsync(other, function, executor);
    }

    @Override
    public <U, V> CompletableFuture<V> thenCombineAsync(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> function,
            Executor executor
    ) {
        return super.thenCombineAsync(other, function, continuationExecutor(executor));
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBoth(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action
    ) {
        return super.thenAcceptBothAsync(other, action, executor);
    }

    @Override
    public <U> CompletableFuture<Void> thenAcceptBothAsync(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action,
            Executor executor
    ) {
        return super.thenAcceptBothAsync(other, action, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return super.runAfterBothAsync(other, action, executor);
    }

    @Override
    public CompletableFuture<Void> runAfterBothAsync(
            CompletionStage<?> other,
            Runnable action,
            Executor executor
    ) {
        return super.runAfterBothAsync(other, action, continuationExecutor(executor));
    }

    @Override
    public <U> CompletableFuture<U> applyToEither(
            CompletionStage<? extends T> other,
            Function<? super T, U> function
    ) {
        return super.applyToEitherAsync(other, function, executor);
    }

    @Override
    public <U> CompletableFuture<U> applyToEitherAsync(
            CompletionStage<? extends T> other,
            Function<? super T, U> function,
            Executor executor
    ) {
        return super.applyToEitherAsync(other, function, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<Void> acceptEither(
            CompletionStage<? extends T> other,
            Consumer<? super T> action
    ) {
        return super.acceptEitherAsync(other, action, executor);
    }

    @Override
    public CompletableFuture<Void> acceptEitherAsync(
            CompletionStage<? extends T> other,
            Consumer<? super T> action,
            Executor executor
    ) {
        return super.acceptEitherAsync(other, action, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return super.runAfterEitherAsync(other, action, executor);
    }

    @Override
    public CompletableFuture<Void> runAfterEitherAsync(
            CompletionStage<?> other,
            Runnable action,
            Executor executor
    ) {
        return super.runAfterEitherAsync(other, action, continuationExecutor(executor));
    }

    @Override
    public <U> CompletableFuture<U> thenCompose(
            Function<? super T, ? extends CompletionStage<U>> function
    ) {
        return super.thenComposeAsync(function, executor);
    }

    @Override
    public <U> CompletableFuture<U> thenComposeAsync(
            Function<? super T, ? extends CompletionStage<U>> function,
            Executor executor
    ) {
        return super.thenComposeAsync(function, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<T> exceptionally(Function<Throwable, ? extends T> function) {
        return super.exceptionallyAsync(function, executor);
    }

    @Override
    public CompletableFuture<T> exceptionallyAsync(
            Function<Throwable, ? extends T> function,
            Executor executor
    ) {
        return super.exceptionallyAsync(function, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return super.whenCompleteAsync(action, executor);
    }

    @Override
    public CompletableFuture<T> whenCompleteAsync(
            BiConsumer<? super T, ? super Throwable> action,
            Executor executor
    ) {
        return super.whenCompleteAsync(action, continuationExecutor(executor));
    }

    @Override
    public <U> CompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> function) {
        return super.handleAsync(function, executor);
    }

    @Override
    public <U> CompletableFuture<U> handleAsync(
            BiFunction<? super T, Throwable, ? extends U> function,
            Executor executor
    ) {
        return super.handleAsync(function, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<T> exceptionallyCompose(
            Function<Throwable, ? extends CompletionStage<T>> function
    ) {
        return super.exceptionallyComposeAsync(function, executor);
    }

    @Override
    public CompletableFuture<T> exceptionallyComposeAsync(
            Function<Throwable, ? extends CompletionStage<T>> function,
            Executor executor
    ) {
        return super.exceptionallyComposeAsync(function, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
        return super.completeAsync(supplier, continuationExecutor(executor));
    }

    @Override
    public CompletableFuture<T> copy() {
        return super.thenApplyAsync(Function.identity(), executor);
    }

    @Override
    public CompletionStage<T> minimalCompletionStage() {
        return new CompletionServiceStage<>(this, completionService);
    }

}
