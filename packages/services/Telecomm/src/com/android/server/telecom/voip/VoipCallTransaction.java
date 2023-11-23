/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.voip;

import android.os.Handler;
import android.os.HandlerThread;
import android.telecom.Log;

import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.TelecomSystem;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class VoipCallTransaction {
    //TODO: add log events
    protected static final long TIMEOUT_LIMIT = 5000L;
    protected final AtomicBoolean mCompleted = new AtomicBoolean(false);
    protected String mTransactionName = this.getClass().getSimpleName();
    private HandlerThread mHandlerThread;
    protected Handler mHandler;
    protected TransactionManager.TransactionCompleteListener mCompleteListener;
    protected List<VoipCallTransaction> mSubTransactions;
    protected TelecomSystem.SyncRoot mLock;

    public VoipCallTransaction(
            List<VoipCallTransaction> subTransactions, TelecomSystem.SyncRoot lock) {
        mSubTransactions = subTransactions;
        mHandlerThread = new HandlerThread(this.toString());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mLock = lock;
    }

    public VoipCallTransaction(TelecomSystem.SyncRoot lock) {
        this(null /** mSubTransactions */, lock);
    }

    public void start() {
        // post timeout work
        CompletableFuture<Void> future = new CompletableFuture<>();
        mHandler.postDelayed(() -> future.complete(null), TIMEOUT_LIMIT);
        future.thenApplyAsync((x) -> {
            if (mCompleted.getAndSet(true)) {
                return null;
            }
            if (mCompleteListener != null) {
                mCompleteListener.onTransactionTimeout(mTransactionName);
            }
            finish();
            return null;
        }, new LoggedHandlerExecutor(mHandler, mTransactionName + "@" + hashCode()
                + ".s", mLock));

        scheduleTransaction();
    }

    protected void scheduleTransaction() {
        LoggedHandlerExecutor executor = new LoggedHandlerExecutor(mHandler,
                mTransactionName + "@" + hashCode() + ".pT", mLock);
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);
        future.thenComposeAsync(this::processTransaction, executor)
                .thenApplyAsync((Function<VoipCallTransactionResult, Void>) result -> {
                    mCompleted.set(true);
                    if (mCompleteListener != null) {
                        mCompleteListener.onTransactionCompleted(result, mTransactionName);
                    }
                    finish();
                    return null;
                    }, executor)
                .exceptionallyAsync((throwable -> {
                    Log.e(this, throwable, "Error while executing transaction.");
                    return null;
                }), executor);
    }

    public CompletionStage<VoipCallTransactionResult> processTransaction(Void v) {
        return CompletableFuture.completedFuture(
                new VoipCallTransactionResult(VoipCallTransactionResult.RESULT_SUCCEED, null));
    }

    public void setCompleteListener(TransactionManager.TransactionCompleteListener listener) {
        mCompleteListener = listener;
    }

    public void finish() {
        // finish all sub transactions
        if (mSubTransactions != null && mSubTransactions.size() > 0) {
            mSubTransactions.forEach(VoipCallTransaction::finish);
        }
        mHandlerThread.quit();
    }
}
