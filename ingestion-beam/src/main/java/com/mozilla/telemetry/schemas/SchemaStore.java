/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.mozilla.telemetry.schemas;

import avro.shaded.com.google.common.annotations.VisibleForTesting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.text.StringSubstitutor;

/** Reads schemas from a file and parses them into a map. */
public abstract class SchemaStore<T> implements Serializable {

  /** Return the parsed schema corresponding to a path underneath the schemas/ directory. */
  public T getSchema(String path) throws SchemaNotFoundException {
    ensureSchemasLoaded();
    T schema = schemas.get(path);
    if (schema == null) {
      throw SchemaNotFoundException.forName(path);
    }
    return schema;
  }

  /** Return the parsed schema corresponding to doctype and namespace parsed from attributes. */
  public T getSchema(Map<String, String> attributes) throws SchemaNotFoundException {
    ensureSchemasLoaded();
    if (attributes == null) {
      throw new SchemaNotFoundException("No schema for message with null attributeMap");
    }
    // This is the path provided by mozilla-pipeline-schemas
    final String path = StringSubstitutor.replace("${document_namespace}/${document_type}/"
        + "${document_type}.${document_version}.schema.json", attributes);
    return getSchema(path);
  }

  /** Returns true if we found at least one schema with the given doctype and namespace. */
  public boolean docTypeExists(String namespace, String docType) {
    ensureSchemasLoaded();
    if (namespace == null || docType == null) {
      return false;
    } else {
      return dirs.contains(namespace + "/" + docType);
    }
  }

  /** Returns true if we found at least one schema with matching the given attributes. */
  public boolean docTypeExists(Map<String, String> attributes) {
    String namespace = attributes.get("document_namespace");
    String docType = attributes.get("document_type");
    return docTypeExists(namespace, docType);
  }

  ////////

  protected SchemaStore(ValueProvider<String> schemasLocation,
      @Nullable ValueProvider<String> aliasingConfigurationLocation) {
    this.schemasLocation = schemasLocation;
    this.schemaAliasesLocation = aliasingConfigurationLocation;
  }

  /* Returns true on a valid schema name e.g. `document.schema.json` */
  protected abstract boolean containsSchemaSuffix(String name);

  /* Returns a parsed schema from an archive input stream. */
  protected abstract T loadSchemaFromArchive(ArchiveInputStream archive) throws IOException;

  ////////

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SchemaStore.class);

  private final ValueProvider<String> schemasLocation;
  @Nullable
  private final ValueProvider<String> schemaAliasesLocation;
  private transient Map<String, T> schemas;
  private transient Set<String> dirs;

  @VisibleForTesting
  int numLoadedSchemas() {
    ensureSchemasLoaded();
    return schemas.size();
  }

  private void loadAllSchemas() throws IOException {
    final Map<String, T> tempSchemas = new HashMap<>();
    final Set<String> tempDirs = new HashSet<>();
    Multimap<String, String> schemaAliasesMap;
    InputStream inputStream;
    try {
      schemaAliasesMap = loadSchemaAliasesMap();
      Metadata metadata = FileSystems.matchSingleFileSpec(schemasLocation.get());
      ReadableByteChannel channel = FileSystems.open(metadata.resourceId());
      inputStream = Channels.newInputStream(channel);
    } catch (IOException e) {
      throw new IOException("Exception thrown while loading schemas configuration", e);
    }
    try (InputStream bi = new BufferedInputStream(inputStream);
        InputStream gzi = new GzipCompressorInputStream(bi);
        ArchiveInputStream i = new TarArchiveInputStream(gzi);) {
      ArchiveEntry entry = null;
      while ((entry = i.getNextEntry()) != null) {
        if (!i.canReadEntryData(entry)) {
          LOG.warn("Unable to read tar file entry: " + entry.getName());
          continue;
        }
        String[] components = entry.getName().split("/");
        if (components.length > 2 && "schemas".equals(components[1])) {
          String name = String.join("/", Arrays.copyOfRange(components, 2, components.length));
          if (entry.isDirectory()) {
            tempDirs.add(name);
            if (schemaAliasesMap.containsKey(name)) {
              tempDirs.addAll(schemaAliasesMap.get(name));
            }
            continue;
          }
          if (containsSchemaSuffix(name)) {
            T schema = loadSchemaFromArchive(i);
            tempSchemas.put(name, schema);

            // if aliases are defined for current `name`, assemble new aliasing path and put it to
            // the map with original schema
            String namespaceWithDocType = components[2] + "/" + components[3];
            if (schemaAliasesMap.containsKey(namespaceWithDocType)) {
              for (String namespaceWithDocTypeAlias : schemaAliasesMap.get(namespaceWithDocType)) {
                String docTypeWithVersion = components[components.length - 1];
                String versionAndSuffix = docTypeWithVersion
                    .substring(docTypeWithVersion.indexOf("."));

                String docTypeAlias = namespaceWithDocTypeAlias.split("/")[1];
                String docTypeWithVersionAlias = docTypeAlias + versionAndSuffix;

                tempSchemas.put(namespaceWithDocTypeAlias + "/" + docTypeWithVersionAlias, schema);
              }
            }
          }
        }
      }
    }
    schemas = tempSchemas;
    dirs = tempDirs;
  }

  /**
   * If `schemaAliasesLocation` is set, returns a mapping from schemas to lists of their aliases.
   * Otherwise returns an empty map.
   */
  private Multimap<String, String> loadSchemaAliasesMap() throws IOException {
    Multimap<String, String> aliases = ArrayListMultimap.create();
    if (schemaAliasesLocation != null) {
      Metadata metadata = FileSystems.matchSingleFileSpec(schemaAliasesLocation.get());
      InputStream configInputStream = Channels
          .newInputStream(FileSystems.open(metadata.resourceId()));

      ObjectMapper objectMapper = new ObjectMapper();
      SchemaAliasingConfiguration aliasingConfig = objectMapper.readValue(configInputStream,
          SchemaAliasingConfiguration.class);

      aliasingConfig.getAliases().forEach((e) -> {
        aliases.put(e.getBase(), e.getAlias());
      });
    }
    return aliases;
  }

  private void ensureSchemasLoaded() {
    if (schemas == null) {
      try {
        loadAllSchemas();
      } catch (IOException e) {
        throw new UncheckedIOException("Unexpected error while loading schemas", e);
      }
    }
  }

}
