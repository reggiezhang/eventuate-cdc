package io.eventuate.local.test.util;

import io.eventuate.common.eventuate.local.PublishedEvent;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class CdcProcessorEventsTest extends AbstractCdcEventsTest implements CdcProcessorCommon{

  @Test
  public void shouldReadNewEventsOnly() throws InterruptedException {
    BlockingQueue<PublishedEvent> publishedEvents = new LinkedBlockingDeque<>();
    prepareBinlogEntryHandler(publishedEvent -> {
      publishedEvents.add(publishedEvent);
      onEventSent(publishedEvent);
    });

    startEventProcessing();

    String testCreatedEvent = generateTestCreatedEvent();
    EventIdEntityId eventIdEntityId = saveEvent(testCreatedEvent);
    waitForEvent(publishedEvents, eventIdEntityId.getEventId(), LocalDateTime.now().plusSeconds(60), testCreatedEvent);
    stopEventProcessing();

    publishedEvents.clear();
    prepareBinlogEntryHandler(publishedEvent -> {
      publishedEvents.add(publishedEvent);
      onEventSent(publishedEvent);
    });
    startEventProcessing();

    testCreatedEvent = generateTestCreatedEvent();
    eventIdEntityId = updateEvent(eventIdEntityId.getEntityId(), testCreatedEvent);
    waitForEventExcluding(publishedEvents, eventIdEntityId.getEventId(), LocalDateTime.now().plusSeconds(60), testCreatedEvent, Collections.singletonList(eventIdEntityId.getEventId()));
    stopEventProcessing();
  }

  @Test
  public void shouldReadUnprocessedEventsAfterStartup() throws InterruptedException {
    BlockingQueue<PublishedEvent> publishedEvents = new LinkedBlockingDeque<>();

    String testCreatedEvent = generateTestCreatedEvent();
    EventIdEntityId eventIdEntityId = saveEvent(testCreatedEvent);

    prepareBinlogEntryHandler(publishedEvents::add);
    startEventProcessing();

    waitForEvent(publishedEvents, eventIdEntityId.getEventId(), LocalDateTime.now().plusSeconds(60), testCreatedEvent);
    stopEventProcessing();
  }

  private PublishedEvent waitForEventExcluding(BlockingQueue<PublishedEvent> publishedEvents, String eventId, LocalDateTime deadline, String eventData, List<String> excludedIds) throws InterruptedException {
    PublishedEvent result = null;
    while (LocalDateTime.now().isBefore(deadline)) {
      long millis = ChronoUnit.MILLIS.between(deadline, LocalDateTime.now());
      PublishedEvent event = publishedEvents.poll(millis, TimeUnit.MILLISECONDS);
      if (event != null) {
        if (event.getId().equals(eventId) && eventData.equals(event.getEventData())) {
          result = event;
          break;
        }
        if (excludedIds.contains(event.getId()))
          throw new RuntimeException("Event with excluded id found in the queue");
      }
    }
    if (result != null)
      return result;
    throw new RuntimeException("event not found: " + eventId);
  }

  protected abstract void prepareBinlogEntryHandler(Consumer<PublishedEvent> consumer);

  protected abstract void startEventProcessing();
  protected abstract void stopEventProcessing();
}
