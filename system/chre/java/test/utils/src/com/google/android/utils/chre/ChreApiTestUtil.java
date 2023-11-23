/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.utils.chre;

import androidx.annotation.NonNull;

import com.google.android.chre.utils.pigweed.ChreRpcClient;
import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import dev.chre.rpc.proto.ChreApiTest;
import dev.pigweed.pw_rpc.Call.ServerStreamingFuture;
import dev.pigweed.pw_rpc.Call.UnaryFuture;
import dev.pigweed.pw_rpc.MethodClient;
import dev.pigweed.pw_rpc.Service;
import dev.pigweed.pw_rpc.UnaryResult;

/**
 * A set of helper functions for tests that use the CHRE API Test nanoapp.
 */
public class ChreApiTestUtil {
    /**
     * The default timeout for an RPC call in seconds.
     */
    public static final int RPC_TIMEOUT_IN_SECONDS = 5;

    /**
     * The default timeout for an RPC call in milliseconds.
     */
    public static final int RPC_TIMEOUT_IN_MS = RPC_TIMEOUT_IN_SECONDS * 1000;

    /**
     * The number of threads for the executor that executes the futures.
     * We need at least 2 here. One to process the RPCs for server streaming
     * and one to process events (which has server streaming as a dependent).
     * 2 is the minimum needed to run smoothly without timeout issues.
     */
    private static final int NUM_THREADS_FOR_EXECUTOR = 2;

    /**
     * Executor for use with server streaming RPCs.
     */
    private final ExecutorService mExecutor =
            Executors.newFixedThreadPool(NUM_THREADS_FOR_EXECUTOR);

    /**
     * Storage for nanoapp streaming messages. This is a map from each RPC client to the
     * list of messages received.
     */
    private final Map<ChreRpcClient, List<MessageLite>> mNanoappStreamingMessages =
            new HashMap<ChreRpcClient, List<MessageLite>>();

    /**
     * If true, there is an active server streaming RPC ongoing.
     */
    private boolean mActiveServerStreamingRpc = false;

    /**
     * Calls a server streaming RPC method on multiple RPC clients. The RPC will be initiated for
     * each client, then we will give each client a maximum of RPC_TIMEOUT_IN_SECONDS seconds of
     * timeout, getting the futures in sequential order. The responses will have the same size
     * as the input rpcClients size.
     *
     * @param <RequestType>   the type of the request (proto generated type).
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClients      the RPC clients.
     * @param method          the fully-qualified method name.
     * @param request         the request.
     *
     * @return                the proto responses or null if there was an error.
     */
    public <RequestType extends MessageLite, ResponseType extends MessageLite>
            List<List<ResponseType>> callConcurrentServerStreamingRpcMethodSync(
                    @NonNull List<ChreRpcClient> rpcClients,
                    @NonNull String method,
                    @NonNull RequestType request) throws Exception {
        Objects.requireNonNull(rpcClients);
        Objects.requireNonNull(method);
        Objects.requireNonNull(request);

        Future<List<List<ResponseType>>> responseFuture =
                callConcurrentServerStreamingRpcMethodAsync(rpcClients, method, request,
                        RPC_TIMEOUT_IN_MS);
        return responseFuture == null
                ? null
                : responseFuture.get(RPC_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Calls a server streaming RPC method with RPC_TIMEOUT_IN_SECONDS seconds of timeout.
     *
     * @param <RequestType>   the type of the request (proto generated type).
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClient       the RPC client.
     * @param method          the fully-qualified method name.
     * @param request         the request.
     *
     * @return                the proto response or null if there was an error.
     */
    public <RequestType extends MessageLite, ResponseType extends MessageLite> List<ResponseType>
            callServerStreamingRpcMethodSync(
                    @NonNull ChreRpcClient rpcClient,
                    @NonNull String method,
                    @NonNull RequestType request) throws Exception {
        Objects.requireNonNull(rpcClient);
        Objects.requireNonNull(method);
        Objects.requireNonNull(request);

        List<List<ResponseType>> responses = callConcurrentServerStreamingRpcMethodSync(
                Arrays.asList(rpcClient),
                method,
                request);
        return responses == null || responses.isEmpty() ? null : responses.get(0);
    }

    /**
     * Calls a server streaming RPC method with RPC_TIMEOUT_IN_SECONDS seconds of
     * timeout with a void request.
     *
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClient       the RPC client.
     * @param method          the fully-qualified method name.
     *
     * @return                the proto response or null if there was an error.
     */
    public <ResponseType extends MessageLite> List<ResponseType>
            callServerStreamingRpcMethodSync(
                    @NonNull ChreRpcClient rpcClient,
                    @NonNull String method) throws Exception {
        Objects.requireNonNull(rpcClient);
        Objects.requireNonNull(method);

        ChreApiTest.Void request = ChreApiTest.Void.newBuilder().build();
        return callServerStreamingRpcMethodSync(rpcClient, method, request);
    }

    /**
     * Calls an RPC method with RPC_TIMEOUT_IN_SECONDS seconds of timeout for concurrent
     * instances of the ChreApiTest nanoapp.
     *
     * @param <RequestType>   the type of the request (proto generated type).
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClients      the RPC clients corresponding to the instances of the
     *                        ChreApiTest nanoapp.
     * @param method          the fully-qualified method name.
     * @param requests        the list of requests.
     *
     * @return                the proto response or null if there was an error.
     */
    public static <RequestType extends MessageLite, ResponseType extends MessageLite>
            List<ResponseType> callConcurrentUnaryRpcMethodSync(
                    @NonNull List<ChreRpcClient> rpcClients,
                    @NonNull String method,
                    @NonNull List<RequestType> requests) throws Exception {
        Objects.requireNonNull(rpcClients);
        Objects.requireNonNull(method);
        Objects.requireNonNull(requests);
        if (rpcClients.size() != requests.size()) {
            return null;
        }

        List<UnaryFuture<ResponseType>> responseFutures =
                new ArrayList<UnaryFuture<ResponseType>>();
        Iterator<ChreRpcClient> rpcClientsIter = rpcClients.iterator();
        Iterator<RequestType> requestsIter = requests.iterator();
        while (rpcClientsIter.hasNext() && requestsIter.hasNext()) {
            ChreRpcClient rpcClient = rpcClientsIter.next();
            RequestType request = requestsIter.next();
            MethodClient methodClient = rpcClient.getMethodClient(method);
            responseFutures.add(methodClient.invokeUnaryFuture(request));
        }

        List<ResponseType> responses = new ArrayList<ResponseType>();
        boolean success = true;
        long endTimeInMs = System.currentTimeMillis() + RPC_TIMEOUT_IN_MS;
        for (UnaryFuture<ResponseType> responseFuture: responseFutures) {
            try {
                UnaryResult<ResponseType> responseResult = responseFuture.get(
                        Math.max(0, endTimeInMs - System.currentTimeMillis()),
                                TimeUnit.MILLISECONDS);
                responses.add(responseResult.response());
            } catch (Exception exception) {
                success = false;
            }
        }
        return success ? responses : null;
    }

    /**
     * Calls an RPC method with RPC_TIMEOUT_IN_SECONDS seconds of timeout for concurrent
     * instances of the ChreApiTest nanoapp.
     *
     * @param <RequestType>   the type of the request (proto generated type).
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClients      the RPC clients corresponding to the instances of the
     *                        ChreApiTest nanoapp.
     * @param method          the fully-qualified method name.
     * @param request         the request.
     *
     * @return                the proto response or null if there was an error.
     */
    public static <RequestType extends MessageLite, ResponseType extends MessageLite>
            List<ResponseType> callConcurrentUnaryRpcMethodSync(
                    @NonNull List<ChreRpcClient> rpcClients,
                    @NonNull String method,
                    @NonNull RequestType request) throws Exception {
        Objects.requireNonNull(rpcClients);
        Objects.requireNonNull(method);
        Objects.requireNonNull(request);

        List<RequestType> requests = new ArrayList<RequestType>();
        for (int i = 0; i < rpcClients.size(); ++i) {
            requests.add(request);
        }
        return callConcurrentUnaryRpcMethodSync(rpcClients, method, requests);
    }

    /**
     * Calls an RPC method with RPC_TIMEOUT_IN_SECONDS seconds of timeout.
     *
     * @param <RequestType>   the type of the request (proto generated type).
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClient       the RPC client.
     * @param method          the fully-qualified method name.
     * @param request         the request.
     *
     * @return                the proto response or null if there was an error.
     */
    public static <RequestType extends MessageLite, ResponseType extends MessageLite> ResponseType
            callUnaryRpcMethodSync(
                    @NonNull ChreRpcClient rpcClient,
                    @NonNull String method,
                    @NonNull RequestType request) throws Exception {
        Objects.requireNonNull(rpcClient);
        Objects.requireNonNull(method);
        Objects.requireNonNull(request);

        List<ResponseType> responses = callConcurrentUnaryRpcMethodSync(Arrays.asList(rpcClient),
                method, request);
        return responses == null || responses.isEmpty() ? null : responses.get(0);
    }

    /**
     * Calls an RPC method with RPC_TIMEOUT_IN_SECONDS seconds of timeout with a void request.
     *
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClient       the RPC client.
     * @param method          the fully-qualified method name.
     *
     * @return                the proto response or null if there was an error.
     */
    public static <ResponseType extends MessageLite> ResponseType
            callUnaryRpcMethodSync(@NonNull ChreRpcClient rpcClient, @NonNull String method)
            throws Exception {
        Objects.requireNonNull(rpcClient);
        Objects.requireNonNull(method);

        ChreApiTest.Void request = ChreApiTest.Void.newBuilder().build();
        return callUnaryRpcMethodSync(rpcClient, method, request);
    }

    /**
     * Gathers events that match the eventTypes for each RPC client. This gathers
     * events until eventCount events are gathered or timeoutInNs nanoseconds has passed.
     * The host will wait until 2 * timeoutInNs to timeout receiving the response.
     * The responses will have the same size as the input rpcClients size.
     *
     * @param rpcClients      the RPC clients.
     * @param eventTypes      the types of event to gather.
     * @param eventCount      the number of events to gather.
     *
     * @return                the events future.
     */
    public Future<List<List<ChreApiTest.GeneralEventsMessage>>> gatherEventsConcurrent(
            @NonNull List<ChreRpcClient> rpcClients, List<Integer> eventTypes, int eventCount,
            long timeoutInNs) throws Exception {
        Objects.requireNonNull(rpcClients);

        ChreApiTest.GatherEventsInput input = ChreApiTest.GatherEventsInput.newBuilder()
                .addAllEventTypes(eventTypes)
                .setEventTypeCount(eventTypes.size())
                .setEventCount(eventCount)
                .setTimeoutInNs(timeoutInNs)
                .build();
        return callConcurrentServerStreamingRpcMethodAsync(rpcClients,
                "chre.rpc.ChreApiTestService.GatherEvents", input,
                TimeUnit.NANOSECONDS.toMillis(2 * timeoutInNs));
    }

    /**
     * Gathers events that match the eventType for each RPC client. This gathers
     * events until eventCount events are gathered or timeoutInNs nanoseconds has passed.
     * The host will wait until 2 * timeoutInNs to timeout receiving the response.
     * The responses will have the same size as the input rpcClients size.
     *
     * @param rpcClients      the RPC clients.
     * @param eventType       the type of event to gather.
     * @param eventCount      the number of events to gather.
     *
     * @return                the events future.
     */
    public Future<List<List<ChreApiTest.GeneralEventsMessage>>> gatherEventsConcurrent(
            @NonNull List<ChreRpcClient> rpcClients, int eventType, int eventCount,
            long timeoutInNs) throws Exception {
        Objects.requireNonNull(rpcClients);

        return gatherEventsConcurrent(rpcClients, Arrays.asList(eventType),
                eventCount, timeoutInNs);
    }

    /**
     * Gathers events that match the eventTypes for the RPC client. This gathers
     * events until eventCount events are gathered or timeoutInNs nanoseconds has passed.
     * The host will wait until 2 * timeoutInNs to timeout receiving the response.
     *
     * @param rpcClient       the RPC client.
     * @param eventTypes      the types of event to gather.
     * @param eventCount      the number of events to gather.
     *
     * @return                the events future.
     */
    public Future<List<ChreApiTest.GeneralEventsMessage>> gatherEvents(
            @NonNull ChreRpcClient rpcClient, List<Integer> eventTypes, int eventCount,
                    long timeoutInNs) throws Exception {
        Objects.requireNonNull(rpcClient);

        Future<List<List<ChreApiTest.GeneralEventsMessage>>> eventsConcurrentFuture =
                gatherEventsConcurrent(Arrays.asList(rpcClient), eventTypes, eventCount,
                        timeoutInNs);
        return eventsConcurrentFuture == null ? null : mExecutor.submit(() -> {
            List<List<ChreApiTest.GeneralEventsMessage>> events =
                    eventsConcurrentFuture.get(2 * timeoutInNs, TimeUnit.NANOSECONDS);
            return events == null || events.size() == 0 ? null : events.get(0);
        });
    }

    /**
     * Gathers events that match the eventType for the RPC client. This gathers
     * events until eventCount events are gathered or timeoutInNs nanoseconds has passed.
     * The host will wait until 2 * timeoutInNs to timeout receiving the response.
     *
     * @param rpcClient       the RPC client.
     * @param eventType       the type of event to gather.
     * @param eventCount      the number of events to gather.
     *
     * @return                the events future.
     */
    public Future<List<ChreApiTest.GeneralEventsMessage>> gatherEvents(
            @NonNull ChreRpcClient rpcClient, int eventType, int eventCount,
                    long timeoutInNs) throws Exception {
        Objects.requireNonNull(rpcClient);

        return gatherEvents(rpcClient, Arrays.asList(eventType), eventCount, timeoutInNs);
    }

    /**
     * Gets the RPC service for the CHRE API Test nanoapp.
     */
    public static Service getChreApiService() {
        return new Service("chre.rpc.ChreApiTestService",
                Service.unaryMethod(
                        "ChreBleGetCapabilities",
                        ChreApiTest.Void.class,
                        ChreApiTest.Capabilities.class),
                Service.unaryMethod(
                        "ChreBleGetFilterCapabilities",
                        ChreApiTest.Void.class,
                        ChreApiTest.Capabilities.class),
                Service.unaryMethod(
                        "ChreBleStartScanAsync",
                        ChreApiTest.ChreBleStartScanAsyncInput.class,
                        ChreApiTest.Status.class),
                Service.serverStreamingMethod(
                        "ChreBleStartScanSync",
                        ChreApiTest.ChreBleStartScanAsyncInput.class,
                        ChreApiTest.GeneralSyncMessage.class),
                Service.unaryMethod(
                        "ChreBleStopScanAsync",
                        ChreApiTest.Void.class,
                        ChreApiTest.Status.class),
                Service.serverStreamingMethod(
                        "ChreBleStopScanSync",
                        ChreApiTest.Void.class,
                        ChreApiTest.GeneralSyncMessage.class),
                Service.unaryMethod(
                        "ChreSensorFindDefault",
                        ChreApiTest.ChreSensorFindDefaultInput.class,
                        ChreApiTest.ChreSensorFindDefaultOutput.class),
                Service.unaryMethod(
                        "ChreGetSensorInfo",
                        ChreApiTest.ChreHandleInput.class,
                        ChreApiTest.ChreGetSensorInfoOutput.class),
                Service.unaryMethod(
                        "ChreGetSensorSamplingStatus",
                        ChreApiTest.ChreHandleInput.class,
                        ChreApiTest.ChreGetSensorSamplingStatusOutput.class),
                Service.unaryMethod(
                        "ChreSensorConfigure",
                        ChreApiTest.ChreSensorConfigureInput.class,
                        ChreApiTest.Status.class),
                Service.unaryMethod(
                        "ChreSensorConfigureModeOnly",
                        ChreApiTest.ChreSensorConfigureModeOnlyInput.class,
                        ChreApiTest.Status.class),
                Service.unaryMethod(
                        "ChreAudioGetSource",
                        ChreApiTest.ChreHandleInput.class,
                        ChreApiTest.ChreAudioGetSourceOutput.class),
                Service.unaryMethod(
                        "ChreConfigureHostEndpointNotifications",
                        ChreApiTest.ChreConfigureHostEndpointNotificationsInput.class,
                        ChreApiTest.Status.class),
                Service.unaryMethod(
                        "RetrieveLatestDisconnectedHostEndpointEvent",
                        ChreApiTest.Void.class,
                        ChreApiTest.RetrieveLatestDisconnectedHostEndpointEventOutput
                                .class),
                Service.unaryMethod(
                        "ChreGetHostEndpointInfo",
                        ChreApiTest.ChreGetHostEndpointInfoInput.class,
                        ChreApiTest.ChreGetHostEndpointInfoOutput.class),
                Service.serverStreamingMethod(
                        "GatherEvents",
                        ChreApiTest.GatherEventsInput.class,
                        ChreApiTest.GeneralEventsMessage.class));
    }

    /**
     * Calls a server streaming RPC method with timeoutInMs milliseconds of timeout on
     * multiple RPC clients. This returns a Future for the result. The responses will have the same
     * size as the input rpcClients size.
     *
     * @param <RequestType>   the type of the request (proto generated type).
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClients      the RPC clients.
     * @param method          the fully-qualified method name.
     * @param requests        the list of requests.
     * @param timeoutInMs     the timeout in milliseconds.
     *
     * @return                the Future for the response for null if there was an error.
     */
    private <RequestType extends MessageLite, ResponseType extends MessageLite>
            Future<List<List<ResponseType>>> callConcurrentServerStreamingRpcMethodAsync(
                    @NonNull List<ChreRpcClient> rpcClients,
                    @NonNull String method,
                    @NonNull List<RequestType> requests,
                    long timeoutInMs) throws Exception {
        Objects.requireNonNull(rpcClients);
        Objects.requireNonNull(method);
        Objects.requireNonNull(requests);
        if (rpcClients.size() != requests.size()) {
            return null;
        }

        List<ServerStreamingFuture> responseFutures = new ArrayList<ServerStreamingFuture>();
        synchronized (mNanoappStreamingMessages) {
            if (mActiveServerStreamingRpc) {
                return null;
            }

            Iterator<ChreRpcClient> rpcClientsIter = rpcClients.iterator();
            Iterator<RequestType> requestsIter = requests.iterator();
            while (rpcClientsIter.hasNext() && requestsIter.hasNext()) {
                ChreRpcClient rpcClient = rpcClientsIter.next();
                RequestType request = requestsIter.next();
                MethodClient methodClient = rpcClient.getMethodClient(method);
                ServerStreamingFuture responseFuture = methodClient.invokeServerStreamingFuture(
                        request,
                        (ResponseType response) -> {
                            synchronized (mNanoappStreamingMessages) {
                                mNanoappStreamingMessages.putIfAbsent(rpcClient,
                                        new ArrayList<MessageLite>());
                                mNanoappStreamingMessages.get(rpcClient).add(response);
                            }
                        });
                responseFutures.add(responseFuture);
            }
            mActiveServerStreamingRpc = true;
        }

        final List<ChreRpcClient> rpcClientsFinal = rpcClients;
        Future<List<List<ResponseType>>> responseFuture = mExecutor.submit(() -> {
            boolean success = true;
            long endTimeInMs = System.currentTimeMillis() + timeoutInMs;
            for (ServerStreamingFuture future: responseFutures) {
                try {
                    future.get(Math.max(0, endTimeInMs - System.currentTimeMillis()),
                            TimeUnit.MILLISECONDS);
                } catch (Exception exception) {
                    success = false;
                }
            }

            synchronized (mNanoappStreamingMessages) {
                List<List<ResponseType>> responses = null;
                if (success) {
                    responses = new ArrayList<List<ResponseType>>();
                    for (ChreRpcClient rpcClient: rpcClientsFinal) {
                        List<MessageLite> messages = mNanoappStreamingMessages.get(rpcClient);
                        List<ResponseType> responseList = new ArrayList<ResponseType>();
                        if (messages != null) {
                            // Only needed to cast the type.
                            for (MessageLite message: messages) {
                                responseList.add((ResponseType) message);
                            }
                        }

                        responses.add(responseList);
                    }
                }

                mNanoappStreamingMessages.clear();
                mActiveServerStreamingRpc = false;
                return responses;
            }
        });
        return responseFuture;
    }

    /**
     * Calls a server streaming RPC method with timeoutInMs milliseconds of timeout on
     * multiple RPC clients. This returns a Future for the result. The responses will have the same
     * size as the input rpcClients size.
     *
     * @param <RequestType>   the type of the request (proto generated type).
     * @param <ResponseType>  the type of the response (proto generated type).
     * @param rpcClients      the RPC clients.
     * @param method          the fully-qualified method name.
     * @param request         the request.
     * @param timeoutInMs     the timeout in milliseconds.
     *
     * @return                the Future for the response for null if there was an error.
     */
    private <RequestType extends MessageLite, ResponseType extends MessageLite>
            Future<List<List<ResponseType>>> callConcurrentServerStreamingRpcMethodAsync(
                    @NonNull List<ChreRpcClient> rpcClients,
                    @NonNull String method,
                    @NonNull RequestType request,
                    long timeoutInMs) throws Exception {
        Objects.requireNonNull(rpcClients);
        Objects.requireNonNull(method);
        Objects.requireNonNull(request);

        ArrayList<RequestType> requests = new ArrayList<RequestType>();
        for (int i = 0; i < rpcClients.size(); ++i) {
            requests.add(request);
        }
        return callConcurrentServerStreamingRpcMethodAsync(rpcClients, method,
                requests, timeoutInMs);
    }
}
