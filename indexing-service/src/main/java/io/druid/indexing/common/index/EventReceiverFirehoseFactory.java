/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.indexing.common.index;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.metamx.emitter.EmittingLogger;
import io.druid.data.input.Firehose;
import io.druid.data.input.FirehoseFactory;
import io.druid.data.input.InputRow;
import io.druid.data.input.impl.MapInputRowParser;
import io.druid.indexing.common.config.EventReceiverFirehoseFactoryConfig;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Builds firehoses that accept events through the {@link EventReceiver} interface. Can also register these
 * firehoses with an {@link ServiceAnnouncingChatHandlerProvider}.
 */
@JsonTypeName("receiver")
public class EventReceiverFirehoseFactory implements FirehoseFactory
{
  private static final EmittingLogger log = new EmittingLogger(EventReceiverFirehoseFactory.class);
  private static final int DEFAULT_BUFFER_SIZE = 100000;

  private final String serviceName;
  private final int bufferSize;
  private final MapInputRowParser parser;
  private final Optional<ChatHandlerProvider> chatHandlerProvider;

  @JsonCreator
  public EventReceiverFirehoseFactory(
      @JsonProperty("serviceName") String serviceName,
      @JsonProperty("firehoseId") String firehoseId,
      @JsonProperty("bufferSize") Integer bufferSize,
      @JsonProperty("parser") MapInputRowParser parser,
      @JacksonInject ChatHandlerProvider chatHandlerProvider,
      @JacksonInject EventReceiverFirehoseFactoryConfig config
  )
  {
    // This code is here for backwards compatibility
    if (serviceName == null) {
      serviceName = String.format("%s:%s",
          config.getFirehoseIdPrefix(),
          Preconditions.checkNotNull(firehoseId, "firehoseId")
      );
    }

    this.serviceName = Preconditions.checkNotNull(serviceName, "serviceName");

    this.bufferSize = bufferSize == null || bufferSize <= 0 ? DEFAULT_BUFFER_SIZE : bufferSize;
    this.parser = Preconditions.checkNotNull(parser, "parser");
    this.chatHandlerProvider = Optional.fromNullable(chatHandlerProvider);
  }

  @Override
  public Firehose connect() throws IOException
  {
    log.info("Connecting firehose: %s", serviceName);

    final EventReceiverFirehose firehose = new EventReceiverFirehose();

    if (chatHandlerProvider.isPresent()) {
      log.info("Found chathandler of class[%s]", chatHandlerProvider.get().getClass().getName());
      chatHandlerProvider.get().register(serviceName, firehose);
      chatHandlerProvider.get().register(serviceName.replaceAll(".*:", ""), firehose); // rofl
    } else {
      log.info("No chathandler detected");
    }

    return firehose;
  }

  @JsonProperty
  public String getServiceName()
  {
    return serviceName;
  }

  @JsonProperty
  public int getBufferSize()
  {
    return bufferSize;
  }

  @JsonProperty
  public MapInputRowParser getParser()
  {
    return parser;
  }

  public class EventReceiverFirehose implements ChatHandler, Firehose
  {
    private final BlockingQueue<InputRow> buffer;

    private final Object readLock = new Object();

    private volatile InputRow nextRow = null;
    private volatile boolean closed = false;

    public EventReceiverFirehose()
    {
      this.buffer = new ArrayBlockingQueue<InputRow>(bufferSize);
    }

    @POST
    @Path("/push-events")
    @Produces("application/json")
    public Response addAll(Collection<Map<String, Object>> events)
    {
      log.debug("Adding %,d events to firehose: %s", events.size(), serviceName);

      final List<InputRow> rows = Lists.newArrayList();
      for (final Map<String, Object> event : events) {
        // Might throw an exception. We'd like that to happen now, instead of while adding to the row buffer.
        rows.add(parser.parse(event));
      }

      try {
        for (final InputRow row : rows) {
          boolean added = false;
          while (!closed && !added) {
            added = buffer.offer(row, 500, TimeUnit.MILLISECONDS);
          }

          if (!added) {
            throw new IllegalStateException("Cannot add events to closed firehose!");
          }
        }

        return Response.ok().entity(ImmutableMap.of("eventCount", events.size())).build();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw Throwables.propagate(e);
      }
    }

    @Override
    public boolean hasMore()
    {
      synchronized (readLock) {
        try {
          while (!closed && nextRow == null) {
            nextRow = buffer.poll(500, TimeUnit.MILLISECONDS);
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw Throwables.propagate(e);
        }

        return nextRow != null;
      }
    }

    @Override
    public InputRow nextRow()
    {
      synchronized (readLock) {
        final InputRow row = nextRow;

        if (row == null) {
          throw new NoSuchElementException();
        } else {
          nextRow = null;
          return row;
        }
      }
    }

    @Override
    public Runnable commit()
    {
      return new Runnable()
      {
        @Override
        public void run()
        {
          // Nothing
        }
      };
    }

    @Override
    public void close() throws IOException
    {
      log.info("Firehose closing.");
      closed = true;

      if (chatHandlerProvider.isPresent()) {
        chatHandlerProvider.get().unregister(serviceName);
      }
    }
  }
}
