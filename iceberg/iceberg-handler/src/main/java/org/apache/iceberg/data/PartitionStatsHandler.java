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

package org.apache.iceberg.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.ImmutableGenericPartitionStatisticsFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionStatisticsFile;
import org.apache.iceberg.PartitionStats;
import org.apache.iceberg.PartitionStatsUtil;
import org.apache.iceberg.Partitioning;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.avro.InternalReader;
import org.apache.iceberg.avro.InternalWriter;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.types.Types.IntegerType;
import org.apache.iceberg.types.Types.LongType;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.iceberg.types.Types.StructType;
import org.apache.iceberg.util.SnapshotUtil;

// TODO: remove class once Iceberg PR #11216 is merged and released

/**
 * Computes, writes and reads the {@link PartitionStatisticsFile}. Uses generic readers and writers
 * to support writing and reading of the stats in table default format.
 */
public final class PartitionStatsHandler {

  private PartitionStatsHandler() {
  }

  public enum Column {
    PARTITION(0),
    SPEC_ID(1),
    DATA_RECORD_COUNT(2),
    DATA_FILE_COUNT(3),
    TOTAL_DATA_FILE_SIZE_IN_BYTES(4),
    POSITION_DELETE_RECORD_COUNT(5),
    POSITION_DELETE_FILE_COUNT(6),
    EQUALITY_DELETE_RECORD_COUNT(7),
    EQUALITY_DELETE_FILE_COUNT(8),
    TOTAL_RECORD_COUNT(9),
    LAST_UPDATED_AT(10),
    LAST_UPDATED_SNAPSHOT_ID(11);

    private final int id;

    Column(int id) {
      this.id = id;
    }

    public int id() {
      return id;
    }
  }

  /**
   * Generates the partition stats file schema based on a given partition type.
   *
   * <p>Note: Provide the unified partition schema type as mentioned in the spec.
   *
   * @param partitionType unified partition schema type.
   * @return a schema that corresponds to the provided unified partition type.
   */
  public static Schema schema(StructType partitionType) {
    Preconditions.checkState(!partitionType.fields().isEmpty(), "table must be partitioned");
    return new Schema(
        NestedField.required(1, Column.PARTITION.name(), partitionType),
        NestedField.required(2, Column.SPEC_ID.name(), IntegerType.get()),
        NestedField.required(3, Column.DATA_RECORD_COUNT.name(), LongType.get()),
        NestedField.required(4, Column.DATA_FILE_COUNT.name(), IntegerType.get()),
        NestedField.required(5, Column.TOTAL_DATA_FILE_SIZE_IN_BYTES.name(), LongType.get()),
        NestedField.optional(6, Column.POSITION_DELETE_RECORD_COUNT.name(), LongType.get()),
        NestedField.optional(7, Column.POSITION_DELETE_FILE_COUNT.name(), IntegerType.get()),
        NestedField.optional(8, Column.EQUALITY_DELETE_RECORD_COUNT.name(), LongType.get()),
        NestedField.optional(9, Column.EQUALITY_DELETE_FILE_COUNT.name(), IntegerType.get()),
        NestedField.optional(10, Column.TOTAL_RECORD_COUNT.name(), LongType.get()),
        NestedField.optional(11, Column.LAST_UPDATED_AT.name(), LongType.get()),
        NestedField.optional(12, Column.LAST_UPDATED_SNAPSHOT_ID.name(), LongType.get()));
  }

  /**
   * Computes and writes the {@link PartitionStatisticsFile} for a given table's current snapshot.
   *
   * @param table The {@link Table} for which the partition statistics is computed.
   * @return {@link PartitionStatisticsFile} for the current snapshot.
   */
  public static PartitionStatisticsFile computeAndWriteStatsFile(Table table) {
    return computeAndWriteStatsFile(table, null);
  }

  /**
   * Computes and writes the {@link PartitionStatisticsFile} for a given table and branch.
   *
   * @param table The {@link Table} for which the partition statistics is computed.
   * @param branch A branch information to select the required snapshot.
   * @return {@link PartitionStatisticsFile} for the given branch.
   */
  public static PartitionStatisticsFile computeAndWriteStatsFile(Table table, String branch) {
    Snapshot currentSnapshot = SnapshotUtil.latestSnapshot(table, branch);
    if (currentSnapshot == null) {
      Preconditions.checkArgument(
          branch == null, "Couldn't find the snapshot for the branch %s", branch);
      return null;
    }

    StructType partitionType = Partitioning.partitionType(table);
    Collection<PartitionStats> stats = PartitionStatsUtil.computeStats(table, currentSnapshot);
    List<PartitionStats> sortedStats = PartitionStatsUtil.sortStats(stats, partitionType);
    return writePartitionStatsFile(
        table, currentSnapshot.snapshotId(), schema(partitionType), sortedStats.iterator());
  }

  @VisibleForTesting
  static PartitionStatisticsFile writePartitionStatsFile(
      Table table, long snapshotId, Schema dataSchema, Iterator<PartitionStats> records) {
    OutputFile outputFile = newPartitionStatsFile(table, snapshotId);

    try (DataWriter<StructLike> writer = dataWriter(dataSchema, outputFile)) {
      records.forEachRemaining(writer::write);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    return ImmutableGenericPartitionStatisticsFile.builder()
        .snapshotId(snapshotId)
        .path(outputFile.location())
        .fileSizeInBytes(outputFile.toInputFile().getLength())
        .build();
  }

  /**
   * Reads partition statistics from the specified {@link InputFile} using given schema.
   *
   * @param schema The {@link Schema} of the partition statistics file.
   * @param inputFile An {@link InputFile} pointing to the partition stats file.
   */
  public static CloseableIterable<PartitionStats> readPartitionStatsFile(
      Schema schema, InputFile inputFile) {
    CloseableIterable<StructLike> records = dataReader(schema, inputFile);
    return CloseableIterable.transform(records, PartitionStatsHandler::recordToPartitionStats);
  }

  private static FileFormat fileFormat(String fileLocation) {
    return FileFormat.fromString(fileLocation.substring(fileLocation.lastIndexOf(".") + 1));
  }

  private static OutputFile newPartitionStatsFile(Table table, long snapshotId) {
    FileFormat fileFormat = FileFormat.AVRO;
    return table
        .io()
        .newOutputFile(
            ((HasTableOperations) table)
                .operations()
                .metadataFileLocation(
                    fileFormat.addExtension(
                        String.format(Locale.ROOT, "partition-stats-%d", snapshotId))));
  }

  private static DataWriter<StructLike> dataWriter(Schema dataSchema, OutputFile outputFile)
      throws IOException {
    FileFormat fileFormat = fileFormat(outputFile.location());
    switch (fileFormat) {
      case AVRO:
        return Avro.writeData(outputFile)
            .schema(dataSchema)
            .createWriterFunc(InternalWriter::create)
            .overwrite()
            .withSpec(PartitionSpec.unpartitioned())
            .build();
      case PARQUET:
      case ORC:
        // Internal writers are not supported for PARQUET & ORC yet.
      default:
        throw new UnsupportedOperationException("Unsupported file format:" + fileFormat.name());
    }
  }

  private static CloseableIterable<StructLike> dataReader(Schema schema, InputFile inputFile) {
    FileFormat fileFormat = fileFormat(inputFile.location());
    switch (fileFormat) {
      case AVRO:
        return Avro.read(inputFile)
            .project(schema)
            .createReaderFunc(fileSchema -> InternalReader.create(schema))
            .build();
      case PARQUET:
      case ORC:
        // Internal readers are not supported for PARQUET & ORC yet.
      default:
        throw new UnsupportedOperationException("Unsupported file format:" + fileFormat.name());
    }
  }

  private static PartitionStats recordToPartitionStats(StructLike record) {
    PartitionStats stats =
        new PartitionStats(
            record.get(Column.PARTITION.id(), StructLike.class),
            record.get(Column.SPEC_ID.id(), Integer.class));
    stats.set(Column.DATA_RECORD_COUNT.id(), record.get(Column.DATA_RECORD_COUNT.id(), Long.class));
    stats.set(Column.DATA_FILE_COUNT.id(), record.get(Column.DATA_FILE_COUNT.id(), Integer.class));
    stats.set(
        Column.TOTAL_DATA_FILE_SIZE_IN_BYTES.id(),
        record.get(Column.TOTAL_DATA_FILE_SIZE_IN_BYTES.id(), Long.class));
    stats.set(
        Column.POSITION_DELETE_RECORD_COUNT.id(),
        record.get(Column.POSITION_DELETE_RECORD_COUNT.id(), Long.class));
    stats.set(
        Column.POSITION_DELETE_FILE_COUNT.id(),
        record.get(Column.POSITION_DELETE_FILE_COUNT.id(), Integer.class));
    stats.set(
        Column.EQUALITY_DELETE_RECORD_COUNT.id(),
        record.get(Column.EQUALITY_DELETE_RECORD_COUNT.id(), Long.class));
    stats.set(
        Column.EQUALITY_DELETE_FILE_COUNT.id(),
        record.get(Column.EQUALITY_DELETE_FILE_COUNT.id(), Integer.class));
    stats.set(
        Column.TOTAL_RECORD_COUNT.id(), record.get(Column.TOTAL_RECORD_COUNT.id(), Long.class));
    stats.set(Column.LAST_UPDATED_AT.id(), record.get(Column.LAST_UPDATED_AT.id(), Long.class));
    stats.set(
        Column.LAST_UPDATED_SNAPSHOT_ID.id(),
        record.get(Column.LAST_UPDATED_SNAPSHOT_ID.id(), Long.class));
    return stats;
  }
}
