/*
 * Copyright Debezium Authors.
 * 
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mongodb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.Immutable;
import io.debezium.annotation.ThreadSafe;
import io.debezium.config.Configuration;
import io.debezium.util.Clock;
import io.debezium.util.LoggingContext.PreviousContext;
import io.debezium.util.Metronome;

/**
 * A Kafka Connect source task that replicates the changes from one or more MongoDB replica sets, using one {@link Replicator}
 * for each replica set.
 * <p>
 * Generally, the {@link MongoDbConnector} assigns each replica set to a separate task, although multiple
 * replica sets will be assigned to each task when the maximum number of tasks is limited. Regardless, every task will use a
 * separate thread to replicate the contents of each replica set, and each replication thread may use multiple threads
 * to perform an initial sync of the replica set.
 * 
 * @see MongoDbConnector
 * @see MongoDbConnectorConfig
 * @author Randall Hauch
 */
@ThreadSafe
public final class MongoDbConnectorTask extends SourceTask {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Deque<Replicator> replicators = new ConcurrentLinkedDeque<>();

    // These are all effectively constants between start(...) and stop(...)
    private volatile TaskRecordQueue queue;
    private volatile String taskName;
    private volatile ReplicationContext replContext;

    /**
     * Create an instance of the MongoDB task.
     */
    public MongoDbConnectorTask() {
    }

    @Override
    public String version() {
        return Module.version();
    }

    @Override
    public void start(Map<String, String> props) {
        if (!this.running.compareAndSet(false, true)) {
            // Already running ...
            return;
        }

        if (context == null) {
            throw new ConnectException("Unexpected null context");
        }
        

        // Read the configuration and set up the replication context ...
        final Configuration config = Configuration.from(props);
        this.taskName = "task" + config.getInteger(MongoDbConnectorConfig.TASK_ID);
        final ReplicationContext replicationContext = new ReplicationContext(config);
        this.replContext = replicationContext;
        PreviousContext previousLogContext = replicationContext.configureLoggingContext(taskName);

        try {
            // Output the configuration ...
            logger.info("Starting MongoDB connector task with configuration:");
            config.forEach((propName, propValue) -> {
                logger.info("   {} = {}", propName, propValue);
            });

            // The MongoDbConnector.taskConfigs created our configuration, but we still validate the configuration in case of bugs
            // ...
            if (!config.validate(MongoDbConnectorConfig.ALL_FIELDS, logger::error)) {
                throw new ConnectException(
                        "Error configuring an instance of " + getClass().getSimpleName() + "; check the logs for details");
            }

            // Read from the configuration the information about the replica sets we are to watch ...
            final String hosts = config.getString(MongoDbConnectorConfig.HOSTS);
            final ReplicaSets replicaSets = ReplicaSets.parse(hosts);

            // Set up the task record queue ...
            this.queue = new TaskRecordQueue(config, replicaSets.replicaSetCount(), running::get);

            // Get the offsets for each of replica set partition ...
            SourceInfo source = replicationContext.source();
            Collection<Map<String, String>> partitions = new ArrayList<>();
            replicaSets.onEachReplicaSet(replicaSet -> {
                String replicaSetName = replicaSet.replicaSetName();
                partitions.add(source.partition(replicaSetName));
            });
            context.offsetStorageReader().offsets(partitions).forEach(source::setOffsetFor);

            // Set up a replicator for each replica set ...
            final int numThreads = replicaSets.replicaSetCount();
            final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            AtomicInteger stillRunning = new AtomicInteger(numThreads);
            logger.info("Starting {} thread(s) to replicate replica sets: {}", numThreads, replicaSets);
            replicaSets.all().forEach(replicaSet -> {
                // Create a replicator for this replica set ...
                Replicator replicator = new Replicator(replicationContext, replicaSet, queue::enqueue);
                replicators.add(replicator);
                // and submit it for execution ...
                executor.submit(() -> {
                    try {
                        // Configure the logging to use the replica set name ...
                        replicationContext.configureLoggingContext(replicaSet.replicaSetName());
                        // Run the replicator, which should run forever until it is stopped ...
                        replicator.run();
                    } finally {
                        try {
                            replicators.remove(replicator);
                        } finally {
                            if (stillRunning.decrementAndGet() == 0) {
                                // we are the last one, so clean up ...
                                try {
                                    executor.shutdown();
                                } finally {
                                    replicationContext.shutdown();
                                }
                            }
                        }
                    }
                });
            });
            logger.info("Successfully started MongoDB connector task with {} thread(s) for replica sets {}", numThreads, replicaSets);
        } finally {
            previousLogContext.restore();
        }
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        return this.queue.poll();
    }

    @Override
    public void stop() {
        PreviousContext previousLogContext = this.replContext.configureLoggingContext(taskName);
        try {
            // Signal to the 'poll()' method that it should stop what its doing ...
            if (this.running.compareAndSet(true, false)) {
                logger.info("Stopping MongoDB task");
                // Stop all running replicators ...
                Replicator replicator = null;
                int counter = 0;
                while ((replicator = this.replicators.poll()) != null) {
                    replicator.stop();
                    ++counter;
                }
                logger.info("Stopped MongoDB replication task by stopping {} replicator threads", counter);
            }
        } catch (Throwable e) {
            logger.error("Unexpected error shutting down the MongoDB replication task", e);
        } finally {
            previousLogContext.restore();
        }
    }

    @Immutable
    protected static class TaskRecordQueue {
        // These are all effectively constants between start(...) and stop(...)
        private final int maxBatchSize;
        private final Metronome metronome;
        private final BlockingQueue<SourceRecord> records;
        private final BooleanSupplier isRunning;

        protected TaskRecordQueue(Configuration config, int numThreads, BooleanSupplier isRunning) {
            final int maxQueueSize = config.getInteger(MongoDbConnectorConfig.MAX_QUEUE_SIZE);
            final long pollIntervalMs = config.getLong(MongoDbConnectorConfig.POLL_INTERVAL_MS);
            maxBatchSize = config.getInteger(MongoDbConnectorConfig.MAX_BATCH_SIZE);
            metronome = Metronome.parker(pollIntervalMs, TimeUnit.MILLISECONDS, Clock.SYSTEM);
            records = new LinkedBlockingDeque<>(maxQueueSize);
            this.isRunning = isRunning;
        }

        public List<SourceRecord> poll() throws InterruptedException {
            List<SourceRecord> batch = new ArrayList<>(maxBatchSize);
            while (isRunning.getAsBoolean() && records.drainTo(batch, maxBatchSize) == 0) {
                // No events to process, so sleep for a bit ...
                metronome.pause();
            }
            return batch;
        }

        /**
         * Adds the event into the queue for subsequent batch processing.
         * 
         * @param record a record from the MongoDB oplog
         * @throws InterruptedException if the thread is interrupted while waiting to enqueue the record
         */
        public void enqueue(SourceRecord record) throws InterruptedException {
            if (record != null) {
                records.put(record);
            }
        }
    }
}
