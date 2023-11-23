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

import static android.telecom.CallException.CODE_OPERATION_TIMED_OUT;

import android.os.OutcomeReceiver;
import android.telecom.TelecomManager;
import android.telecom.CallException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class TransactionManager {
    private static final String TAG = "VoipCallTransactionManager";
    private static TransactionManager INSTANCE = null;
    private static final Object sLock = new Object();
    private Queue<VoipCallTransaction> mTransactions;
    private VoipCallTransaction mCurrentTransaction;

    public interface TransactionCompleteListener {
        void onTransactionCompleted(VoipCallTransactionResult result, String transactionName);
        void onTransactionTimeout(String transactionName);
    }

    private TransactionManager() {
        mTransactions = new ArrayDeque<>();
        mCurrentTransaction = null;
    }

    public static TransactionManager getInstance() {
        synchronized (sLock) {
            if (INSTANCE == null) {
                INSTANCE = new TransactionManager();
            }
        }
        return INSTANCE;
    }

    @VisibleForTesting
    public static TransactionManager getTestInstance() {
        return new TransactionManager();
    }

    public void addTransaction(VoipCallTransaction transaction,
            OutcomeReceiver<VoipCallTransactionResult, CallException> receiver) {
        synchronized (sLock) {
            mTransactions.add(transaction);
        }
        transaction.setCompleteListener(new TransactionCompleteListener() {
            @Override
            public void onTransactionCompleted(VoipCallTransactionResult result,
                    String transactionName){
                Log.i(TAG, String.format("transaction %s completed: with result=[%d]",
                        transactionName, result.getResult()));
                if (result.getResult() == TelecomManager.TELECOM_TRANSACTION_SUCCESS) {
                    receiver.onResult(result);
                } else {
                    receiver.onError(
                            new CallException(result.getMessage(),
                                    result.getResult()));
                }
                finishTransaction();
            }

            @Override
            public void onTransactionTimeout(String transactionName){
                Log.i(TAG, String.format("transaction %s timeout", transactionName));
                receiver.onError(new CallException(transactionName + " timeout",
                        CODE_OPERATION_TIMED_OUT));
                finishTransaction();
            }
        });

        startTransactions();
    }

    private void startTransactions() {
        synchronized (sLock) {
            if (mTransactions.isEmpty()) {
                // No transaction waiting for process
                return;
            }

            if (mCurrentTransaction != null) {
                // Ongoing transaction
                return;
            }
            mCurrentTransaction = mTransactions.poll();
        }
        mCurrentTransaction.start();
    }

    private void finishTransaction() {
        synchronized (sLock) {
            mCurrentTransaction = null;
        }
        startTransactions();
    }

    @VisibleForTesting
    public void clear() {
        List<VoipCallTransaction> pendingTransactions;
        synchronized (sLock) {
            pendingTransactions = new ArrayList<>(mTransactions);
        }
        for (VoipCallTransaction transaction : pendingTransactions) {
            transaction.finish();
        }
    }
}
