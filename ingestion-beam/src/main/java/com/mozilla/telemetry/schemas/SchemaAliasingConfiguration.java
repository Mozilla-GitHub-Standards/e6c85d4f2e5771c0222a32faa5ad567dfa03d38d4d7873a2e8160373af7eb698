/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.mozilla.telemetry.schemas;

import static org.apache.commons.lang3.builder.ToStringStyle.SHORT_PREFIX_STYLE;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.apache.commons.lang3.builder.ToStringBuilder;

class SchemaAliasingConfiguration {

  private final List<SchemaAlias> aliases;

  @JsonCreator
  SchemaAliasingConfiguration(
      @JsonProperty(value = "aliases", required = true) List<SchemaAlias> aliases) {
    this.aliases = aliases;
  }

  List<SchemaAlias> getAliases() {
    return aliases;
  }

  @Override
  public String toString() {
    return ToStringBuilder.reflectionToString(this, SHORT_PREFIX_STYLE);
  }
}
