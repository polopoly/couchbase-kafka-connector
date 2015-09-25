/**
 * Copyright (C) 2015 Couchbase, Inc.
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package com.couchbase.kafka;

import com.couchbase.client.core.ClusterFacade;
import com.couchbase.client.core.config.CouchbaseBucketConfig;
import com.couchbase.client.core.dcp.BucketStreamAggregator;
import com.couchbase.client.core.dcp.BucketStreamAggregatorState;
import com.couchbase.client.core.dcp.BucketStreamState;
import com.couchbase.client.core.dcp.BucketStreamStateUpdatedEvent;
import com.couchbase.client.core.message.CouchbaseMessage;
import com.couchbase.client.core.message.cluster.GetClusterConfigRequest;
import com.couchbase.client.core.message.cluster.GetClusterConfigResponse;
import com.couchbase.client.core.message.cluster.OpenBucketRequest;
import com.couchbase.client.core.message.cluster.SeedNodesRequest;
import com.couchbase.client.core.message.dcp.DCPRequest;
import com.couchbase.client.core.message.dcp.FailoverLogEntry;
import com.couchbase.client.core.message.dcp.SnapshotMarkerMessage;
import com.couchbase.client.core.message.dcp.StreamRequestResponse;
import com.couchbase.client.deps.com.lmax.disruptor.EventTranslatorOneArg;
import com.couchbase.client.deps.com.lmax.disruptor.RingBuffer;
import com.couchbase.kafka.state.RunMode;
import com.couchbase.kafka.state.StateSerializer;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link CouchbaseReader} is in charge of accepting events from Couchbase.
 *
 * @author Sergey Avseyev
 */
public class CouchbaseReader {
    private static final EventTranslatorOneArg<DCPEvent, CouchbaseMessage> TRANSLATOR =
            new EventTranslatorOneArg<DCPEvent, CouchbaseMessage>() {
                @Override
                public void translateTo(final DCPEvent event, final long sequence, final CouchbaseMessage message) {
                    event.setMessage(message);
                }
            };
    private final ClusterFacade core;
    private final RingBuffer<DCPEvent> dcpRingBuffer;
    private final List<String> nodes;
    private final String bucket;
    private final String streamName;
    private final String password;
    private final BucketStreamAggregator streamAggregator;
    private final StateSerializer stateSerializer;
    private int numberOfPartitions;


    /**
     * Creates a new {@link KafkaWriter}.
     *
     * @param core            the core reference.
     * @param environment     the environment object, which carries settings.
     * @param dcpRingBuffer   the buffer where to publish new events.
     * @param stateSerializer the object to serialize the state of DCP streams.
     */
    public CouchbaseReader(final ClusterFacade core, final CouchbaseKafkaEnvironment environment,
                           final RingBuffer<DCPEvent> dcpRingBuffer, final StateSerializer stateSerializer) {
        this.core = core;
        this.dcpRingBuffer = dcpRingBuffer;
        this.nodes = environment.couchbaseNodes();
        this.bucket = environment.couchbaseBucket();
        this.password = environment.couchbasePassword();
        this.streamAggregator = new BucketStreamAggregator(core, bucket);
        this.stateSerializer = stateSerializer;
        this.streamName = "CouchbaseKafka(" + this.hashCode() + ")";
    }

    /**
     * Performs connection with 2 seconds timeout.
     */
    public void connect() {
        connect(2, TimeUnit.SECONDS);
    }

    /**
     * Performs connection with arbitrary timeout
     *
     * @param timeout  the custom timeout.
     * @param timeUnit the unit for the timeout.
     */
    public void connect(final long timeout, final TimeUnit timeUnit) {
        core.send(new SeedNodesRequest(nodes))
                .timeout(timeout, timeUnit)
                .toBlocking()
                .single();
        core.send(new OpenBucketRequest(bucket, password))
                .timeout(timeout, timeUnit)
                .toBlocking()
                .single();
        numberOfPartitions = core.<GetClusterConfigResponse>send(new GetClusterConfigRequest())
                .map(new Func1<GetClusterConfigResponse, Integer>() {
                    @Override
                    public Integer call(GetClusterConfigResponse response) {
                        CouchbaseBucketConfig config = (CouchbaseBucketConfig) response.config().bucketConfig(bucket);
                        return config.numberOfPartitions();
                    }
                })
                .timeout(timeout, timeUnit)
                .toBlocking()
                .single();
    }

    /**
     * Continue from the state where the stream was left.
     */
    public void run() {
        run(new BucketStreamAggregatorState(numberOfPartitions), RunMode.LOAD_AND_RESUME);
    }

    /**
     * Run with specified mode.
     *
     * @param mode running mode. See {@link RunMode} for details.
     */
    public void run(RunMode mode) {
        run(new BucketStreamAggregatorState(numberOfPartitions), mode);
    }

    /**
     * Executes worker reading loop, which relays events from Couchbase to Kafka.
     */
    public void run(final BucketStreamAggregatorState state, RunMode mode) {
        if (mode == RunMode.LOAD_AND_RESUME) {
            stateSerializer.load(state);
        }
        state.updates().subscribe(
                new Action1<BucketStreamStateUpdatedEvent>() {
                    @Override
                    public void call(BucketStreamStateUpdatedEvent event) {
                        if (event.partialUpdate()) {
                            stateSerializer.dump(event.aggregatorState(), event.partition());
                        } else {
                            stateSerializer.dump(event.aggregatorState());
                        }
                    }
                });
        streamAggregator.open(streamName, state)
                .flatMap(new Func1<StreamRequestResponse, Observable<DCPRequest>>() {
                    @Override
                    public Observable<DCPRequest> call(StreamRequestResponse response) {
                        final BucketStreamState initialState = state.get(response.partition());
                        FailoverLogEntry mostRecentEntry = null;
                        for (FailoverLogEntry failoverLogEntry : response.failoverLog()) {
                            if (mostRecentEntry == null || failoverLogEntry.sequenceNumber() > mostRecentEntry.sequenceNumber()) {
                                mostRecentEntry = failoverLogEntry;
                            }
                        }
                        final BucketStreamState newState = new BucketStreamState(
                                mostRecentEntry == null ? initialState.vbucketUUID() : mostRecentEntry.vbucketUUID(),
                                mostRecentEntry == null ? initialState.startSequenceNumber() : mostRecentEntry.sequenceNumber(),
                                initialState.endSequenceNumber(),
                                initialState.snapshotStartSequenceNumber(),
                                initialState.snapshotEndSequenceNumber());
                        state.set(response.partition(), newState, false);
                        return response.stream();
                    }
                })
                .toBlocking()
                .forEach(new Action1<DCPRequest>() {
                    @Override
                    public void call(final DCPRequest dcpRequest) {
                        if (dcpRequest instanceof SnapshotMarkerMessage) {
                            SnapshotMarkerMessage snapshotMarker = (SnapshotMarkerMessage) dcpRequest;
                            final BucketStreamState oldState = state.get(snapshotMarker.partition());
                            BucketStreamState newState = new BucketStreamState(
                                    oldState.vbucketUUID(),
                                    snapshotMarker.endSequenceNumber(),
                                    oldState.endSequenceNumber(),
                                    snapshotMarker.endSequenceNumber(),
                                    oldState.snapshotEndSequenceNumber());
                            state.set(snapshotMarker.partition(), newState);
                        } else {
                            dcpRingBuffer.tryPublishEvent(TRANSLATOR, dcpRequest);
                        }
                    }
                });
    }
}
