package io.eventuate.local.cdc.debezium;

import io.eventuate.Int128;
import io.eventuate.SubscriberOptions;
import io.eventuate.javaclient.commonimpl.AggregateCrud;
import io.eventuate.javaclient.commonimpl.EntityIdVersionAndEventIds;
import io.eventuate.javaclient.commonimpl.EventTypeAndData;
import io.eventuate.javaclient.spring.jdbc.EventuateSchema;
import io.eventuate.local.java.jdbckafkastore.EventuateKafkaAggregateSubscriptions;
import io.eventuate.testutil.AsyncUtil;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public abstract class AbstractTopicRelayTest {
  private Logger logger = LoggerFactory.getLogger(getClass());

  @Autowired
  private AggregateCrud eventuateJdbcEventStore;

  @Autowired
  private EventuateKafkaAggregateSubscriptions eventuateKafkaAggregateSubscriptions;

  @Autowired
  private EventTableChangesToAggregateTopicRelay eventTableChangesToAggregateTopicRelay;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private EventuateSchema eventuateSchema;

  @Test
  public void shouldCaptureAndPublishChange() throws ExecutionException, InterruptedException {

    String aggregateType = "TestAggregate" + UUID.randomUUID().toString();
    String eventType = "TestEvent" + UUID.randomUUID().toString();

    List<EventTypeAndData> myEvents = Collections.singletonList(new EventTypeAndData(eventType, "{}", Optional.empty()));

    long publishTime = System.currentTimeMillis();

    EntityIdVersionAndEventIds ewidv = AsyncUtil.await(eventuateJdbcEventStore.save(aggregateType, myEvents, Optional.empty()));

    Int128 expectedEventId = ewidv.getEntityVersion();
    BlockingQueue<Int128> result = new LinkedBlockingDeque<>();

    logger.debug("Looking for eventId {}", expectedEventId);

    eventuateKafkaAggregateSubscriptions.subscribe("testSubscriber-" + getClass().getName(),
            Collections.singletonMap(aggregateType, Collections.singleton(eventType)),
            SubscriberOptions.DEFAULTS,
            se -> {
              logger.debug("got se {}", se);
              if (se.getId().equals(expectedEventId))
                result.add(se.getId());
              return CompletableFuture.completedFuture(null);
            }).get();

    Assert.assertNotNull("Failed to find eventId: " + expectedEventId, result.poll(30, TimeUnit.SECONDS));
    Assert.assertNull(result.poll(30, TimeUnit.SECONDS));

    long endTime = System.currentTimeMillis();

    logger.debug("got the event I just published in msecs {}", endTime - publishTime);
  }

  @Test
  public void testDelete() throws ExecutionException, InterruptedException {

    String aggregateType = "TestAggregate" + UUID.randomUUID().toString();
    String eventType = "TestEvent" + UUID.randomUUID().toString();

    List<EventTypeAndData> myEvents = Collections.singletonList(new EventTypeAndData(eventType, "{}", Optional.empty()));

    EntityIdVersionAndEventIds ewidv = AsyncUtil.await(eventuateJdbcEventStore.save(aggregateType, myEvents, Optional.empty()));

    Set<Int128> expectedEventIds = new HashSet<>();
    expectedEventIds.add(ewidv.getEntityVersion());
    BlockingQueue<Int128> result = new LinkedBlockingDeque<>();
    eventuateKafkaAggregateSubscriptions.subscribe("testSubscriber-" + getClass().getName(),
            Collections.singletonMap(aggregateType, Collections.singleton(eventType)),
            SubscriberOptions.DEFAULTS,
            se -> {
              logger.debug("got se {}", se);
              if (expectedEventIds.contains(se.getId()))
                result.add(se.getId());
              return CompletableFuture.completedFuture(null);
            }).get();

    Assert.assertNotNull(result.poll(30, TimeUnit.SECONDS));
    Assert.assertNull(result.poll(30, TimeUnit.SECONDS));

    new JdbcTemplate(dataSource).update(String.format("delete from %s", eventuateSchema.qualifyTable("events")));

    ewidv = AsyncUtil.await(eventuateJdbcEventStore.save(aggregateType, myEvents, Optional.empty()));
    expectedEventIds.add(ewidv.getEntityVersion());

    Assert.assertNotNull(result.poll(30, TimeUnit.SECONDS));
    Assert.assertNull(result.poll(30, TimeUnit.SECONDS));
  }

  @Test
  public void shouldStartup() throws InterruptedException {
    TimeUnit.SECONDS.sleep(10);
  }
}
