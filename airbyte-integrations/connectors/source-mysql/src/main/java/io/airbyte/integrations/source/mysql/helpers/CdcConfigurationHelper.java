/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.mysql.helpers;

import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.commons.functional.CheckedConsumer;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.jdbc.JdbcDatabase;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CdcConfigurationHelper {

  private static final Logger LOGGER = LoggerFactory.getLogger(CdcConfigurationHelper.class);
  private static final String CDC_OFFSET = "mysql_cdc_offset";
  private static final String LOG_BIN = "log_bin";
  private static final String BINLOG_FORMAT = "binlog_format";
  private static final String BINLOG_ROW_IMAGE = "binlog_row_image";

  public static void checkBinlog(JsonNode offset, JdbcDatabase database) {
    Optional<String> binlogOptional = getBinlog(offset);
    if (binlogOptional.isPresent()) {
      String binlog = binlogOptional.get();
      if (isBinlogAvailable(binlog, database)) {
        LOGGER.info("""
                    Binlog %s is available""".formatted(binlog));
      } else {
        String error =
            """
            Binlog %s is not available. This is a critical error, it means that requested binlog is not present on mysql server. " To fix data synchronization you need to reset your data. Please check binlog retention policy configurations."""
                .formatted(binlog);
        LOGGER.error(error);
        throw new RuntimeException("""
                                   Binlog %s is not available.""".formatted(binlog));
      }
    }
  }

  public static List<CheckedConsumer<JdbcDatabase, Exception>> getCheckOperations() {
    return List.of(getCheckOperation(LOG_BIN, "ON"),
        getCheckOperation(BINLOG_FORMAT, "ROW"),
        getCheckOperation(BINLOG_ROW_IMAGE, "FULL"));

  }

  private static boolean isBinlogAvailable(String binlog, JdbcDatabase database) {
    try {
      List<String> binlogs = database.resultSetQuery(connection -> connection.createStatement().executeQuery("SHOW BINARY LOGS"),
          resultSet -> resultSet.getString("Log_name")).collect(Collectors.toList());

      return !binlog.isEmpty() && binlogs.stream().anyMatch(e -> e.equals(binlog));
    } catch (SQLException e) {
      LOGGER.error("Can not get binlog list. Error: ", e);
      throw new RuntimeException(e);
    }
  }

  private static Optional<String> getBinlog(JsonNode offset) {
    JsonNode node = offset.get(CDC_OFFSET);
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> jsonField = fields.next();
      return Optional.ofNullable(Jsons.deserialize(jsonField.getValue().asText()).path("file").asText());
    }
    return Optional.empty();
  }

  private static CheckedConsumer<JdbcDatabase, Exception> getCheckOperation(String name, String value) {
    return database -> {
      final List<String> result = database.resultSetQuery(connection -> {
        final String sql = """
                           show variables where Variable_name = '%s'""".formatted(name);

        return connection.createStatement().executeQuery(sql);
      }, resultSet -> resultSet.getString("Value")).collect(toList());

      if (result.size() != 1) {
        throw new RuntimeException("""
                                   Could not query the variable %s""".formatted(name));
      }

      final String resultValue = result.get(0);
      if (!resultValue.equalsIgnoreCase(value)) {
        throw new RuntimeException("""
                                   The variable %s should be set to %s, but it is : %s""".formatted(name, value, resultValue));
      }
    };
  }

}
