package com.netflix.conductor.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.inject.AbstractModule;
import com.netflix.conductor.common.utils.JsonMapperProvider;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.execution.TestConfiguration;
import com.netflix.conductor.dao.ExecutionDAO;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.dao.mysql.MySQLExecutionDAO;
import com.netflix.conductor.dao.mysql.MySQLQueueDAO;
import com.netflix.conductor.mysql.MySQLConfiguration;
import com.netflix.conductor.mysql.MySQLDataSourceProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PerformanceTest {

    public static final int MSGS = 5000;
    public static final int PRODUCER_BATCH = 10; // make sure MSGS % PRODUCER_BATCH == 0
    public static final int PRODUCERS = 4;
    public static final int WORKERS = 4;
    public static final int OBSERVERS = 1;
    public static final int OBSERVER_DELAY = 500;
    public static final int UNACK_RUNNERS = 0;
    public static final int UNACK_DELAY = 500;
    public static final int WORKER_BATCH = 10;
    public static final int WORKER_BATCH_TIMEOUT = 0;
    public static final int COMPLETION_MONITOR_DELAY = 1000;

    private MySQLConfiguration configuration;
    private DataSource dataSource;
    private QueueDAO Q;
    private ExecutionDAO E;

    private ExecutorService THREADPOOL = Executors.newFixedThreadPool(PRODUCERS + WORKERS + OBSERVERS + UNACK_RUNNERS);
    private static final Logger logger = LoggerFactory.getLogger(PerformanceTest.class);

    @Before
    public void setUp() {
        TestConfiguration testConfiguration = new TestConfiguration();
        configuration = new TestMySQLConfiguration(testConfiguration,
                "jdbc:mysql://root:root@localhost:3306/conductor?charset=utf8&parseTime=true&interpolateParams=true",
                10, 2);
        MySQLDataSourceProvider mySQLDataSourceProvider = new MySQLDataSourceProvider(configuration);
        dataSource = mySQLDataSourceProvider.get();
        flywayMigrate(dataSource);
        resetAllData(dataSource);

        final ObjectMapper objectMapper = new JsonMapperProvider().get();
        Q = new MySQLQueueDAO(objectMapper, dataSource);
        E = new MySQLExecutionDAO(objectMapper, dataSource);
    }

    @After
    public void tearDown() throws Exception {
        resetAllData(dataSource);
    }

    public static final String QUEUE = "task_queue";

    @Test
    public void testQueueDaoPerformance() throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean(false);
        Stopwatch start = Stopwatch.createStarted();
        AtomicInteger poppedCoutner = new AtomicInteger(0);
        HashMultiset<String> allPopped = HashMultiset.create();

        // Consumers - workers
        for (int i = 0; i < WORKERS; i++) {
            THREADPOOL.submit(() -> {
                while (!stop.get()) {
                    List<Message> pop = Q.pollMessages(QUEUE, WORKER_BATCH, WORKER_BATCH_TIMEOUT);
                    logger.info("Popped {} messages", pop.size());
                    poppedCoutner.accumulateAndGet(pop.size(), Integer::sum);

                    if (pop.size() == 0) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        logger.info("Popped {}", pop.stream().map(Message::getId).collect(Collectors.toList()));
                    }

                    pop.forEach(popped -> {
                        synchronized (allPopped) {
                            allPopped.add(popped.getId());
                        }
                        boolean exists = Q.exists(QUEUE, popped.getId());
                        boolean ack = Q.ack(QUEUE, popped.getId());

                        if (ack && exists) {
                            // OK
                        } else {
                            logger.error("Exists & Ack did not succeed for msg: {}", popped);
                        }
                    });
                }
            });
        }

        // Producers
        List<Future<?>> producers = Lists.newArrayList();
        for (int i = 0; i < PRODUCERS; i++) {
            Future<?> producer = THREADPOOL.submit(() -> {
                // N messages
                for (int j = 0; j < MSGS / PRODUCER_BATCH; j++) {
                    List<Message> randomMessages = getRandomMessages(PRODUCER_BATCH);
                    Q.push(QUEUE, randomMessages);
                    logger.info("Pushed {} messages", PRODUCER_BATCH);
                    logger.info("Pushed {}", randomMessages.stream().map(Message::getId).collect(Collectors.toList()));
                }
                logger.info("Pushed ALL");
            });

            producers.add(producer);
        }

        // Observers
        for (int i = 0; i < OBSERVERS; i++) {
            THREADPOOL.submit(() -> {
                while (!stop.get()) {
                    try {
                        int size = Q.getSize(QUEUE);
                        Q.queuesDetail();
                        logger.info("Size   {} messages", size);
                    } catch (Exception e) {
                        logger.info("Queue size failed, nevermind");
                    }

                    try {
                        Thread.sleep(OBSERVER_DELAY);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        // Consumers - unack processor
        for (int i = 0; i < UNACK_RUNNERS; i++) {
            THREADPOOL.submit(() -> {
                while (!stop.get()) {
                    try {
                        Q.processUnacks(QUEUE);
                    } catch (Exception e) {
                        logger.info("Unack failed, nevermind");
                        continue;
                    }
                    logger.info("Unacked");
                    try {
                        Thread.sleep(UNACK_DELAY);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        long elapsed;
        while (true) {
            try {
                Thread.sleep(COMPLETION_MONITOR_DELAY);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            int size = Q.getSize(QUEUE);
            logger.info("MONITOR SIZE : {}", size);

            if (size == 0 && producers.stream().map(Future::isDone).reduce(true, (b1, b2) -> b1 && b2)) {
                elapsed = start.elapsed(TimeUnit.MILLISECONDS);
                stop.set(true);
                break;
            }
        }

        THREADPOOL.awaitTermination(10, TimeUnit.SECONDS);
        THREADPOOL.shutdown();
        logger.info("Finished in {} ms", elapsed);
        logger.info("Throughput {} msgs/second", ((MSGS * PRODUCERS) / (elapsed * 1.0)) * 1000);
        logger.info("Threads finished");
        if (poppedCoutner.get() != MSGS * PRODUCERS) {
            synchronized (allPopped) {
                List<String> duplicates = allPopped.entrySet().stream()
                        .filter(stringEntry -> stringEntry.getCount() > 1)
                        .map(stringEntry -> stringEntry.getElement() + ": " + stringEntry.getCount())
                        .collect(Collectors.toList());

                logger.error("Found duplicate pops: " + duplicates);
            }
            throw new RuntimeException("Popped " + poppedCoutner.get() + " != produced: " + MSGS * PRODUCERS);
        }
    }

    @Test
    public void testExecDaoPerformance() {
        E.createTasks()
    }

    private List<Message> getRandomMessages(int i) {
        String timestamp = Long.toString(System.nanoTime());
        return IntStream.range(0, i).mapToObj(j -> {
            String id = Thread.currentThread().getId() + "_" + timestamp + "_" + j;
            return new Message(id, "{ \"a\": \"b\", \"timestamp\": \" " + timestamp + " \"}", "receipt");
        }).collect(Collectors.toList());
    }

    private void flywayMigrate(DataSource dataSource) {
        Flyway flyway = new Flyway();
        flyway.setDataSource(dataSource);
        flyway.setPlaceholderReplacement(false);
        try {
            flyway.migrate();
        } catch (FlywayException e) {
            if (e.getMessage().contains("non-empty")) {
                return;
            }
            throw e;
        }
    }

    public void resetAllData(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet rs = connection.prepareStatement("SHOW TABLES").executeQuery();
                 PreparedStatement keysOn = connection.prepareStatement("SET FOREIGN_KEY_CHECKS=1")) {
                try (PreparedStatement keysOff = connection.prepareStatement("SET FOREIGN_KEY_CHECKS=0")) {
                    keysOff.execute();
                    while (rs.next()) {
                        String table = rs.getString(1);
                        try (PreparedStatement ps = connection.prepareStatement("TRUNCATE TABLE " + table)) {
                            ps.execute();
                        }
                    }
                } finally {
                    keysOn.execute();
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static class TestMySQLConfiguration implements MySQLConfiguration {
        private final TestConfiguration testConfiguration;
        private final String jdbcUrl;
        private final int connectionMax;
        private final int connecionIdle;

        public TestMySQLConfiguration(TestConfiguration testConfiguration, String jdbcUrl, int maxConns, int connecionIdle) {
            this.jdbcUrl = jdbcUrl;
            this.testConfiguration = testConfiguration;
            this.connectionMax = maxConns;
            this.connecionIdle = connecionIdle;
        }

        @Override
        public String getJdbcUrl() {
            return jdbcUrl;
        }

        @Override
        public String getTransactionIsolationLevel() {
            return "TRANSACTION_REPEATABLE_READ";
//            return "TRANSACTION_SERIALIZABLE";
        }

        @Override
        public int getConnectionPoolMaxSize() {
            return connectionMax;
        }

        @Override
        public int getConnectionPoolMinIdle() {
            return connecionIdle;
        }

        @Override
        public int getSweepFrequency() {
            return testConfiguration.getSweepFrequency();
        }

        @Override
        public boolean disableSweep() {
            return testConfiguration.disableSweep();
        }

        @Override
        public boolean disableAsyncWorkers() {
            return testConfiguration.disableAsyncWorkers();
        }

        @Override
        public String getServerId() {
            return testConfiguration.getServerId();
        }

        @Override
        public String getEnvironment() {
            return testConfiguration.getEnvironment();
        }

        @Override
        public String getStack() {
            return testConfiguration.getStack();
        }

        @Override
        public String getAppId() {
            return testConfiguration.getAppId();
        }

        @Override
        public boolean enableAsyncIndexing() {
            return testConfiguration.enableAsyncIndexing();
        }

        @Override
        public String getProperty(String string, String def) {
            return testConfiguration.getProperty(string, def);
        }

        @Override
        public boolean getBooleanProperty(String name, boolean defaultValue) {
            return testConfiguration.getBooleanProperty(name, defaultValue);
        }

        @Override
        public String getAvailabilityZone() {
            return testConfiguration.getAvailabilityZone();
        }

        @Override
        public int getIntProperty(String string, int def) {
            return testConfiguration.getIntProperty(string, def);
        }

        @Override
        public String getRegion() {
            return testConfiguration.getRegion();
        }

        @Override
        public Long getWorkflowInputPayloadSizeThresholdKB() {
            return testConfiguration.getWorkflowInputPayloadSizeThresholdKB();
        }

        @Override
        public Long getMaxWorkflowInputPayloadSizeThresholdKB() {
            return testConfiguration.getMaxWorkflowInputPayloadSizeThresholdKB();
        }

        @Override
        public Long getWorkflowOutputPayloadSizeThresholdKB() {
            return testConfiguration.getWorkflowOutputPayloadSizeThresholdKB();
        }

        @Override
        public Long getMaxWorkflowOutputPayloadSizeThresholdKB() {
            return testConfiguration.getMaxWorkflowOutputPayloadSizeThresholdKB();
        }

        @Override
        public Long getTaskInputPayloadSizeThresholdKB() {
            return testConfiguration.getTaskInputPayloadSizeThresholdKB();
        }

        @Override
        public Long getMaxTaskInputPayloadSizeThresholdKB() {
            return testConfiguration.getMaxTaskInputPayloadSizeThresholdKB();
        }

        @Override
        public Long getTaskOutputPayloadSizeThresholdKB() {
            return testConfiguration.getTaskOutputPayloadSizeThresholdKB();
        }

        @Override
        public Long getMaxTaskOutputPayloadSizeThresholdKB() {
            return testConfiguration.getMaxTaskOutputPayloadSizeThresholdKB();
        }

        @Override
        public Map<String, Object> getAll() {
            return testConfiguration.getAll();
        }

        @Override
        public long getLongProperty(String name, long defaultValue) {
            return testConfiguration.getLongProperty(name, defaultValue);
        }

        @Override
        public DB getDB() {
            return testConfiguration.getDB();
        }

        @Override
        public String getDBString() {
            return testConfiguration.getDBString();
        }

        @Override
        public int getAsyncUpdateShortRunningWorkflowDuration() {
            return testConfiguration.getAsyncUpdateShortRunningWorkflowDuration();
        }

        @Override
        public int getAsyncUpdateDelay() {
            return testConfiguration.getAsyncUpdateDelay();
        }

        @Override
        public boolean getJerseyEnabled() {
            return testConfiguration.getJerseyEnabled();
        }

        @Override
        public boolean getBoolProperty(String name, boolean defaultValue) {
            return testConfiguration.getBoolProperty(name, defaultValue);
        }

        @Override
        public List<AbstractModule> getAdditionalModules() {
            return testConfiguration.getAdditionalModules();
        }
    }
}
