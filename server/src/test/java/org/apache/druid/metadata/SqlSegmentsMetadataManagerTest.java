/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.metadata;

import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import org.apache.druid.client.DataSourcesSnapshot;
import org.apache.druid.client.ImmutableDruidDataSource;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.DruidExceptionMatcher;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.segment.TestDataSource;
import org.apache.druid.segment.metadata.CentralizedDatasourceSchemaConfig;
import org.apache.druid.segment.metadata.SegmentSchemaCache;
import org.apache.druid.segment.metadata.SegmentSchemaManager;
import org.apache.druid.server.coordinator.CreateDataSegments;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.partition.NoneShardSpec;
import org.hamcrest.MatcherAssert;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

public class SqlSegmentsMetadataManagerTest extends SqlSegmentsMetadataManagerTestBase
{
  private static DataSegment createSegment(
      String dataSource,
      String interval,
      String version
  )
  {
    return new DataSegment(
        dataSource,
        Intervals.of(interval),
        version,
        ImmutableMap.of(),
        ImmutableList.of(),
        ImmutableList.of(),
        NoneShardSpec.instance(),
        9,
        1234L
    );
  }

  @Rule
  public final TestDerbyConnector.DerbyConnectorRule derbyConnectorRule
      = new TestDerbyConnector.DerbyConnectorRule();

  private final DataSegment wikiSegment1 =
      CreateDataSegments.ofDatasource(TestDataSource.WIKI).startingAt("2012-03-15").eachOfSizeInMb(500).get(0);
  private final DataSegment wikiSegment2 =
      CreateDataSegments.ofDatasource(TestDataSource.WIKI).startingAt("2012-01-05").eachOfSizeInMb(500).get(0);

  private void publishUnusedSegments(DataSegment... segments) throws IOException
  {
    for (DataSegment segment : segments) {
      publishSegment(segment);
      sqlSegmentsMetadataManager.markSegmentAsUnused(segment.getId());
    }
  }

  private void publishWikiSegments()
  {
    try {
      publishSegment(wikiSegment1);
      publishSegment(wikiSegment2);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Before
  public void setUp()
  {
    connector = derbyConnectorRule.getConnector();
    SegmentsMetadataManagerConfig config = new SegmentsMetadataManagerConfig(Period.seconds(3), null);
    storageConfig = derbyConnectorRule.metadataTablesConfigSupplier().get();

    segmentSchemaCache = new SegmentSchemaCache(NoopServiceEmitter.instance());
    segmentSchemaManager = new SegmentSchemaManager(
        derbyConnectorRule.metadataTablesConfigSupplier().get(),
        jsonMapper,
        connector
    );

    sqlSegmentsMetadataManager = new SqlSegmentsMetadataManager(
        jsonMapper,
        Suppliers.ofInstance(config),
        derbyConnectorRule.metadataTablesConfigSupplier(),
        connector,
        segmentSchemaCache,
        CentralizedDatasourceSchemaConfig.create(),
        NoopServiceEmitter.instance()
    );
    sqlSegmentsMetadataManager.start();

    connector.createSegmentSchemasTable();
    connector.createSegmentTable();
  }

  @After
  public void teardown()
  {
    if (sqlSegmentsMetadataManager.isPollingDatabasePeriodically()) {
      sqlSegmentsMetadataManager.stopPollingDatabasePeriodically();
    }
    sqlSegmentsMetadataManager.stop();
  }

  @Test
  public void testPollEmpty()
  {
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());
    Assert.assertTrue(
        sqlSegmentsMetadataManager.retrieveAllDataSourceNames().isEmpty()
    );
    Assert.assertEquals(
        0,
        sqlSegmentsMetadataManager
            .getImmutableDataSourcesWithAllUsedSegments()
            .stream()
            .map(ImmutableDruidDataSource::getName).count()
    );
    Assert.assertNull(sqlSegmentsMetadataManager.getImmutableDataSourceWithUsedSegments(TestDataSource.WIKI));
    Assert.assertTrue(
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments()).isEmpty()
    );
  }

  @Test
  public void testPollPeriodically()
  {
    publishWikiSegments();
    DataSourcesSnapshot dataSourcesSnapshot = sqlSegmentsMetadataManager.getDataSourcesSnapshot();
    Assert.assertNull(dataSourcesSnapshot);
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());
    // This call make sure that the first poll is completed
    sqlSegmentsMetadataManager.useLatestSnapshotIfWithinDelay();
    Assert.assertTrue(sqlSegmentsMetadataManager.getLatestDatabasePoll() instanceof SqlSegmentsMetadataManager.PeriodicDatabasePoll);
    dataSourcesSnapshot = sqlSegmentsMetadataManager.getDataSourcesSnapshot();
    Assert.assertEquals(
        ImmutableSet.of(TestDataSource.WIKI),
        sqlSegmentsMetadataManager.retrieveAllDataSourceNames()
    );
    Assert.assertEquals(
        ImmutableList.of(TestDataSource.WIKI),
        dataSourcesSnapshot.getDataSourcesWithAllUsedSegments()
                           .stream()
                           .map(ImmutableDruidDataSource::getName)
                           .collect(Collectors.toList())
    );
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(dataSourcesSnapshot.getDataSource(TestDataSource.WIKI).getSegments())
    );
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(dataSourcesSnapshot.iterateAllUsedSegmentsInSnapshot())
    );
  }

  @Test
  public void testPollOnDemand()
  {
    publishWikiSegments();
    DataSourcesSnapshot dataSourcesSnapshot = sqlSegmentsMetadataManager.getDataSourcesSnapshot();
    Assert.assertNull(dataSourcesSnapshot);
    // This should return false and not wait/poll anything as we did not schedule periodic poll
    Assert.assertFalse(sqlSegmentsMetadataManager.useLatestSnapshotIfWithinDelay());
    Assert.assertNull(dataSourcesSnapshot);
    // This call will force on demand poll
    sqlSegmentsMetadataManager.forceOrWaitOngoingDatabasePoll();
    Assert.assertFalse(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());
    Assert.assertTrue(sqlSegmentsMetadataManager.getLatestDatabasePoll() instanceof SqlSegmentsMetadataManager.OnDemandDatabasePoll);
    dataSourcesSnapshot = sqlSegmentsMetadataManager.getDataSourcesSnapshot();
    Assert.assertEquals(
        ImmutableSet.of(TestDataSource.WIKI),
        sqlSegmentsMetadataManager.retrieveAllDataSourceNames()
    );
    Assert.assertEquals(
        ImmutableList.of(TestDataSource.WIKI),
        dataSourcesSnapshot.getDataSourcesWithAllUsedSegments()
                           .stream()
                           .map(ImmutableDruidDataSource::getName)
                           .collect(Collectors.toList())
    );
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(dataSourcesSnapshot.getDataSource(TestDataSource.WIKI).getSegments())
    );
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(dataSourcesSnapshot.iterateAllUsedSegmentsInSnapshot())
    );
  }

  @Test(timeout = 60_000)
  public void testPollPeriodicallyAndOnDemandInterleave() throws Exception
  {
    publishWikiSegments();
    DataSourcesSnapshot dataSourcesSnapshot = sqlSegmentsMetadataManager.getDataSourcesSnapshot();
    Assert.assertNull(dataSourcesSnapshot);
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());
    // This call make sure that the first poll is completed
    sqlSegmentsMetadataManager.useLatestSnapshotIfWithinDelay();
    Assert.assertTrue(sqlSegmentsMetadataManager.getLatestDatabasePoll() instanceof SqlSegmentsMetadataManager.PeriodicDatabasePoll);
    dataSourcesSnapshot = sqlSegmentsMetadataManager.getDataSourcesSnapshot();
    Assert.assertEquals(
        ImmutableList.of(TestDataSource.WIKI),
        dataSourcesSnapshot.getDataSourcesWithAllUsedSegments()
                           .stream()
                           .map(ImmutableDruidDataSource::getName)
                           .collect(Collectors.toList())
    );
    publishSegment(createNewSegment1(TestDataSource.KOALA));

    // This call will force on demand poll
    sqlSegmentsMetadataManager.forceOrWaitOngoingDatabasePoll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());
    Assert.assertTrue(sqlSegmentsMetadataManager.getLatestDatabasePoll() instanceof SqlSegmentsMetadataManager.OnDemandDatabasePoll);
    // New datasource should now be in the snapshot since we just force on demand poll.
    dataSourcesSnapshot = sqlSegmentsMetadataManager.getDataSourcesSnapshot();
    Assert.assertEquals(
        ImmutableList.of(TestDataSource.KOALA, TestDataSource.WIKI),
        dataSourcesSnapshot.getDataSourcesWithAllUsedSegments()
                           .stream()
                           .map(ImmutableDruidDataSource::getName)
                           .collect(Collectors.toList())
    );

    final String newDataSource3 = "wikipedia3";
    publishSegment(createNewSegment1(newDataSource3));

    // This time wait for periodic poll (not doing on demand poll so we have to wait a bit...)
    while (sqlSegmentsMetadataManager.getDataSourcesSnapshot().getDataSource(newDataSource3) == null) {
      Thread.sleep(1000);
    }
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());
    Assert.assertTrue(sqlSegmentsMetadataManager.getLatestDatabasePoll() instanceof SqlSegmentsMetadataManager.PeriodicDatabasePoll);
    dataSourcesSnapshot = sqlSegmentsMetadataManager.getDataSourcesSnapshot();
    Assert.assertEquals(
        ImmutableSet.of(TestDataSource.KOALA, "wikipedia3", TestDataSource.WIKI),
        dataSourcesSnapshot.getDataSourcesWithAllUsedSegments()
                           .stream()
                           .map(ImmutableDruidDataSource::getName)
                           .collect(Collectors.toSet())
    );
  }

  @Test
  public void testPrepareImmutableDataSourceWithUsedSegmentsAwaitsPollOnRestart() throws IOException
  {
    publishWikiSegments();
    DataSegment koalaSegment = pollThenStopThenPublishKoalaSegment();
    Assert.assertEquals(
        ImmutableSet.of(koalaSegment),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.getImmutableDataSourceWithUsedSegments(TestDataSource.KOALA).getSegments())
    );
  }

  @Test
  public void testGetDataSourceWithUsedSegmentsAwaitsPollOnRestart() throws IOException
  {
    publishWikiSegments();
    DataSegment koalaSegment = pollThenStopThenPublishKoalaSegment();
    Assert.assertEquals(
        ImmutableSet.of(koalaSegment),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.getImmutableDataSourceWithUsedSegments(TestDataSource.KOALA).getSegments())
    );
  }

  @Test
  public void testPrepareImmutableDataSourcesWithAllUsedSegmentsAwaitsPollOnRestart() throws IOException
  {
    publishWikiSegments();
    DataSegment koalaSegment = pollThenStopThenPublishKoalaSegment();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment),
        ImmutableSet.copyOf(
            sqlSegmentsMetadataManager
                .getImmutableDataSourcesWithAllUsedSegments()
                .stream()
                .flatMap((ImmutableDruidDataSource dataSource) -> dataSource.getSegments().stream())
                .iterator()
        )
    );
  }

  @Test
  public void testIterateAllUsedSegmentsAwaitsPollOnRestart() throws IOException
  {
    publishWikiSegments();
    DataSegment koalaSegment = pollThenStopThenPublishKoalaSegment();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  private DataSegment pollThenStopThenPublishKoalaSegment() throws IOException
  {
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    sqlSegmentsMetadataManager.stopPollingDatabasePeriodically();
    Assert.assertFalse(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());
    Assert.assertEquals(
        ImmutableSet.of(TestDataSource.WIKI),
        sqlSegmentsMetadataManager.retrieveAllDataSourceNames()
    );
    final DataSegment koalaSegment = createNewSegment1(TestDataSource.KOALA);
    publishSegment(koalaSegment);
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    return koalaSegment;
  }

  /**
   * Create a corrupted segment entry in the segments table to test
   * whether the overall loading of segments from the database continues to work
   * even if one of the entries is corrupted.
   */
  @Test
  public void testPollWithCorruptedSegment() throws IOException
  {
    publishWikiSegments();

    final DataSegment corruptSegment = DataSegment.builder(wikiSegment1).dataSource("corrupt-datasource").build();
    publishSegment(corruptSegment);
    updateSegmentPayload(corruptSegment, StringUtils.toUtf8("corrupt-payload"));

    EmittingLogger.registerEmitter(new NoopServiceEmitter());
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    Assert.assertEquals(
        TestDataSource.WIKI,
        Iterables.getOnlyElement(sqlSegmentsMetadataManager.getImmutableDataSourcesWithAllUsedSegments()).getName()
    );
  }

  @Test
  public void testGetUnusedSegmentIntervals() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();

    // Allow null values of used_status_last_updated to test upgrade from older Druid versions
    allowUsedFlagLastUpdatedToBeNullable();

    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());
    int numChangedSegments = sqlSegmentsMetadataManager.markAsUnusedAllSegmentsInDataSource(TestDataSource.WIKI);
    Assert.assertEquals(2, numChangedSegments);

    // Publish an unused segment with used_status_last_updated 2 hours ago
    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-16T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );
    publishUnusedSegments(koalaSegment1);
    derbyConnectorRule.segments().updateUsedStatusLastUpdated(
        koalaSegment1.getId().toString(),
        DateTimes.nowUtc().minus(Duration.standardHours(2))
    );

    // Publish an unused segment with used_status_last_updated 2 days ago
    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-16T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );
    publishUnusedSegments(koalaSegment2);
    derbyConnectorRule.segments().updateUsedStatusLastUpdated(
        koalaSegment2.getId().toString(),
        DateTimes.nowUtc().minus(Duration.standardDays(2))
    );

    // Publish an unused segment and set used_status_last_updated to null
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );
    publishUnusedSegments(koalaSegment3);
    updateUsedStatusLastUpdatedToNull(koalaSegment3);

    Assert.assertEquals(
        ImmutableList.of(wikiSegment2.getInterval()),
        sqlSegmentsMetadataManager.getUnusedSegmentIntervals(
            TestDataSource.WIKI,
            null,
            DateTimes.of("3000"),
            1,
            DateTimes.COMPARE_DATE_AS_STRING_MAX
        )
    );

    // Test the DateTime maxEndTime argument of getUnusedSegmentIntervals
    Assert.assertEquals(
        ImmutableList.of(wikiSegment2.getInterval()),
        sqlSegmentsMetadataManager.getUnusedSegmentIntervals(
            TestDataSource.WIKI,
            null,
            DateTimes.of(2012, 1, 7, 0, 0),
            1,
            DateTimes.COMPARE_DATE_AS_STRING_MAX
        )
    );
    Assert.assertEquals(
        ImmutableList.of(wikiSegment1.getInterval()),
        sqlSegmentsMetadataManager.getUnusedSegmentIntervals(
            TestDataSource.WIKI,
            DateTimes.of(2012, 1, 7, 0, 0),
            DateTimes.of(2012, 4, 7, 0, 0),
            1,
            DateTimes.COMPARE_DATE_AS_STRING_MAX
        )
    );
    Assert.assertEquals(
        ImmutableList.of(),
        sqlSegmentsMetadataManager.getUnusedSegmentIntervals(
            TestDataSource.WIKI,
            DateTimes.of(2012, 1, 7, 0, 0),
            DateTimes.of(2012, 1, 7, 0, 0),
            1,
            DateTimes.COMPARE_DATE_AS_STRING_MAX
        )
    );

    Assert.assertEquals(
        ImmutableList.of(wikiSegment2.getInterval(), wikiSegment1.getInterval()),
        sqlSegmentsMetadataManager.getUnusedSegmentIntervals(
            TestDataSource.WIKI,
            null,
            DateTimes.of("3000"),
            5,
            DateTimes.COMPARE_DATE_AS_STRING_MAX
        )
    );

    // Test a buffer period that should exclude some segments

    // The wikipedia datasource has segments generated with last used time equal to roughly the time of test run. None of these segments should be selected with a bufer period of 1 day
    Assert.assertEquals(
        ImmutableList.of(),
        sqlSegmentsMetadataManager.getUnusedSegmentIntervals(
            TestDataSource.WIKI,
            DateTimes.COMPARE_DATE_AS_STRING_MIN,
            DateTimes.of("3000"),
            5,
            DateTimes.nowUtc().minus(Duration.parse("PT86400S"))
        )
    );

    // koalaSegment3 has a null used_status_last_updated which should mean getUnusedSegmentIntervals never returns it
    // koalaSegment2 has a used_status_last_updated older than 1 day which means it should be returned
    // The last of the 3 segments in koala has a used_status_last_updated date less than one day and should not be returned
    Assert.assertEquals(
        ImmutableList.of(koalaSegment2.getInterval()),
        sqlSegmentsMetadataManager.getUnusedSegmentIntervals(
            TestDataSource.KOALA,
            DateTimes.COMPARE_DATE_AS_STRING_MIN,
            DateTimes.of("3000"),
            5,
            DateTimes.nowUtc().minus(Duration.parse("PT86400S"))
        )
    );
  }

  @Test(timeout = 60_000)
  public void testMarkAsUnusedAllSegmentsInDataSource() throws IOException, InterruptedException
  {
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    publishSegment(createNewSegment1(TestDataSource.KOALA));

    awaitDataSourceAppeared(TestDataSource.KOALA);
    int numChangedSegments = sqlSegmentsMetadataManager.markAsUnusedAllSegmentsInDataSource(TestDataSource.KOALA);
    Assert.assertEquals(1, numChangedSegments);
    awaitDataSourceDisappeared(TestDataSource.KOALA);
    Assert.assertNull(sqlSegmentsMetadataManager.getImmutableDataSourceWithUsedSegments(TestDataSource.KOALA));
  }

  private static DataSegment createNewSegment1(String datasource)
  {
    return createSegment(
        datasource,
        "2017-10-15T00:00:00.000/2017-10-16T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );
  }

  private static DataSegment createNewSegment2(String datasource)
  {
    return createSegment(
        datasource,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );
  }

  @Test(timeout = 60_000)
  public void testMarkSegmentAsUnused() throws IOException, InterruptedException
  {
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-16T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishSegment(koalaSegment);
    awaitDataSourceAppeared(TestDataSource.KOALA);
    Assert.assertNotNull(sqlSegmentsMetadataManager.getImmutableDataSourceWithUsedSegments(TestDataSource.KOALA));

    Assert.assertTrue(sqlSegmentsMetadataManager.markSegmentAsUnused(koalaSegment.getId()));
    awaitDataSourceDisappeared(TestDataSource.KOALA);
    Assert.assertNull(sqlSegmentsMetadataManager.getImmutableDataSourceWithUsedSegments(TestDataSource.KOALA));
  }

  private void awaitDataSourceAppeared(String datasource) throws InterruptedException
  {
    while (sqlSegmentsMetadataManager.getImmutableDataSourceWithUsedSegments(datasource) == null) {
      Thread.sleep(5);
    }
  }

  private void awaitDataSourceDisappeared(String dataSource) throws InterruptedException
  {
    while (sqlSegmentsMetadataManager.getImmutableDataSourceWithUsedSegments(dataSource) != null) {
      Thread.sleep(5);
    }
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegments() throws Exception
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-16T20:19:12.565Z"
    );

    // Overshadowed by koalaSegment2
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishUnusedSegments(koalaSegment1, koalaSegment2, koalaSegment3);
    final Set<SegmentId> segmentIds = ImmutableSet.of(
        koalaSegment1.getId(),
        koalaSegment2.getId(),
        koalaSegment3.getId()
    );

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
    Assert.assertEquals(2, sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegments(TestDataSource.KOALA, segmentIds));
    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment1, koalaSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsInEternityIntervalWithVersions() throws Exception
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-16T20:19:12.565Z"
    );

    // Overshadowed by koalaSegment2
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishUnusedSegments(koalaSegment1, koalaSegment2, koalaSegment3);

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
    Assert.assertEquals(
        2,
        sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegmentsInInterval(
            TestDataSource.KOALA,
            Intervals.ETERNITY,
            ImmutableList.of("2017-10-15T20:19:12.565Z", "2017-10-16T20:19:12.565Z")
        )
    );
    sqlSegmentsMetadataManager.poll();

    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment1, koalaSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsInIntervalWithEmptyVersions() throws Exception
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-16T20:19:12.565Z"
    );

    // Overshadowed by koalaSegment2
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishUnusedSegments(koalaSegment1, koalaSegment2, koalaSegment3);

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
    Assert.assertEquals(
        0,
        sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegmentsInInterval(
            TestDataSource.KOALA,
            Intervals.of("2017/2018"),
            ImmutableList.of()
        )
    );
    sqlSegmentsMetadataManager.poll();

    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsInEternityIntervalWithEmptyVersions() throws Exception
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-16T20:19:12.565Z"
    );

    // Overshadowed by koalaSegment2
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishUnusedSegments(koalaSegment1, koalaSegment2, koalaSegment3);

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
    Assert.assertEquals(
        0,
        sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegmentsInInterval(
            TestDataSource.KOALA,
            Intervals.ETERNITY,
            ImmutableList.of()
        )
    );
    sqlSegmentsMetadataManager.poll();

    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsInFiniteIntervalWithVersions() throws Exception
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-16T20:19:12.565Z"
    );

    // Overshadowed by koalaSegment2
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishUnusedSegments(koalaSegment1, koalaSegment2, koalaSegment3);

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
    Assert.assertEquals(
        2,
        sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegmentsInInterval(
            TestDataSource.KOALA,
            Intervals.of("2017-10-15/2017-10-18"),
            ImmutableList.of("2017-10-15T20:19:12.565Z", "2017-10-16T20:19:12.565Z")
        )
    );
    sqlSegmentsMetadataManager.poll();

    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment1, koalaSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsWithNonExistentVersions() throws Exception
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-16T20:19:12.565Z"
    );

    // Overshadowed by koalaSegment2
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishUnusedSegments(koalaSegment1, koalaSegment2, koalaSegment3);

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
    Assert.assertEquals(
        0,
        sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegmentsInInterval(
            TestDataSource.KOALA,
            Intervals.ETERNITY,
            ImmutableList.of("foo", "bar")
        )
    );
    sqlSegmentsMetadataManager.poll();

    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsInvalidDataSource() throws Exception
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createNewSegment1(TestDataSource.KOALA);
    final DataSegment koalaSegment2 = createNewSegment1(TestDataSource.KOALA);

    publishUnusedSegments(koalaSegment1, koalaSegment2);
    final ImmutableSet<SegmentId> segmentIds =
        ImmutableSet.of(koalaSegment1.getId(), koalaSegment2.getId());
    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );

    MatcherAssert.assertThat(
        Assert.assertThrows(
            DruidException.class,
            () -> sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegments("wrongDataSource", segmentIds)
        ),
        DruidExceptionMatcher
            .invalidInput()
            .expectMessageContains("Could not find segment IDs")
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsWithInvalidSegmentIds()
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createNewSegment1(TestDataSource.KOALA);
    final DataSegment koalaSegment2 = createNewSegment1(TestDataSource.KOALA);

    final ImmutableSet<SegmentId> segmentIds =
        ImmutableSet.of(koalaSegment1.getId(), koalaSegment2.getId());
    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );

    MatcherAssert.assertThat(
        Assert.assertThrows(
            DruidException.class,
            () -> sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegments(TestDataSource.KOALA, segmentIds)
        ),
        DruidExceptionMatcher
            .invalidInput()
            .expectMessageContains("Could not find segment IDs")
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsInInterval() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createNewSegment1(TestDataSource.KOALA);
    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-16T20:19:12.565Z"
    );
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-19T00:00:00.000/2017-10-20T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    // Overshadowed by koalaSegment2
    final DataSegment koalaSegment4 = createNewSegment2(TestDataSource.KOALA);

    publishUnusedSegments(koalaSegment1, koalaSegment2, koalaSegment3, koalaSegment4);
    final Interval theInterval = Intervals.of("2017-10-15T00:00:00.000/2017-10-18T00:00:00.000");

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );

    // 2 out of 3 segments match the interval
    Assert.assertEquals(2, sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegmentsInInterval(TestDataSource.KOALA, theInterval, null));

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment1, koalaSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUsedNonOvershadowedSegmentsInIntervalWithOverlappingInterval() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-16T20:19:12.565Z"
    );

    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-19T00:00:00.000/2017-10-22T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    // Overshadowed by koalaSegment2
    final DataSegment koalaSegment4 = createNewSegment2(TestDataSource.KOALA);

    publishUnusedSegments(koalaSegment1, koalaSegment2, koalaSegment3, koalaSegment4);
    final Interval theInterval = Intervals.of("2017-10-16T00:00:00.000/2017-10-20T00:00:00.000");

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );

    // 1 out of 3 segments match the interval, other 2 overlap, only the segment fully contained will be marked unused
    Assert.assertEquals(1, sqlSegmentsMetadataManager.markAsUsedNonOvershadowedSegmentsInInterval(TestDataSource.KOALA, theInterval, null));

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkSegmentsAsUnused() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createNewSegment1(TestDataSource.KOALA);
    final DataSegment koalaSegment2 = createNewSegment1(TestDataSource.KOALA);

    publishSegment(koalaSegment1);
    publishSegment(koalaSegment2);

    final ImmutableSet<SegmentId> segmentIds =
        ImmutableSet.of(koalaSegment1.getId(), koalaSegment1.getId());

    Assert.assertEquals(segmentIds.size(), sqlSegmentsMetadataManager.markSegmentsAsUnused(segmentIds));
    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUnusedSegmentsInInterval() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createNewSegment1(TestDataSource.KOALA);
    final DataSegment koalaSegment2 = createNewSegment2(TestDataSource.KOALA);
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-19T00:00:00.000/2017-10-20T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishSegment(koalaSegment1);
    publishSegment(koalaSegment2);
    publishSegment(koalaSegment3);
    final Interval theInterval = Intervals.of("2017-10-15T00:00:00.000/2017-10-18T00:00:00.000");

    // 2 out of 3 segments match the interval
    Assert.assertEquals(2, sqlSegmentsMetadataManager.markAsUnusedSegmentsInInterval(TestDataSource.KOALA, theInterval, null));

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment3),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUnusedSegmentsInIntervalAndVersions() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DateTime now = DateTimes.nowUtc();
    final String v1 = now.toString();
    final String v2 = now.plus(Duration.standardDays(1)).toString();

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-16T00:00:00.000",
        v1
    );
    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        v2
    );
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-19T00:00:00.000/2017-10-20T00:00:00.000",
        v2
    );

    publishSegment(koalaSegment1);
    publishSegment(koalaSegment2);
    publishSegment(koalaSegment3);
    final Interval theInterval = Intervals.of("2017-10-15/2017-10-18");

    Assert.assertEquals(
        2,
        sqlSegmentsMetadataManager.markAsUnusedSegmentsInInterval(
            TestDataSource.KOALA,
            theInterval,
            ImmutableList.of(v1, v2)
        )
    );

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment3),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUnusedSegmentsInIntervalAndNonExistentVersions() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DateTime now = DateTimes.nowUtc();
    final String v1 = now.toString();
    final String v2 = now.plus(Duration.standardDays(1)).toString();

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-16T00:00:00.000",
        v1
    );
    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        v2
    );
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-19T00:00:00.000/2017-10-20T00:00:00.000",
        v2
    );

    publishSegment(koalaSegment1);
    publishSegment(koalaSegment2);
    publishSegment(koalaSegment3);
    final Interval theInterval = Intervals.of("2017-10-15/2017-10-18");

    Assert.assertEquals(
        0,
        sqlSegmentsMetadataManager.markAsUnusedSegmentsInInterval(
            TestDataSource.KOALA,
            theInterval,
            ImmutableList.of("foo", "bar", "baz")
        )
    );

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment1, koalaSegment2, koalaSegment3),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUnusedSegmentsInIntervalWithEmptyVersions() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DateTime now = DateTimes.nowUtc();
    final String v1 = now.toString();
    final String v2 = now.plus(Duration.standardDays(1)).toString();

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-16T00:00:00.000",
        v1
    );
    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        v2
    );
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-19T00:00:00.000/2017-10-20T00:00:00.000",
        v2
    );

    publishSegment(koalaSegment1);
    publishSegment(koalaSegment2);
    publishSegment(koalaSegment3);
    final Interval theInterval = Intervals.of("2017-10-15/2017-10-18");

    Assert.assertEquals(
        0,
        sqlSegmentsMetadataManager.markAsUnusedSegmentsInInterval(
            TestDataSource.KOALA,
            theInterval,
            ImmutableList.of()
        )
    );

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment1, koalaSegment2, koalaSegment3),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUnusedSegmentsInEternityIntervalWithEmptyVersions() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DateTime now = DateTimes.nowUtc();
    final String v1 = now.toString();
    final String v2 = now.plus(Duration.standardDays(1)).toString();

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-16T00:00:00.000",
        v1
    );
    final DataSegment koalaSegment2 = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        v2
    );
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-19T00:00:00.000/2017-10-20T00:00:00.000",
        v2
    );

    publishSegment(koalaSegment1);
    publishSegment(koalaSegment2);
    publishSegment(koalaSegment3);
    final Interval theInterval = Intervals.of("2017-10-15/2017-10-18");

    Assert.assertEquals(
        0,
        sqlSegmentsMetadataManager.markAsUnusedSegmentsInInterval(
            TestDataSource.KOALA,
            theInterval,
            ImmutableList.of()
        )
    );

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment1, koalaSegment2, koalaSegment3),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testMarkAsUnusedSegmentsInIntervalWithOverlappingInterval() throws IOException
  {
    publishWikiSegments();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.poll();
    Assert.assertTrue(sqlSegmentsMetadataManager.isPollingDatabasePeriodically());

    final DataSegment koalaSegment1 = createSegment(
        TestDataSource.KOALA,
        "2017-10-15T00:00:00.000/2017-10-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );
    final DataSegment koalaSegment2 = createNewSegment2(TestDataSource.KOALA);
    final DataSegment koalaSegment3 = createSegment(
        TestDataSource.KOALA,
        "2017-10-19T00:00:00.000/2017-10-22T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishSegment(koalaSegment1);
    publishSegment(koalaSegment2);
    publishSegment(koalaSegment3);
    final Interval theInterval = Intervals.of("2017-10-16T00:00:00.000/2017-10-20T00:00:00.000");

    // 1 out of 3 segments match the interval, other 2 overlap, only the segment fully contained will be marked unused
    Assert.assertEquals(1, sqlSegmentsMetadataManager.markAsUnusedSegmentsInInterval(TestDataSource.KOALA, theInterval, null));

    sqlSegmentsMetadataManager.poll();
    Assert.assertEquals(
        ImmutableSet.of(wikiSegment1, wikiSegment2, koalaSegment1, koalaSegment3),
        ImmutableSet.copyOf(sqlSegmentsMetadataManager.iterateAllUsedSegments())
    );
  }

  @Test
  public void testStopAndStart()
  {
    // Simulate successive losing and getting the coordinator leadership
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.stopPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.startPollingDatabasePeriodically();
    sqlSegmentsMetadataManager.stopPollingDatabasePeriodically();
  }

  @Test
  public void testIterateAllUsedNonOvershadowedSegmentsForDatasourceInterval() throws Exception
  {
    publishWikiSegments();
    final Interval theInterval = Intervals.of("2012-03-15T00:00:00.000/2012-03-20T00:00:00.000");

    // Re-create SqlSegmentsMetadataManager with a higher poll duration
    final SegmentsMetadataManagerConfig config = new SegmentsMetadataManagerConfig(Period.seconds(1), null);
    sqlSegmentsMetadataManager = new SqlSegmentsMetadataManager(
        jsonMapper,
        Suppliers.ofInstance(config),
        derbyConnectorRule.metadataTablesConfigSupplier(),
        derbyConnectorRule.getConnector(),
        segmentSchemaCache,
        CentralizedDatasourceSchemaConfig.create(),
        NoopServiceEmitter.instance()
    );
    sqlSegmentsMetadataManager.start();

    Optional<Iterable<DataSegment>> segments = sqlSegmentsMetadataManager
        .iterateAllUsedNonOvershadowedSegmentsForDatasourceInterval(TestDataSource.WIKI, theInterval, true);
    Assert.assertTrue(segments.isPresent());
    Set<DataSegment> dataSegmentSet = ImmutableSet.copyOf(segments.get());
    Assert.assertEquals(1, dataSegmentSet.size());
    Assert.assertTrue(dataSegmentSet.contains(wikiSegment1));

    final DataSegment wikiSegment3 = createSegment(
        TestDataSource.WIKI,
        "2012-03-16T00:00:00.000/2012-03-17T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );
    publishSegment(wikiSegment3);

    // New segment is not returned since we call without force poll
    segments = sqlSegmentsMetadataManager
        .iterateAllUsedNonOvershadowedSegmentsForDatasourceInterval(TestDataSource.WIKI, theInterval, false);
    Assert.assertTrue(segments.isPresent());
    dataSegmentSet = ImmutableSet.copyOf(segments.get());
    Assert.assertEquals(1, dataSegmentSet.size());
    Assert.assertTrue(dataSegmentSet.contains(wikiSegment1));

    // New segment is returned since we call with force poll
    segments = sqlSegmentsMetadataManager
        .iterateAllUsedNonOvershadowedSegmentsForDatasourceInterval(TestDataSource.WIKI, theInterval, true);
    Assert.assertTrue(segments.isPresent());
    dataSegmentSet = ImmutableSet.copyOf(segments.get());
    Assert.assertEquals(2, dataSegmentSet.size());
    Assert.assertTrue(dataSegmentSet.contains(wikiSegment1));
    Assert.assertTrue(dataSegmentSet.contains(wikiSegment3));
  }

  @Test
  public void testPopulateUsedFlagLastUpdated() throws IOException
  {
    allowUsedFlagLastUpdatedToBeNullable();
    final DataSegment koalaSegment = createSegment(
        TestDataSource.KOALA,
        "2017-10-17T00:00:00.000/2017-10-18T00:00:00.000",
        "2017-10-15T20:19:12.565Z"
    );

    publishUnusedSegments(koalaSegment);
    updateUsedStatusLastUpdatedToNull(koalaSegment);

    Assert.assertEquals(1, getCountOfRowsWithLastUsedNull());
    sqlSegmentsMetadataManager.populateUsedFlagLastUpdated();
    Assert.assertEquals(0, getCountOfRowsWithLastUsedNull());
  }

  private int getCountOfRowsWithLastUsedNull()
  {
    return derbyConnectorRule.getConnector().retryWithHandle(
        handle -> handle.select(
            StringUtils.format(
                "SELECT ID FROM %1$s WHERE USED_STATUS_LAST_UPDATED IS NULL",
                derbyConnectorRule.segments().getTableName()
            )
        ).size()
    );
  }

  private void updateSegmentPayload(DataSegment segment, byte[] payload)
  {
    derbyConnectorRule.segments().update(
        "UPDATE %1$s SET PAYLOAD = ? WHERE ID = ?",
        payload,
        segment.getId().toString()
    );
  }

  private void updateUsedStatusLastUpdatedToNull(DataSegment segment)
  {
    derbyConnectorRule.segments().update(
        "UPDATE %1$s SET USED_STATUS_LAST_UPDATED = NULL WHERE ID = ?",
        segment.getId().toString()
    );
  }

  /**
   * Alters the column used_status_last_updated to be nullable. This is used to
   * test backward compatibility with versions of Druid without this column
   * present in the segments table.
   */
  private void allowUsedFlagLastUpdatedToBeNullable()
  {
    derbyConnectorRule.segments().update(
        "ALTER TABLE %1$s ALTER COLUMN USED_STATUS_LAST_UPDATED NULL"
    );
  }
}
