package com.netflix.conductor.dao.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Uninterruptibles;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.execution.ApplicationException;
import com.netflix.conductor.dao.QueueDAO;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Singleton
public class MySQLQueueDAO extends MySQLBaseDAO implements QueueDAO {
    private static final Long UNACK_SCHEDULE_MS = 60_000L;

    @Inject
    public MySQLQueueDAO(ObjectMapper om, DataSource ds) {
        super(om, ds);

        Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(this::processAllUnacks,
                        UNACK_SCHEDULE_MS, UNACK_SCHEDULE_MS, TimeUnit.MILLISECONDS);
        logger.debug(MySQLQueueDAO.class.getName() + " is ready to serve");
    }

    @Override
    public void push(String queueName, String messageId, long offsetTimeInSecond) {
        push(queueName, messageId, 0, offsetTimeInSecond);
    }

    @Override
    public void push(String queueName, String messageId, int priority, long offsetTimeInSecond) {
        withTransaction(tx -> pushMessage(tx, queueName, messageId, null, priority, offsetTimeInSecond));
    }

    @Override
    public void push(String queueName, List<Message> messages) {
        withTransaction(tx -> messages
                .forEach(message -> pushMessage(tx, queueName, message.getId(), message.getPayload(), message.getPriority(), 0)));
    }

    @Override
    public boolean pushIfNotExists(String queueName, String messageId, long offsetTimeInSecond) {
        return pushIfNotExists(queueName, messageId, 0, offsetTimeInSecond);
    }

    @Override
    public boolean pushIfNotExists(String queueName, String messageId, int priority, long offsetTimeInSecond) {
        return getWithRetriedTransactions(tx -> {
            if (!existsMessage(tx, queueName, messageId)) {
                pushMessage(tx, queueName, messageId, null, priority, offsetTimeInSecond);
                return true;
            }
            return false;
        });
    }

    @Override
    public List<String> pop(String queueName, int count, int timeout) {
        return pollMessages(queueName, count, timeout).stream().map(Message::getId).collect(Collectors.toList());
    }

    @Override
    public List<Message> pollMessages(String queueName, int count, int timeout) {
        if (timeout < 1) {
            List<Message> messages = getWithTransactionWithOutErrorPropagation(tx -> popMessages(tx, queueName, count, timeout));
            if (messages == null) return new ArrayList<>();
            return messages;
        }


        long start = System.currentTimeMillis();
        final List<Message> messages = new ArrayList<>();

        while (true) {
            List<Message> messagesSlice = getWithTransactionWithOutErrorPropagation(tx -> popMessages(tx, queueName, count - messages.size(), timeout));
            if(messagesSlice == null) {
                logger.warn("Unable to poll {} messages from {} due to tx conflict, only {} popped", count, queueName, messages.size());
                // conflict could have happened, returned messages popped so far
                return messages;
            }

            messages.addAll(messagesSlice);
            if (messages.size() >= count || ((System.currentTimeMillis() - start) > timeout)) {
                return messages;
            }
            Uninterruptibles.sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void remove(String queueName, String messageId) {
        withTransaction(tx -> removeMessage(tx, queueName, messageId));
    }

    @Override
    public int getSize(String queueName) {
        return getWithRetriedTransactions(tx -> {
            String LOCK_TASKS = "SELECT * FROM queue_message WHERE queue_name = ? FOR SHARE";
            execute(tx, LOCK_TASKS, q -> q.addParameter(queueName).executeQuery());

            final String GET_QUEUE_SIZE = "SELECT COUNT(*) FROM queue_message WHERE queue_name = ?";
            return query(tx, GET_QUEUE_SIZE, q -> ((Long) q.addParameter(queueName).executeCount()).intValue());
        });
    }

    @Override
    public boolean ack(String queueName, String messageId) {
        return getWithRetriedTransactions(tx -> removeMessage(tx, queueName, messageId));
    }

    @Override
    public boolean setUnackTimeout(String queueName, String messageId, long unackTimeout) {
        long updatedOffsetTimeInSecond = unackTimeout / 1000;

        final String UPDATE_UNACK_TIMEOUT = "UPDATE queue_message SET offset_time_seconds = ?, deliver_on = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP) WHERE queue_name = ? AND message_id = ?";

        return queryWithTransaction(UPDATE_UNACK_TIMEOUT,
                q -> q.addParameter(updatedOffsetTimeInSecond).addParameter(updatedOffsetTimeInSecond)
                        .addParameter(queueName).addParameter(messageId).executeUpdate()) == 1;
    }

    @Override
    public void flush(String queueName) {
        final String FLUSH_QUEUE = "DELETE FROM queue_message WHERE queue_name = ?";
        executeWithTransaction(FLUSH_QUEUE, q -> q.addParameter(queueName).executeDelete());
    }

    @Override
    public Map<String, Long> queuesDetail() {
        final String GET_QUEUES_DETAIL = "SELECT queue_name, (SELECT count(*) FROM queue_message WHERE popped = false AND queue_name = q.queue_name) AS size FROM queue q";
        return queryWithTransaction(GET_QUEUES_DETAIL, q -> q.executeAndFetch(rs -> {
            Map<String, Long> detail = Maps.newHashMap();
            while (rs.next()) {
                String queueName = rs.getString("queue_name");
                Long size = rs.getLong("size");
                detail.put(queueName, size);
            }
            return detail;
        }));
    }

    @Override
    public Map<String, Map<String, Map<String, Long>>> queuesDetailVerbose() {
        // @formatter:off
        final String GET_QUEUES_DETAIL_VERBOSE = "SELECT queue_name, \n"
                + "       (SELECT count(*) FROM queue_message WHERE popped = false AND queue_name = q.queue_name) AS size,\n"
                + "       (SELECT count(*) FROM queue_message WHERE popped = true AND queue_name = q.queue_name) AS uacked \n"
                + "FROM queue q";
        // @formatter:on

        return queryWithTransaction(GET_QUEUES_DETAIL_VERBOSE, q -> q.executeAndFetch(rs -> {
            Map<String, Map<String, Map<String, Long>>> result = Maps.newHashMap();
            while (rs.next()) {
                String queueName = rs.getString("queue_name");
                Long size = rs.getLong("size");
                Long queueUnacked = rs.getLong("uacked");
                result.put(queueName, ImmutableMap.of("a", ImmutableMap.of( // sharding not implemented, returning only
                        // one shard with all the info
                        "size", size, "uacked", queueUnacked)));
            }
            return result;
        }));
    }

    /**
     * Un-pop all un-acknowledged messages for all queues.

     * @since 1.11.6
     */
    public void processAllUnacks() {

        logger.trace("processAllUnacks started");

        getWithRetriedTransactions(tx -> {
            String LOCK_TASKS = "SELECT message_id FROM queue_message WHERE popped = true AND TIMESTAMPADD(SECOND,60,deliver_on) < CURRENT_TIMESTAMP FOR UPDATE SKIP LOCKED";
            query(tx, LOCK_TASKS, Query::executeQuery);

            List<String> messages = query(tx, LOCK_TASKS, p -> p.executeAndFetch(rs -> {
                        List<String> results = new ArrayList<>();
                        while (rs.next()) {
                            results.add(rs.getString("message_id"));
                        }
                        return results;
                    }));

            if (messages.size() == 0) {
                return 0;
            }
            String msgIdsString = String.join(",", messages);

            final String PROCESS_UNACKS = "UPDATE queue_message SET popped = false WHERE message_id IN (?)";
            Integer unacked = query(tx, PROCESS_UNACKS, q -> q.addParameter(msgIdsString).executeUpdate());
            if (unacked > 0) {
                logger.debug("Unacked {} messages: {} from all queues", unacked, messages);
            }
            return unacked;
        });
    }

    @Override
    public void processUnacks(String queueName) {
        // All messages popped but not acked in 60 seconds will be returned to queue
        getWithRetriedTransactions(tx -> {
            String LOCK_TASKS = "SELECT message_id FROM queue_message WHERE queue_name = ? AND popped = true AND TIMESTAMPADD(SECOND,60,deliver_on) < CURRENT_TIMESTAMP FOR UPDATE SKIP LOCKED";
            query(tx, LOCK_TASKS, q -> q.addParameter(queueName).executeQuery());

            List<String> messages = query(tx, LOCK_TASKS, p -> p.addParameter(queueName)
                    .executeAndFetch(rs -> {
                        List<String> results = new ArrayList<>();
                        while (rs.next()) {
                            results.add(rs.getString("message_id"));
                        }
                        return results;
                    }));

            if (messages.size() == 0) {
                return 0;
            }
            String msgIdsString = String.join(",", messages);

            final String PROCESS_UNACKS = "UPDATE queue_message SET popped = false WHERE queue_name = ? AND message_id IN (?)";
            Integer unacked = query(tx, PROCESS_UNACKS, q -> q.addParameter(queueName).addParameter(msgIdsString).executeUpdate());
            if (unacked > 0) {
                logger.debug("Unacked {} messages: {} from queue: {}", unacked, messages, queueName);
            }
            return unacked;
        });
    }

    @Override
    public boolean setOffsetTime(String queueName, String messageId, long offsetTimeInSecond) {
        return getWithRetriedTransactions(tx -> {
            String LOCK_TASKS = "SELECT * FROM queue_message WHERE queue_name = ? FOR UPDATE";
            execute(tx, LOCK_TASKS, q -> q.addParameter(queueName).executeQuery());

            final String SET_OFFSET_TIME = "UPDATE queue_message SET offset_time_seconds = ?, deliver_on = TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP) \n"
                    + "WHERE queue_name = ? AND message_id = ?";

            return query(tx, SET_OFFSET_TIME, q -> q.addParameter(offsetTimeInSecond)
                    .addParameter(offsetTimeInSecond).addParameter(queueName).addParameter(messageId).executeUpdate()) == 1;
        });
    }

    @Override
    public boolean exists(String queueName, String messageId) {
        return getWithRetriedTransactions(tx -> existsMessage(tx, queueName, messageId));
    }

    private boolean existsMessage(Connection connection, String queueName, String messageId) {
        final String EXISTS_MESSAGE = "SELECT EXISTS(SELECT 1 FROM queue_message WHERE queue_name = ? AND message_id = ?) FOR SHARE";
        return query(connection, EXISTS_MESSAGE, q -> q.addParameter(queueName).addParameter(messageId).exists());
    }

    private void pushMessage(Connection connection, String queueName, String messageId, String payload, Integer priority,
                             long offsetTimeInSecond) {
        String LOCK_QUEUE = "SELECT * FROM queue WHERE queue_name = ? FOR UPDATE";
        execute(connection, LOCK_QUEUE, q -> q.addParameter(queueName).executeQuery());
        String LOCK_TASKS = "SELECT * FROM queue_message WHERE queue_name = ? AND message_id = ? FOR UPDATE";
        execute(connection, LOCK_TASKS, q -> q.addParameter(queueName).addParameter(messageId).executeQuery());

        createQueueIfNotExists(connection, queueName);

        String PUSH_MESSAGE = "INSERT INTO queue_message (deliver_on, queue_name, message_id, priority, offset_time_seconds, payload) VALUES (TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP), ?, ?,?,?,?) ON DUPLICATE KEY UPDATE payload=VALUES(payload), deliver_on=VALUES(deliver_on)";
        execute(connection, PUSH_MESSAGE, q -> q.addParameter(offsetTimeInSecond).addParameter(queueName)
                .addParameter(messageId).addParameter(priority).addParameter(offsetTimeInSecond)
                .addParameter(payload).executeUpdate());
    }

    private boolean removeMessage(Connection connection, String queueName, String messageId) {
        String LOCK_TASKS = "SELECT * FROM queue_message WHERE queue_name = ? AND message_id = ? FOR UPDATE";
        execute(connection, LOCK_TASKS, q -> q.addParameter(queueName).addParameter(messageId).executeQuery());

        final String REMOVE_MESSAGE = "DELETE FROM queue_message WHERE queue_name = ? AND message_id = ?";
        return query(connection, REMOVE_MESSAGE,
                q -> q.addParameter(queueName).addParameter(messageId).executeDelete());
    }

    private List<Message> peekMessages(Connection connection, String queueName, int count) {
        if (count < 1)
            return Collections.emptyList();

        final String PEEK_MESSAGES = "SELECT message_id, priority, payload FROM queue_message use index(combo_queue_message) WHERE queue_name = ? AND popped = false AND deliver_on <= TIMESTAMPADD(MICROSECOND, 1000, CURRENT_TIMESTAMP) ORDER BY priority DESC, deliver_on, created_on LIMIT ? FOR UPDATE SKIP LOCKED";

        List<Message> messages = query(connection, PEEK_MESSAGES, p -> p.addParameter(queueName)
                .addParameter(count).executeAndFetch(rs -> {
                    List<Message> results = new ArrayList<>();
                    while (rs.next()) {
                        Message m = new Message();
                        m.setId(rs.getString("message_id"));
                        m.setPriority(rs.getInt("priority"));
                        m.setPayload(rs.getString("payload"));
                        results.add(m);
                    }
                    return results;
                }));

        return messages;
    }

    private List<Message> popMessages(Connection connection, String queueName, int count, int timeout) {
        List<Message> messages = peekMessages(connection, queueName, count);

        if (messages.isEmpty()) {
            return messages;
        }

        final String POP_MESSAGES = "UPDATE queue_message SET popped = true WHERE queue_name = ? AND message_id IN (%s) AND popped = false";

        final List<String> Ids = messages.stream().map(Message::getId).collect(Collectors.toList());
        final String query = String.format(POP_MESSAGES, Query.generateInBindings(messages.size()));

        int result = query(connection, query, q -> q.addParameter(queueName).addParameters(Ids).executeUpdate());

        if (result != messages.size()) {
            String message = String.format("Could not pop all messages for given ids: %s (%d messages were popped)",
                    Ids, result);
            throw new ApplicationException(ApplicationException.Code.BACKEND_ERROR, message);
        }
        return messages;
    }


    private void createQueueIfNotExists(Connection connection, String queueName) {
        logger.trace("Creating new queue '{}'", queueName);

        final String CREATE_QUEUE = "INSERT IGNORE INTO queue (queue_name) VALUES (?)";
        execute(connection, CREATE_QUEUE, q -> q.addParameter(queueName).executeUpdate());
    }
}
