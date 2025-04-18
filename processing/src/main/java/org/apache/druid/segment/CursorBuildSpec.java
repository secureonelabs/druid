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

package org.apache.druid.segment;

import com.google.common.base.Preconditions;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.query.OrderBy;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.filter.Filter;
import org.apache.druid.segment.filter.Filters;
import org.apache.druid.segment.vector.VectorCursor;
import org.apache.druid.utils.CollectionUtils;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Defines the plan for how the reader will scan, filter, transform, group and aggregate, and or order the data from a
 * {@link CursorFactory} so that an appropriate {@link CursorHolder} can be constructed. The {@link CursorBuildSpec}
 * includes physical and virtual columns will be read from the data, a {@link Filter} so that the {@link Cursor} and
 * {@link VectorCursor} only provide matching rows, and details on how the scanned, transformed, and filtered data will
 * be grouped, aggegated, and ordered if applicable to allow {@link CursorHolder} construction to provide optimized
 * {@link Cursor} or {@link VectorCursor} such as providing cursors for pre-aggregated data with
 * {@link org.apache.druid.segment.projections.Projections}.
 *
 * @see #getFilter()
 * @see #getInterval()
 * @see #getPhysicalColumns()
 * @see #getVirtualColumns()
 * @see #getGroupingColumns()
 * @see #getAggregators()
 * @see #getPreferredOrdering()
 */
public class CursorBuildSpec
{
  public static final CursorBuildSpec FULL_SCAN = builder().build();

  public static CursorBuildSpecBuilder builder()
  {
    return new CursorBuildSpecBuilder();
  }

  public static CursorBuildSpecBuilder builder(CursorBuildSpec spec)
  {
    return new CursorBuildSpecBuilder(spec);
  }

  @Nullable
  private final Filter filter;
  private final Interval interval;
  @Nullable
  private final List<String> groupingColumns;
  private final VirtualColumns virtualColumns;
  @Nullable
  private final List<AggregatorFactory> aggregators;
  private final List<OrderBy> preferredOrdering;

  private final QueryContext queryContext;

  private final boolean isAggregate;

  @Nullable
  private final Set<String> physicalColumns;

  @Nullable
  private final QueryMetrics<?> queryMetrics;

  public CursorBuildSpec(
      @Nullable Filter filter,
      Interval interval,
      @Nullable Set<String> physicalColumns,
      VirtualColumns virtualColumns,
      @Nullable List<String> groupingColumns,
      @Nullable List<AggregatorFactory> aggregators,
      List<OrderBy> preferredOrdering,
      QueryContext queryContext,
      @Nullable QueryMetrics<?> queryMetrics
  )
  {
    this.filter = filter;
    this.interval = Preconditions.checkNotNull(interval, "interval");
    this.virtualColumns = Preconditions.checkNotNull(virtualColumns, "virtualColumns");
    this.physicalColumns = physicalColumns;
    this.groupingColumns = groupingColumns;
    this.aggregators = aggregators;
    this.preferredOrdering = Preconditions.checkNotNull(preferredOrdering, "preferredOrdering");
    this.queryContext = Preconditions.checkNotNull(queryContext, "queryContext");
    this.queryMetrics = queryMetrics;
    this.isAggregate = !CollectionUtils.isNullOrEmpty(groupingColumns) || !CollectionUtils.isNullOrEmpty(aggregators);
  }

  /**
   * {@link Filter} to supply to the {@link CursorHolder}. Only rows which match will be available through the
   * selectors created from the {@link Cursor} or {@link org.apache.druid.segment.vector.VectorCursor}
   */
  @Nullable
  public Filter getFilter()
  {
    return filter;
  }

  /**
   * {@link Interval} filter to supply to the {@link CursorHolder}. Only rows whose timestamps fall within this range
   * will be available through the selectors created from the {@link Cursor} or
   * {@link org.apache.druid.segment.vector.VectorCursor}
   */
  public Interval getInterval()
  {
    return interval;
  }

  /**
   * Set of physical columns required from a cursor. If null, and {@link #groupingColumns} is null or empty and
   * {@link #aggregators} is null or empty, then a {@link CursorHolder} must assume that ALL columns are required.
   */
  @Nullable
  public Set<String> getPhysicalColumns()
  {
    return physicalColumns;
  }

  /**
   * Any {@link VirtualColumns} which are used by a query engine to assist in
   * determining if {@link CursorHolder#canVectorize()}
   */
  public VirtualColumns getVirtualColumns()
  {
    return virtualColumns;
  }

  /**
   * Any columns which will be used for grouping by a query engine for the {@link CursorHolder}, useful for
   * specializing the {@link Cursor} or {@link org.apache.druid.segment.vector.VectorCursor} if any pre-aggregated
   * data is available.
   */
  @Nullable
  public List<String> getGroupingColumns()
  {
    return groupingColumns;
  }

  /**
   * Any {@link AggregatorFactory} which will be used by a query engine for the {@link CursorHolder}, useful
   * to assist in determining if {@link CursorHolder#canVectorize()}, as well as specializing the {@link Cursor} or
   * {@link org.apache.druid.segment.vector.VectorCursor} if any pre-aggregated data is available.
   */
  @Nullable
  public List<AggregatorFactory> getAggregators()
  {
    return aggregators;
  }

  /**
   * List of all {@link OrderBy} columns which a query engine will use to sort its results to supply to the
   * {@link CursorHolder}, which can allow optimization of the provided {@link Cursor} or
   * {@link org.apache.druid.segment.vector.VectorCursor} if data matching the preferred ordering is available.
   * <p>
   * If not specified, the cursor will advance in the native order of the underlying data.
   */
  public List<OrderBy> getPreferredOrdering()
  {
    return preferredOrdering;
  }

  /**
   * {@link QueryContext} for the {@link CursorHolder} to provide a mechanism to push various data into
   * {@link Cursor} and {@link org.apache.druid.segment.vector.VectorCursor} such as
   * {@link org.apache.druid.query.QueryContexts#VECTORIZE_KEY} and
   * {@link org.apache.druid.query.QueryContexts#VECTOR_SIZE_KEY}
   */
  public QueryContext getQueryContext()
  {
    return queryContext;
  }

  /**
   * {@link QueryMetrics} to use for measuring things involved with {@link Cursor} and
   * {@link org.apache.druid.segment.vector.VectorCursor} creation.
   */
  @Nullable
  public QueryMetrics<?> getQueryMetrics()
  {
    return queryMetrics;
  }

  /**
   * Returns true if {@link #getGroupingColumns()} is not null or empty and/or {@link #getAggregators()} is not null or
   * empty. This method is useful for quickly checking if it is worth considering if a {@link CursorFactory} should
   * attempt to produce a {@link CursorHolder} that is {@link CursorHolder#isPreAggregated()} to satisfy the build spec.
   */
  public boolean isAggregate()
  {
    return isAggregate;
  }

  /**
   * Returns true if the supplied ordering matches {@link #getPreferredOrdering()}, meaning that the supplied ordering
   * has everything which is in the preferred ordering in the same direction and order. The supplied ordering may have
   * additional columns beyond the preferred ordering and still satisify this method.
   */
  public boolean isCompatibleOrdering(List<OrderBy> ordering)
  {
    // if the build spec doesn't prefer an ordering, any order is ok
    if (preferredOrdering.isEmpty()) {
      return true;
    }
    // all columns must be present in ordering if the build spec specifies them
    if (ordering.size() < preferredOrdering.size()) {
      return false;
    }
    for (int i = 0; i < preferredOrdering.size(); i++) {
      if (!ordering.get(i).equals(preferredOrdering.get(i))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean equals(Object o)
  {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CursorBuildSpec that = (CursorBuildSpec) o;
    return isAggregate == that.isAggregate &&
           Objects.equals(filter, that.filter) &&
           Objects.equals(interval, that.interval) &&
           Objects.equals(groupingColumns, that.groupingColumns) &&
           Objects.equals(virtualColumns, that.virtualColumns) &&
           Objects.equals(aggregators, that.aggregators) &&
           Objects.equals(preferredOrdering, that.preferredOrdering) &&
           Objects.equals(queryContext, that.queryContext) &&
           Objects.equals(physicalColumns, that.physicalColumns) &&
           Objects.equals(queryMetrics, that.queryMetrics);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(
        filter,
        interval,
        groupingColumns,
        virtualColumns,
        aggregators,
        preferredOrdering,
        queryContext,
        isAggregate,
        physicalColumns,
        queryMetrics
    );
  }

  public static class CursorBuildSpecBuilder
  {
    @Nullable
    private Filter filter;
    private Interval interval = Intervals.ETERNITY;
    private VirtualColumns virtualColumns = VirtualColumns.EMPTY;
    @Nullable
    private Set<String> physicalColumns;

    @Nullable
    private List<String> groupingColumns;
    @Nullable
    private List<AggregatorFactory> aggregators;
    private List<OrderBy> preferredOrdering = Collections.emptyList();

    private QueryContext queryContext = QueryContext.empty();

    @Nullable
    private QueryMetrics<?> queryMetrics;

    private CursorBuildSpecBuilder()
    {
      // initialize with defaults
    }

    private CursorBuildSpecBuilder(CursorBuildSpec buildSpec)
    {
      this.filter = buildSpec.filter;
      this.interval = buildSpec.interval;
      this.physicalColumns = buildSpec.physicalColumns;
      this.virtualColumns = buildSpec.virtualColumns;
      this.groupingColumns = buildSpec.groupingColumns;
      this.aggregators = buildSpec.aggregators;
      this.preferredOrdering = buildSpec.preferredOrdering;
      this.queryContext = buildSpec.queryContext;
      this.queryMetrics = buildSpec.queryMetrics;
    }

    /**
     * @see CursorBuildSpec#getFilter() for usage.
     */
    @Nullable
    public Filter getFilter()
    {
      return filter;
    }

    /**
     * @see CursorBuildSpec#getFilter() for usage. All {@link Filter#getRequiredColumns()} must be explicitly added to
     * {@link #virtualColumns} if virtual or, if set to a non-null value, {@link #physicalColumns}.
     */
    public CursorBuildSpecBuilder setFilter(@Nullable Filter filter)
    {
      this.filter = filter;
      return this;
    }

    /**
     * @see CursorBuildSpec#getInterval() for usage.
     */
    public Interval getInterval()
    {
      return interval;
    }

    /**
     * @see CursorBuildSpec#getInterval() for usage.
     */
    public CursorBuildSpecBuilder setInterval(Interval interval)
    {
      this.interval = interval;
      return this;
    }

    /**
     * @see CursorBuildSpec#getPhysicalColumns() for usage.
     */
    @Nullable
    public Set<String> getPhysicalColumns()
    {
      return physicalColumns;
    }

    /**
     * @see CursorBuildSpec#getPhysicalColumns() for usage. The backing value is not automatically populated by calls to
     * {@link #setFilter(Filter)}, {@link #setVirtualColumns(VirtualColumns)}, {@link #setAggregators(List)}, or
     * {@link #setPreferredOrdering(List)}, so this must be explicitly set for all required physical columns. If set to
     * null, and {@link #groupingColumns} is null or empty and {@link #aggregators} is null or empty, then a
     * {@link CursorHolder} must assume that ALL columns are required
     */
    public CursorBuildSpecBuilder setPhysicalColumns(@Nullable Set<String> physicalColumns)
    {
      this.physicalColumns = physicalColumns;
      return this;
    }

    /**
     * @see CursorBuildSpec#getVirtualColumns() for usage. All {@link VirtualColumn#requiredColumns()} must be
     * explicitly added to {@link #physicalColumns} if it is set to a non-null value.
     */
    public VirtualColumns getVirtualColumns()
    {
      return virtualColumns;
    }

    /**
     * @see CursorBuildSpec#getVirtualColumns() for usage.
     */
    public CursorBuildSpecBuilder setVirtualColumns(VirtualColumns virtualColumns)
    {
      this.virtualColumns = virtualColumns;
      return this;
    }

    /**
     * @see CursorBuildSpec#getGroupingColumns() for usage.
     */
    @Nullable
    public List<String> getGroupingColumns()
    {
      return groupingColumns;
    }

    /**
     * @see CursorBuildSpec#getGroupingColumns() for usage.
     */
    public CursorBuildSpecBuilder setGroupingColumns(@Nullable List<String> groupingColumns)
    {
      this.groupingColumns = groupingColumns;
      return this;
    }

    /**
     * @see CursorBuildSpec#getAggregators() for usage.
     */
    @Nullable
    public List<AggregatorFactory> getAggregators()
    {
      return aggregators;
    }

    /**
     * @see CursorBuildSpec#getAggregators() for usage. All {@link AggregatorFactory#requiredFields()} must be
     * explicitly added to {@link #virtualColumns} if virtual or, if set to a non-null value, {@link #physicalColumns}.
     */
    public CursorBuildSpecBuilder setAggregators(@Nullable List<AggregatorFactory> aggregators)
    {
      this.aggregators = aggregators;
      return this;
    }

    /**
     * @see CursorBuildSpec#getPreferredOrdering() for usage.
     */
    public List<OrderBy> getPreferredOrdering()
    {
      return preferredOrdering;
    }

    /**
     * @see CursorBuildSpec#getPreferredOrdering() for usage. All {@link OrderBy#getColumnName()} must be explicitly
     * added to {@link #virtualColumns} if virtual or, if set to a non-null value, {@link #physicalColumns}.
     */
    public CursorBuildSpecBuilder setPreferredOrdering(List<OrderBy> preferredOrdering)
    {
      this.preferredOrdering = preferredOrdering;
      return this;
    }

    /**
     * @see CursorBuildSpec#getQueryContext()
     */
    public QueryContext getQueryContext()
    {
      return queryContext;
    }

    /**
     * @see CursorBuildSpec#getQueryContext()
     */
    public CursorBuildSpecBuilder setQueryContext(QueryContext queryContext)
    {
      this.queryContext = queryContext;
      return this;
    }

    /**
     * @see CursorBuildSpec#getQueryMetrics()
     */
    @Nullable
    public QueryMetrics<?> getQueryMetrics()
    {
      return queryMetrics;
    }

    /**
     * @see CursorBuildSpec#getQueryMetrics()
     */
    public CursorBuildSpecBuilder setQueryMetrics(@Nullable QueryMetrics<?> queryMetrics)
    {
      this.queryMetrics = queryMetrics;
      return this;
    }



    /**
     * Adds a {@link Filter} to the builder, if {@link #filter} is already set, the existing and new filters will be
     * combined with an {@link org.apache.druid.segment.filter.AndFilter}. If {@link #physicalColumns} is set,
     * {@link Filter#getRequiredColumns()} which are not present in {@link #virtualColumns} will be added to the
     * existing set of {@link #physicalColumns}.
     */
    public CursorBuildSpecBuilder andFilter(
        Filter filterToAdd
    )
    {
      DruidException.conditionalDefensive(filterToAdd != null, "filterToAdd must not be null");
      final Filter newFilter;
      final Set<String> newPhysicalColumns;
      if (filter == null) {
        newFilter = filterToAdd;
      } else {
        newFilter = Filters.and(Arrays.asList(filter, filterToAdd));
      }
      if (physicalColumns != null) {
        newPhysicalColumns = new HashSet<>(physicalColumns);
        for (String column : filterToAdd.getRequiredColumns()) {
          if (!virtualColumns.exists(column)) {
            newPhysicalColumns.add(column);
          }
        }
      } else {
        newPhysicalColumns = null;
      }
      return setFilter(newFilter).setPhysicalColumns(newPhysicalColumns);
    }

    public CursorBuildSpec build()
    {
      return new CursorBuildSpec(
          filter,
          interval,
          physicalColumns,
          virtualColumns,
          groupingColumns,
          aggregators,
          preferredOrdering,
          queryContext,
          queryMetrics
      );
    }
  }
}
