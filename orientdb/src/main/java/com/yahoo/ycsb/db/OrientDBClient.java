/**
 * Copyright (c) 2012 - 2016 YCSB contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.yahoo.ycsb.db;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.orient.client.remote.OEngineRemote;
import com.orientechnologies.orient.client.remote.OServerAdmin;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.dictionary.ODictionary;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.index.OIndexCursor;
import com.orientechnologies.orient.core.intent.OIntentMassiveInsert;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.Status;
import com.yahoo.ycsb.StringByteIterator;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

/**
 * OrientDB client for YCSB framework.
 */
public class OrientDBClient extends DB {

  private static final String CLASS = "usertable";
  protected ODatabaseDocumentTx db;
  private ODictionary<ORecord> dictionary;
  private boolean isRemote = false;

  private static final String URL_PROPERTY = "orientdb.url";

  private static final String USER_PROPERTY = "orientdb.user";
  private static final String USER_PROPERTY_DEFAULT = "admin";

  private static final String PASSWORD_PROPERTY = "orientdb.password";
  private static final String PASSWORD_PROPERTY_DEFAULT = "admin";

  private static final String NEWDB_PROPERTY = "orientdb.newdb";
  private static final String NEWDB_PROPERTY_DEFAULT = "false";

  private static final String STORAGE_TYPE_PROPERTY = "orientdb.remote.storagetype";

  private static final String DO_TRANSACTIONS_PROPERTY = "dotransactions";
  private static final String DO_TRANSACTIONS_PROPERTY_DEFAULT = "true";

  private static final String ORIENTDB_DOCUMENT_TYPE = "document";

  /**
   * Initialize any state for this DB. Called once per DB instance; there is one
   * DB instance per client thread.
   */
  public void init() throws DBException {
    Properties props = getProperties();

    String url = props.getProperty(URL_PROPERTY);
    String user = props.getProperty(USER_PROPERTY, USER_PROPERTY_DEFAULT);
    String password = props.getProperty(PASSWORD_PROPERTY, PASSWORD_PROPERTY_DEFAULT);
    Boolean newdb = Boolean.parseBoolean(props.getProperty(NEWDB_PROPERTY, NEWDB_PROPERTY_DEFAULT));
    String remoteStorageType = props.getProperty(STORAGE_TYPE_PROPERTY);
    Boolean dotransactions = Boolean.parseBoolean(props.getProperty(DO_TRANSACTIONS_PROPERTY, DO_TRANSACTIONS_PROPERTY_DEFAULT));

    if (url == null) {
      throw new DBException(String.format("Required property \"%s\" missing for OrientDBClient", URL_PROPERTY));
    }

    System.err.println("OrientDB loading database url = " + url);

    // If using a remote database, use the OServerAdmin interface to connect
    if (url.startsWith(OEngineRemote.NAME)) {
      isRemote = true;
      if (remoteStorageType == null) {
        throw new DBException("When connecting to a remote OrientDB instance, specify a database storage type (plocal or memory) with " + STORAGE_TYPE_PROPERTY);
      }

      try {
        OServerAdmin server = new OServerAdmin(url).connect(user, password);

        if (server.existsDatabase()) {
          if (newdb && !dotransactions) {
            System.err.println("OrientDB dropping and recreating fresh db on remote server.");
            server.dropDatabase(remoteStorageType);
            server.createDatabase(server.getURL(), ORIENTDB_DOCUMENT_TYPE, remoteStorageType);
          }
        } else {
          System.err.println("OrientDB database not found, creating fresh db");
          server.createDatabase(server.getURL(), ORIENTDB_DOCUMENT_TYPE, remoteStorageType);
        }

        server.close();
        db = new ODatabaseDocumentTx(url).open(user, password);
      } catch (IOException | OException e) {
        throw new DBException(String.format("Error interfacing with %s", url), e);
      }
    } else {
      try {
        db = new ODatabaseDocumentTx(url);
        if (db.exists()) {
          db.open(user, password);
          if (newdb && !dotransactions) {
            System.err.println("OrientDB dropping and recreating fresh db.");
            db.drop();
            db.create();
          }
        } else {
          System.err.println("OrientDB database not found, creating fresh db");
          db.create();
        }
      } catch (ODatabaseException e) {
        throw new DBException(String.format("Error interfacing with %s", url), e);
      }
    }

    System.err.println("OrientDB connection created with " + url);
    dictionary = db.getMetadata().getIndexManager().getDictionary();
    if (!db.getMetadata().getSchema().existsClass(CLASS))
      db.getMetadata().getSchema().createClass(CLASS);

    // TODO: This is a transparent optimization that should be openned up to the user.
    db.declareIntent(new OIntentMassiveInsert());
  }

  @Override
  public void cleanup() throws DBException {
    // Set this thread's db reference (needed for thread safety in testing)
    ODatabaseRecordThreadLocal.INSTANCE.set(db);

    if (db != null) {
      db.close();
      db = null;
    }
  }

  /**
   * Insert a record in the database. Any field/value pairs in the specified
   * values HashMap will be written into the record with the specified record
   * key.
   *
   * @param table
   *          The name of the table
   * @param key
   *          The record key of the record to insert.
   * @param values
   *          A HashMap of field/value pairs to insert in the record
   * @return Zero on success, a non-zero error code on error. See this class's
   *         description for a discussion of error codes.
   */
  @Override
  public Status insert(String table, String key,
      HashMap<String, ByteIterator> values) {
    try {
      final ODocument document = new ODocument(CLASS);
      for (Entry<String, String> entry : StringByteIterator.getStringMap(values)
          .entrySet()) {
        document.field(entry.getKey(), entry.getValue());
      }
      document.save();
      dictionary.put(key, document);

      return Status.OK;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  /**
   * Delete a record from the database.
   *
   * @param table
   *          The name of the table
   * @param key
   *          The record key of the record to delete.
   * @return Zero on success, a non-zero error code on error. See this class's
   *         description for a discussion of error codes.
   */
  @Override
  public Status delete(String table, String key) {
    try {
      dictionary.remove(key);
      return Status.OK;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  /**
   * Read a record from the database. Each field/value pair from the result will
   * be stored in a HashMap.
   *
   * @param table
   *          The name of the table
   * @param key
   *          The record key of the record to read.
   * @param fields
   *          The list of fields to read, or null for all of them
   * @param result
   *          A HashMap of field/value pairs for the result
   * @return Zero on success, a non-zero error code on error or "not found".
   */
  @Override
  public Status read(String table, String key, Set<String> fields,
      HashMap<String, ByteIterator> result) {
    try {
      final ODocument document = dictionary.get(key);
      if (document != null) {
        if (fields != null) {
          for (String field : fields) {
            result.put(field,
                new StringByteIterator((String) document.field(field)));
          }
        } else {
          for (String field : document.fieldNames()) {
            result.put(field,
                new StringByteIterator((String) document.field(field)));
          }
        }
        return Status.OK;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  /**
   * Update a record in the database. Any field/value pairs in the specified
   * values HashMap will be written into the record with the specified record
   * key, overwriting any existing values with the same field name.
   *
   * @param table
   *          The name of the table
   * @param key
   *          The record key of the record to write.
   * @param values
   *          A HashMap of field/value pairs to update in the record
   * @return Zero on success, a non-zero error code on error. See this class's
   *         description for a discussion of error codes.
   */
  @Override
  public Status update(String table, String key,
      HashMap<String, ByteIterator> values) {
    try {
      final ODocument document = dictionary.get(key);
      if (document != null) {
        for (Entry<String, String> entry : StringByteIterator
            .getStringMap(values).entrySet()) {
          document.field(entry.getKey(), entry.getValue());
        }
        document.save();
        return Status.OK;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }

  /**
   * Perform a range scan for a set of records in the database. Each field/value
   * pair from the result will be stored in a HashMap.
   *
   * @param table
   *          The name of the table
   * @param startkey
   *          The record key of the first record to read.
   * @param recordcount
   *          The number of records to read
   * @param fields
   *          The list of fields to read, or null for all of them
   * @param result
   *          A Vector of HashMaps, where each HashMap is a set field/value
   *          pairs for one record
   * @return Zero on success, a non-zero error code on error. See this class's
   *         description for a discussion of error codes.
   */
  @Override
  public Status scan(String table, String startkey, int recordcount, Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
    if (isRemote) {
      // Iterator methods needed for scanning are Unsupported for remote database connections.
      return Status.NOT_IMPLEMENTED;
    }

    try {
      int entrycount = 0;
      final OIndexCursor entries = dictionary.getIndex().iterateEntriesMajor(startkey, true, true);

      while (entries.hasNext() && entrycount < recordcount) {
        final Entry<Object, OIdentifiable> entry = entries.nextEntry();
        final ODocument document = entry.getValue().getRecord();

        final HashMap<String, ByteIterator> map =
            new HashMap<String, ByteIterator>();
        result.add(map);

        for (String field : fields) {
          map.put(field, new StringByteIterator((String) document.field(field)));
        }

        entrycount++;
      }

      return Status.OK;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return Status.ERROR;
  }
}
